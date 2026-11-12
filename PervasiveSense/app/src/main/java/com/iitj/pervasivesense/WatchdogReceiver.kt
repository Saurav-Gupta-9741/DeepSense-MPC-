package com.iitj.pervasivesense

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps sensing running while the user wants it: the phone's battery saver (or swiping the app
 * away, on some phones) can stop the service, and then nothing would watch a drive. An alarm
 * every [INTERVAL_MS], and one a few seconds after the app is swiped away, start it again if
 * it was on and is not running. If Android refuses (no battery exemption on Android 12+), a
 * notification asks the user to turn it back on with one tap.
 *
 * Limit: "Force stop" (or uninstalling) cancels every alarm; nothing of the app runs until it
 * is opened again. That is an Android rule no app can get around.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SensingForegroundService.wasRunning(context)) { cancel(context); return }
        if (SensingForegroundService.alive) return
        if (!SensingForegroundService.hasActivityRecognitionPermission(context)) { notifyStopped(context); return }
        try {
            ContextCompat.startForegroundService(context, Intent(context, SensingForegroundService::class.java))
            Log.i(TAG, "sensing restarted")
        } catch (e: Exception) {
            // Android 12+: a foreground service may not start from the background without the exemption.
            Log.w(TAG, "could not restart sensing", e)
            notifyStopped(context)
        }
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val INTERVAL_MS = 15 * 60_000L
        private const val SOON_MS = 5_000L
        const val STOPPED_NOTIFICATION_ID = 1006

        private fun intent(context: Context, code: Int) = PendingIntent.getBroadcast(context, code,
            Intent(context, WatchdogReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Every 15 minutes while sensing is on (inexact: no special permission, little battery). */
        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVAL_MS, INTERVAL_MS, intent(context, 50))
        }

        /** The app was swiped away: check again in a few seconds. */
        fun checkSoon(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SOON_MS, intent(context, 51))
        }

        /** Stopped by the user: no more restarts. */
        fun cancel(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.cancel(intent(context, 50))
            am.cancel(intent(context, 51))
        }

        private fun notifyStopped(context: Context) {
            val open = PendingIntent.getActivity(context, 52,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_RESTART, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                nm.notify(STOPPED_NOTIFICATION_ID, NotificationCompat.Builder(context, SensingForegroundService.ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(context.getString(R.string.watchdog_stopped_title))
                    .setContentText(context.getString(R.string.watchdog_stopped_text))
                    .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.watchdog_stopped_text)))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build())
            }
        }
    }
}
