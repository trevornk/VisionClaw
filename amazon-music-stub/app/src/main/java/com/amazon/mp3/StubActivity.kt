package com.amazon.mp3

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal activity so the app shows up as installed.
 * Immediately starts the MediaBridgeService and finishes.
 */
class StubActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the media bridge service
        startForegroundService(Intent(this, MediaBridgeService::class.java))

        // Close immediately - no UI needed
        finish()
    }
}
