package com.meta.wearable.dat.externalsampleapps.cameraaccess.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * VoiceToggleTileService — Quick Settings tile that starts/stops [VoiceSessionService].
 *
 * Add the tile from the notification shade edit menu ("VisionClaw Voice") to get
 * a one-tap toggle for the audio foreground service without opening the app.
 */
class VoiceToggleTileService : TileService() {

    companion object {
        private const val TAG = "VoiceToggleTile"
    }

    /** Scope used to observe [VoiceSessionService.isActive] while the tile is visible. */
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observerJob: Job? = null

    // ── TileService lifecycle ────────────────────────────────────────────────

    override fun onStartListening() {
        super.onStartListening()
        // Sync tile immediately with current state…
        updateTile(VoiceSessionService.isActive.value)
        // …then keep it in sync while the tile panel is open.
        observerJob = tileScope.launch {
            VoiceSessionService.isActive.collect { active ->
                updateTile(active)
            }
        }
        Log.d(TAG, "onStartListening — isActive=${VoiceSessionService.isActive.value}")
    }

    override fun onStopListening() {
        super.onStopListening()
        observerJob?.cancel()
        observerJob = null
        Log.d(TAG, "onStopListening")
    }

    override fun onClick() {
        super.onClick()
        val active = VoiceSessionService.isActive.value
        Log.d(TAG, "onClick — currently active=$active")

        if (active) {
            // Send stop intent to the running service.
            val stopIntent = Intent(this, VoiceSessionService::class.java).apply {
                action = VoiceSessionService.ACTION_STOP
            }
            startService(stopIntent)
        } else {
            // Start the foreground service (no special action needed).
            val startIntent = Intent(this, VoiceSessionService::class.java)
            startForegroundService(startIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tileScope.cancel()
    }

    // ── Tile state helpers ───────────────────────────────────────────────────

    private fun updateTile(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "VisionClaw Voice"
        tile.subtitle = if (active) "Active" else "Tap to start"
        tile.icon = Icon.createWithResource(this, R.drawable.camera_access_icon)
        tile.updateTile()
    }
}
