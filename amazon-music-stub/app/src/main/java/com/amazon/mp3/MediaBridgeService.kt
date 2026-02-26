package com.amazon.mp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import android.content.pm.ServiceInfo

/**
 * Fake media service that registers a MediaSession so the Ray-Ban glasses
 * think Amazon Music is playing. When play/pause is received via Bluetooth,
 * it forwards the command to VisionClaw's VoiceSessionService.
 */
class MediaBridgeService : Service() {

    companion object {
        private const val TAG = "AmazonMusicStub"
        private const val CHANNEL_ID = "media_bridge"
        private const val NOTIFICATION_ID = 2001

        const val VISIONCLAW_PKG = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
        const val VISIONCLAW_SERVICE = "$VISIONCLAW_PKG.service.VoiceSessionService"
        const val VISIONCLAW_TOGGLE = "$VISIONCLAW_PKG.TOGGLE_VOICE_SESSION"
        const val VISIONCLAW_START = "$VISIONCLAW_PKG.START_VOICE_SESSION"
        const val VISIONCLAW_STOP = "$VISIONCLAW_PKG.STOP_VOICE_SESSION"
    }

    private var mediaSession: MediaSession? = null
    private var isVoiceActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        setupMediaSession()
        Log.d(TAG, "MediaBridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // If started by MediaButtonReceiver with a play action, toggle VisionClaw
        if (intent?.action == "PLAY_RECEIVED") {
            toggleVisionClaw()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
        Log.d(TAG, "MediaBridgeService destroyed")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "AmazonMusicStub").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession: onPlay")
                    toggleVisionClaw()
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession: onPause")
                    stopVisionClaw()
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession: onStop")
                    stopVisionClaw()
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    Log.d(TAG, "MediaSession: media button event: ${mediaButtonIntent.action}")
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })

            // Set as active and "playing" so we're the preferred media session
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .build()
            )
            isActive = true
        }
    }

    private fun toggleVisionClaw() {
        Log.d(TAG, "Toggling VisionClaw voice session")
        isVoiceActive = !isVoiceActive

        val action = if (isVoiceActive) VISIONCLAW_START else VISIONCLAW_STOP
        val intent = Intent(action).apply {
            component = ComponentName(VISIONCLAW_PKG, VISIONCLAW_SERVICE)
        }

        try {
            startForegroundService(intent)
            Log.d(TAG, "Sent $action to VisionClaw")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VisionClaw: ${e.message}")
            isVoiceActive = false
        }

        // Update playback state to reflect current state
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(
                    if (isVoiceActive) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    0, 1.0f
                )
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_PLAY_PAUSE
                )
                .build()
        )
    }

    private fun stopVisionClaw() {
        if (!isVoiceActive) return
        isVoiceActive = false

        val intent = Intent(VISIONCLAW_STOP).apply {
            component = ComponentName(VISIONCLAW_PKG, VISIONCLAW_SERVICE)
        }

        try {
            startForegroundService(intent)
            Log.d(TAG, "Sent STOP to VisionClaw")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop VisionClaw: ${e.message}")
        }

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_PLAY_PAUSE
                )
                .build()
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VisionClaw Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Bridges glasses commands to VisionClaw"
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionClaw Bridge")
            .setContentText("Ready for glasses commands")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
