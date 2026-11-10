package com.iitj.pervasivesense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * After the phone restarts or the app is updated, sensing starts again if it was running
 * (the fall check and the fit-to-drive watch must not silently stop). Android allows a
 * foreground service to start from these two broadcasts.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SensingForegroundService.wasRunning(context)) return
        // Android 14+: the health-type service needs the Physical activity permission.
        if (!SensingForegroundService.hasActivityRecognitionPermission(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java))
        } catch (e: Exception) {
            Log.w("BootReceiver", "could not restart sensing", e)
        }
    }
}
