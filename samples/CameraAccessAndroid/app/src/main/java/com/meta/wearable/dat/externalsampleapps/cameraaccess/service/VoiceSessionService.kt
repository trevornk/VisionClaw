package com.meta.wearable.dat.externalsampleapps.cameraaccess.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.AudioManager
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiLiveService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawBridge
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.OpenClawConnectionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallRouter
import com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw.ToolCallStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VoiceSessionService - Foreground service for audio-only Gemini Live sessions.
 *
 * Keeps the Gemini WebSocket connection, mic capture, and audio playback alive
 * when the app is minimized or the screen is off. Shows a persistent notification
 * so Android doesn't kill the process.
 *
 * UI observes state via companion-object StateFlows (no binding required).
 */
class VoiceSessionService : Service() {

    companion object {
        private const val TAG = "VoiceSessionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_session"

        /** Intent action to stop the session (from notification button or UI). */
        const val ACTION_STOP = "com.meta.wearable.dat.externalsampleapps.cameraaccess.STOP_VOICE_SESSION"
        const val ACTION_START = "com.meta.wearable.dat.externalsampleapps.cameraaccess.START_VOICE_SESSION"
        const val ACTION_TOGGLE = "com.meta.wearable.dat.externalsampleapps.cameraaccess.TOGGLE_VOICE_SESSION"

        // ── Public state for UI to observe ──────────────────────────────────────

        private val _isActive = MutableStateFlow(false)
        val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

        private val _connectionState = MutableStateFlow<GeminiConnectionState>(GeminiConnectionState.Disconnected)
        val connectionState: StateFlow<GeminiConnectionState> = _connectionState.asStateFlow()

        private val _isModelSpeaking = MutableStateFlow(false)
        val isModelSpeaking: StateFlow<Boolean> = _isModelSpeaking.asStateFlow()

        private val _userTranscript = MutableStateFlow("")
        val userTranscript: StateFlow<String> = _userTranscript.asStateFlow()

        private val _aiTranscript = MutableStateFlow("")
        val aiTranscript: StateFlow<String> = _aiTranscript.asStateFlow()

        private val _toolCallStatus = MutableStateFlow<ToolCallStatus>(ToolCallStatus.Idle)
        val toolCallStatus: StateFlow<ToolCallStatus> = _toolCallStatus.asStateFlow()

        private val _openClawConnectionState = MutableStateFlow<OpenClawConnectionState>(OpenClawConnectionState.NotConfigured)
        val openClawConnectionState: StateFlow<OpenClawConnectionState> = _openClawConnectionState.asStateFlow()
    }

    // ── Service internals ────────────────────────────────────────────────────

    /** Coroutine scope tied to this service instance lifecycle. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Own instances — NOT shared with any ViewModel.
    private val geminiService = GeminiLiveService()
    private val audioManager = AudioManager()
    private val openClawBridge = OpenClawBridge()
    private var toolCallRouter: ToolCallRouter? = null

    private var stateObservationJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Service lifecycle ────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopVoiceSession()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (_isActive.value) {
                    Log.d(TAG, "Toggle → stopping")
                    stopVoiceSession()
                    return START_NOT_STICKY
                }
                Log.d(TAG, "Toggle → starting")
            }
        }

        // Promote to foreground. Try mic type first for proper access,
        // fall back to mediaPlayback if background-restricted.
        // Note: RECORD_AUDIO permission is granted; the FGS type is just a declaration.
        // On SDK 33, AudioRecord should still work even with mediaPlayback type.
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            Log.d(TAG, "Started with mic+mediaPlayback FGS type")
        } catch (e: Exception) {
            Log.w(TAG, "Mic FGS denied, using mediaPlayback only: ${e.message}")
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        }

        startVoiceSession()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopVoiceSession()
        serviceScope.cancel()
        releaseWakeLock()
        Log.d(TAG, "Service destroyed")
    }

    // ── Session management ───────────────────────────────────────────────────

    private fun startVoiceSession() {
        if (_isActive.value) return

        _isActive.value = true
        _userTranscript.value = ""
        _aiTranscript.value = ""
        _toolCallStatus.value = ToolCallStatus.Idle
        _isModelSpeaking.value = false

        // ── Audio callbacks ──────────────────────────────────────────────────

        // Phone/voice-only mode: mute mic while model speaks to prevent echo.
        audioManager.onAudioCaptured = lambda@{ data ->
            if (geminiService.isModelSpeaking.value) return@lambda
            geminiService.sendAudio(data)
        }

        geminiService.onAudioReceived = { data ->
            audioManager.playAudio(data)
        }

        geminiService.onInterrupted = {
            audioManager.stopPlayback()
        }

        geminiService.onTurnComplete = {
            _userTranscript.value = ""
        }

        geminiService.onInputTranscription = { text ->
            _userTranscript.value = _userTranscript.value + text
            _aiTranscript.value = ""
        }

        geminiService.onOutputTranscription = { text ->
            _aiTranscript.value = _aiTranscript.value + text
        }

        geminiService.onDisconnected = { reason ->
            if (_isActive.value) {
                Log.w(TAG, "Disconnected: $reason")
                stopVoiceSession()
            }
        }

        // ── Connect & start ──────────────────────────────────────────────────

        serviceScope.launch {
            // Check OpenClaw reachability and start fresh conversation.
            openClawBridge.checkConnection()
            openClawBridge.resetSession()

            // Wire tool calls.
            toolCallRouter = ToolCallRouter(openClawBridge, serviceScope)

            geminiService.onToolCall = { toolCall ->
                for (call in toolCall.functionCalls) {
                    toolCallRouter?.handleToolCall(call) { response ->
                        geminiService.sendToolResponse(response)
                    }
                }
            }

            geminiService.onToolCallCancellation = { cancellation ->
                toolCallRouter?.cancelToolCalls(cancellation.ids)
            }

            // Continuously mirror service state into companion StateFlows for UI.
            stateObservationJob = serviceScope.launch {
                while (isActive) {
                    delay(100)
                    _connectionState.value = geminiService.connectionState.value
                    _isModelSpeaking.value = geminiService.isModelSpeaking.value
                    _toolCallStatus.value = openClawBridge.lastToolCallStatus.value
                    _openClawConnectionState.value = openClawBridge.connectionState.value
                }
            }

            // Connect to Gemini Live.
            geminiService.connect { setupOk ->
                if (!setupOk) {
                    val msg = when (val state = geminiService.connectionState.value) {
                        is GeminiConnectionState.Error -> state.message
                        else -> "Failed to connect to Gemini"
                    }
                    Log.e(TAG, "Connection failed: $msg")
                    stopVoiceSession()
                    return@connect
                }

                // Start microphone capture now that we're connected.
                try {
                    audioManager.startCapture()
                    Log.d(TAG, "Audio capture started")
                } catch (e: Exception) {
                    Log.e(TAG, "Mic capture failed: ${e.message}")
                    geminiService.disconnect()
                    stateObservationJob?.cancel()
                    stopVoiceSession()
                }
            }
        }
    }

    private fun stopVoiceSession() {
        toolCallRouter?.cancelAll()
        toolCallRouter = null

        audioManager.stopCapture()
        geminiService.disconnect()

        stateObservationJob?.cancel()
        stateObservationJob = null

        // Reset public state so UI reflects stopped state.
        _isActive.value = false
        _connectionState.value = GeminiConnectionState.Disconnected
        _isModelSpeaking.value = false

        stopSelf()
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "VisionClaw voice session active in background"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // "Stop" action — sends ACTION_STOP back to the service.
        val stopIntent = Intent(this, VoiceSessionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Tap notification → re-open app.
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionClaw - Voice Active")
            .setContentText("Tap to return · voice session running")
            .setSmallIcon(R.drawable.sound_icon)
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Stop",
                    stopPendingIntent,
                ).build()
            )
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    // ── Wake lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VisionClaw:VoiceSessionWakeLock",
        ).apply {
            // Cap at 4 hours as a safety valve; the service should be stopped explicitly.
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
