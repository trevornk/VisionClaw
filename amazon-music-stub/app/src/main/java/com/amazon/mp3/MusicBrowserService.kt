package com.amazon.mp3

import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import android.content.pm.ServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * MediaBrowserService that Meta View connects to when the glasses send
 * EXECUTE_TAP_AND_HOLD for com.amazon.mp3. This is the interface that
 * real Amazon Music exposes, and Meta View expects to find it.
 */
class MusicBrowserService : MediaBrowserService() {

    companion object {
        private const val TAG = "AmazonMusicStub"
        private const val CHANNEL_ID = "music_browser"
        private const val NOTIFICATION_ID = 2002

        const val VISIONCLAW_PKG = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
        const val VISIONCLAW_SERVICE = "$VISIONCLAW_PKG.service.VoiceSessionService"
        const val VISIONCLAW_TOGGLE = "$VISIONCLAW_PKG.TOGGLE_VOICE_SESSION"
        const val VISIONCLAW_STOP = "$VISIONCLAW_PKG.STOP_VOICE_SESSION"
    }

    private var mediaSession: MediaSession? = null
    private var isVoiceActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Ready"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        setupMediaSession()
        Log.d(TAG, "MusicBrowserService created")
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "AmazonMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession onPlay → starting VisionClaw")
                    startVisionClaw()
                }

                override fun onPause() {
                    Log.d(TAG, "MediaSession onPause → stopping VisionClaw")
                    stopVisionClaw()
                }

                override fun onStop() {
                    Log.d(TAG, "MediaSession onStop → stopping VisionClaw")
                    stopVisionClaw()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.d(TAG, "MediaSession onPlayFromMediaId: $mediaId")
                    startVisionClaw()
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    Log.d(TAG, "MediaSession onPlayFromSearch: $query")
                    startVisionClaw()
                }

                override fun onCustomAction(action: String, extras: Bundle?) {
                    Log.d(TAG, "MediaSession custom action: $action")
                    if (isVoiceActive) stopVisionClaw() else startVisionClaw()
                }

                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    Log.d(TAG, "MediaSession media button: ${mediaButtonIntent.action}")
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })

            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_NONE, 0, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                    )
                    .build()
            )
            isActive = true
        }

        sessionToken = mediaSession!!.sessionToken
    }

    private fun startVisionClaw() {
        if (isVoiceActive) return
        isVoiceActive = true

        // Go foreground so we can start other foreground services
        startForeground(
            NOTIFICATION_ID,
            buildNotification("VisionClaw Active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )

        // Launch VisionClaw's trampoline activity which provides foreground context
        // for microphone access, then starts VoiceSessionService
        val intent = Intent(VISIONCLAW_TOGGLE).apply {
            component = ComponentName(VISIONCLAW_PKG, "$VISIONCLAW_PKG.VoiceTrampolineActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            Log.d(TAG, "Started VisionClaw via Trampoline")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VisionClaw trampoline: ${e.message}")
            isVoiceActive = false
        }

        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
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
            Log.d(TAG, "Stopped VisionClaw")
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
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
                )
                .build()
        )

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── MediaBrowserService required overrides ──────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "onGetRoot from: $clientPackageName")
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren: $parentId")
        // Return empty list - we don't have any media
        result.sendResult(mutableListOf())
    }

    // ── Notification ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VisionClaw Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionClaw")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
