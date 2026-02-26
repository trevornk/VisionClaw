package com.meta.wearable.dat.externalsampleapps.cameraaccess.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.MainActivity

class GlassesMediaBrowserService : MediaBrowserService() {

    companion object {
        private const val TAG = "VisionClaw"
        private const val CHANNEL_ID = "glasses_bridge"
        private const val NOTIFICATION_ID = 3001
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
            Log.d(TAG, "GlassesMediaBrowserService: started with mediaPlayback+microphone")
        } catch (e: Exception) {
            Log.w(TAG, "GlassesMediaBrowserService: mic denied, using mediaPlayback only: ${e.message}")
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }
        setupMediaSession()
        Log.d(TAG, "GlassesMediaBrowserService created (persistent)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "GlassesMediaBrowserService onStartCommand")
        // Keep running even if killed
        return START_STICKY
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "AmazonMusic").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "Glasses: onPlay → toggle")
                    toggleVoice()
                }

                override fun onPause() {
                    Log.d(TAG, "Glasses: onPause → stop")
                    stopVoice()
                }

                override fun onStop() {
                    Log.d(TAG, "Glasses: onStop → stop")
                    stopVoice()
                }

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    Log.d(TAG, "Glasses: onPlayFromMediaId=$mediaId → toggle")
                    toggleVoice()
                }

                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    Log.d(TAG, "Glasses: onPlayFromSearch=$query → toggle")
                    toggleVoice()
                }
            })

            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PAUSED, 0, 1.0f)
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or
                        PlaybackState.ACTION_PLAY_FROM_SEARCH
                    )
                    .build()
            )
            isActive = true
        }

        sessionToken = mediaSession!!.sessionToken
    }

    private fun toggleVoice() {
        Log.d(TAG, "Glasses: starting VoiceSessionService TOGGLE")
        val intent = Intent(VoiceSessionService.ACTION_TOGGLE, null, this, VoiceSessionService::class.java)
        startForegroundService(intent)
    }

    private fun stopVoice() {
        Log.d(TAG, "Glasses: starting VoiceSessionService STOP")
        val intent = Intent(VoiceSessionService.ACTION_STOP, null, this, VoiceSessionService::class.java)
        startForegroundService(intent)
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d(TAG, "Glasses: onGetRoot from $clientPackageName")
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Glasses Bridge",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("VisionClaw")
            .setContentText("Ready for glasses")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        Log.d(TAG, "GlassesMediaBrowserService destroyed — restarting")
        mediaSession?.release()
        mediaSession = null
        // Self-restart
        val restartIntent = Intent(this, GlassesMediaBrowserService::class.java)
        startForegroundService(restartIntent)
        super.onDestroy()
    }
}
