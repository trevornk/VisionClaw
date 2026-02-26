/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// VoiceOnlyScreen - Audio-only Gemini Live session (no camera/video)
//
// Starts a Gemini Live session via VoiceSessionService (foreground service) so
// the session persists when the app is minimized or the screen turns off.
// The screen only observes state — it does NOT own the session lifecycle.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.externalsampleapps.cameraaccess.gemini.GeminiUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.service.VoiceSessionService
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun VoiceOnlyScreen(
    viewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // ── Observe service state ────────────────────────────────────────────────
    val isActive by VoiceSessionService.isActive.collectAsStateWithLifecycle()
    val connectionState by VoiceSessionService.connectionState.collectAsStateWithLifecycle()
    val isModelSpeaking by VoiceSessionService.isModelSpeaking.collectAsStateWithLifecycle()
    val userTranscript by VoiceSessionService.userTranscript.collectAsStateWithLifecycle()
    val aiTranscript by VoiceSessionService.aiTranscript.collectAsStateWithLifecycle()
    val toolCallStatus by VoiceSessionService.toolCallStatus.collectAsStateWithLifecycle()
    val openClawConnectionState by VoiceSessionService.openClawConnectionState.collectAsStateWithLifecycle()

    // Build a GeminiUiState so GeminiOverlay can render as-is.
    val geminiUiState = GeminiUiState(
        isGeminiActive = isActive,
        connectionState = connectionState,
        isModelSpeaking = isModelSpeaking,
        userTranscript = userTranscript,
        aiTranscript = aiTranscript,
        toolCallStatus = toolCallStatus,
        openClawConnectionState = openClawConnectionState,
    )

    // ── Start the foreground service when this composable first enters the tree ──
    LaunchedEffect(Unit) {
        val intent = Intent(context, VoiceSessionService::class.java)
        context.startForegroundService(intent)
    }

    // ── Do NOT stop the service when navigating away — that's the whole point ──
    DisposableEffect(Unit) {
        onDispose { /* intentionally empty — service persists in background */ }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        // Gemini overlay (status + transcripts + tool call indicator) — top of screen
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 8.dp),
        ) {
            GeminiOverlay(uiState = geminiUiState)
        }

        // Center label
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "🎙️",
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                text = "Voice Mode",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Listening via Bluetooth",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
            )
        }

        // Stop button at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SwitchButton(
                label = "Stop",
                onClick = {
                    // Stop the service (it persists across navigation, so we must stop it explicitly).
                    val stopIntent = Intent(context, VoiceSessionService::class.java).apply {
                        action = VoiceSessionService.ACTION_STOP
                    }
                    context.startService(stopIntent)
                    // Navigate back to device selection.
                    viewModel.navigateToDeviceSelection()
                },
            )
        }
    }
}
