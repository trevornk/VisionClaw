package com.meta.wearable.dat.externalsampleapps.cameraaccess.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("VisionClaw", "Boot/update received — starting GlassesMediaBrowserService")
            val svcIntent = Intent(context, GlassesMediaBrowserService::class.java)
            context.startForegroundService(svcIntent)
        }
    }
}
