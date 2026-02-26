package com.amazon.mp3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent

/**
 * Receives MEDIA_BUTTON broadcasts from the system (Bluetooth media commands
 * from the glasses) and forwards them to MediaBridgeService.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AmazonMusicStub"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return

        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return

        Log.d(TAG, "Media button received: keyCode=${event.keyCode}")

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                val serviceIntent = Intent(context, MediaBridgeService::class.java).apply {
                    action = "PLAY_RECEIVED"
                }
                context.startForegroundService(serviceIntent)
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                val serviceIntent = Intent(context, MediaBridgeService::class.java).apply {
                    action = "STOP_RECEIVED"
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
