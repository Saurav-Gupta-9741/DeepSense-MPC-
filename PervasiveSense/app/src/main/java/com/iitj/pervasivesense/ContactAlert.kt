package com.iitj.pervasivesense

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

enum class AlertKind { DRIVING_AFTER_WARNING, ERRATIC_DRIVING, TEST }

/** A contact alert counting down (shown on the fit-to-drive screen). */
data class AlertCountdown(val kind: AlertKind, val secondsLeft: Int, val contactName: String)

/**
 * Opt-in alert to the trusted contact: "I may be driving after a fit-to-drive
 * warning". Always a countdown first ([COUNTDOWN_SEC], spoken, with a Cancel
 * button in the notification and on the screen), so a passenger or a false
 * alarm never reaches the contact. Then a text message from the user's own
 * phone, with the location if the user allowed it. At most one alert of each
 * kind per hour. Runs on the service's sensing thread ([handler]).
 */
class ContactAlert(
    private val context: Context,
    private val handler: Handler,
    private val notificationManager: NotificationManager,
    private val channelId: String,
    private val say: (String) -> Unit,
    /** Cancelled with "I'm a passenger": stop watching this trip. */
    private val onPassenger: () -> Unit
) {
    companion object {
        private const val TAG = "ContactAlert"
        const val COUNTDOWN_SEC = 30
        const val TEST_COUNTDOWN_SEC = 10
        const val NOTIFICATION_ID = 1005
        const val ACTION_CANCEL = "com.iitj.pervasivesense.action.CANCEL_ALERT"
        private const val REPEAT_MS = 3_600_000L
        private const val LOCATION_MAX_AGE_MS = 15 * 60_000L

        fun hasSmsPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        fun hasLocationPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun canSendSms(c: Context) = c.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

        /** Opted in, a contact saved, SMS allowed and possible. */
        fun ready(c: Context) = FitToDriveStore.alertContact(c) && FitToDriveStore.contactPhone(c).isNotBlank() &&
            hasSmsPermission(c) && canSendSms(c)
    }

    private var kind: AlertKind? = null
    private var deadline = 0L
    private var location: Location? = null
    private var locationListener: LocationListener? = null
    private val lastSent = HashMap<AlertKind, Long>()
    private val tick = Runnable { update() }

    val running get() = kind != null

    /** Starts a countdown; false if not set up, one is running or this kind was sent within the hour. */
    fun start(k: AlertKind): Boolean {
        val now = SystemClock.elapsedRealtime()
        val current = kind
        if (current != null) {
            // Unsteady driving during the "driving after a warning" countdown: say so in the message.
            if (k == AlertKind.ERRATIC_DRIVING && current == AlertKind.DRIVING_AFTER_WARNING) kind = k
            return false
        }
        val last = lastSent[k]
        if (k != AlertKind.TEST && last != null && now - last < REPEAT_MS) return false
        if (!ready(context)) return false
        kind = k
        deadline = now + 1000L * (if (k == AlertKind.TEST) TEST_COUNTDOWN_SEC else COUNTDOWN_SEC)
        location = null
        requestLocation()
        say(context.getString(when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_say_alert_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_say_alert_erratic
            AlertKind.TEST -> R.string.fit_say_alert_test
        }))
        postCountdownNotification(k)
        update()
        return true
    }

    /** [asPassenger]: the user is not driving; the trip is no longer watched. */
    fun cancel(asPassenger: Boolean) {
        val k = kind ?: return
        stop()
        notificationManager.cancel(NOTIFICATION_ID)
        if (asPassenger && k != AlertKind.TEST) onPassenger()
        say(context.getString(R.string.fit_say_alert_cancelled))
    }

    fun shutdown() {
        stop()
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun stop() {
        kind = null
        handler.removeCallbacks(tick)
        stopLocation()
        SensingRepository.setAlert(null)
    }

    private fun update() {
        val k = kind ?: return
        val left = ceil((deadline - SystemClock.elapsedRealtime()) / 1000.0).toInt()
        if (left <= 0) { send(k); return }
        SensingRepository.setAlert(AlertCountdown(k, left, contactName()))
        handler.postDelayed(tick, 1000L)
    }

    private fun contactName() = FitToDriveStore.contactName(context).ifBlank { FitToDriveStore.contactPhone(context) }

    private fun send(k: AlertKind) {
        val loc = location ?: lastKnownLocation()
        stop()
        var text = context.getString(when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_sms_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_sms_erratic
            AlertKind.TEST -> R.string.fit_sms_test
        })
        if (loc != null) {
            text += context.getString(R.string.fit_sms_location,
                String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", loc.latitude, loc.longitude))
        }
        val phone = FitToDriveStore.contactPhone(context)
        // "Sent" only once the network confirms; on failure Messages is one tap away.
        sendSms(phone, text) { ok, code ->
            if (ok) lastSent[k] = SystemClock.elapsedRealtime()
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val result = if (ok) context.getString(R.string.fit_alert_sent, contactName(), at)
                else context.getString(R.string.fit_alert_failed, contactName(), at) + " (" + code + ")"
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(result)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(if (ok) openScreen() else openMessages(phone, text))
                .setAutoCancel(true)
            if (!ok) n.setContentText(context.getString(R.string.fit_alert_open_messages))
            notificationManager.notify(NOTIFICATION_ID, n.build())
            SensingRepository.setMessage(result)
            say(context.getString(if (ok) R.string.fit_say_alert_sent else R.string.fit_say_alert_failed))
        }
    }

    /** The SIM to text from: the default SMS SIM, or (dual SIM set to "ask every time") the calls SIM. */
    private fun smsManager(): SmsManager {
        val ids = listOf(SubscriptionManager.getDefaultSmsSubscriptionId(),
            SubscriptionManager.getDefaultVoiceSubscriptionId(), SubscriptionManager.getDefaultSubscriptionId())
        val id = ids.firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (id != null) base.createForSubscriptionId(id) else base
        } else {
            @Suppress("DEPRECATION")
            if (id != null) SmsManager.getSmsManagerForSubscriptionId(id) else SmsManager.getDefault()
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag") // permission checked in ready()
    private fun sendSms(phone: String, text: String, done: (Boolean, String) -> Unit) {
        val number = phone.filter { it.isDigit() || it == '+' }
        val action = "com.iitj.pervasivesense.SMS_SENT." + SystemClock.elapsedRealtime()
        var finished = false
        fun finish(ok: Boolean, code: String) { if (!finished) { finished = true; done(ok, code) } }
        try {
            val sms = smsManager()
            val parts = sms.divideMessage(text)
            var pending = parts.size
            var failed: String? = null
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK && failed == null) failed = "error $resultCode"
                    if (--pending > 0) return
                    runCatching { context.unregisterReceiver(this) }
                    finish(failed == null, failed ?: "")
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, filter)
            val sent = ArrayList(parts.indices.map {
                PendingIntent.getBroadcast(context, 40 + it, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            // No answer from the network within 60 s: say so rather than claim it was sent.
            handler.postDelayed({
                if (!finished) runCatching { context.unregisterReceiver(receiver) }
                finish(false, "no answer from the network")
            }, 60_000L)
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            finish(false, e.javaClass.simpleName)
        }
    }

    /** Opens Messages with the alert written in, to send it by hand. */
    private fun openMessages(phone: String, text: String) = PendingIntent.getActivity(context, 33,
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(phone))).putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun openScreen() = PendingIntent.getActivity(context, 31,
        Intent(context, FitToDriveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun postCountdownNotification(k: AlertKind) {
        val cancel = PendingIntent.getService(context, 32,
            Intent(context, SensingForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val secs = ((deadline - SystemClock.elapsedRealtime()) / 1000).toInt()
        val title = context.getString(R.string.fit_alert_notify_title, contactName())
        val text = context.getString(when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_alert_detail_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_alert_detail_erratic
            AlertKind.TEST -> R.string.fit_alert_detail_test
        })
        notificationManager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setWhen(System.currentTimeMillis() + secs * 1000L)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(openScreen())
            .addAction(0, context.getString(if (k == AlertKind.TEST) R.string.fit_cancel else R.string.fit_alert_cancel), cancel)
            .build())
    }

    // ------------------------------------------------------------------ location (optional)
    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        try {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { lm.isProviderEnabled(it) } ?: return
            val listener = LocationListener { loc -> location = loc; stopLocation() }
            locationListener = listener
            lm.requestLocationUpdates(provider, 0L, 0f, listener, handler.looper)
        } catch (e: Exception) {
            Log.w(TAG, "location unavailable", e)
        }
    }

    private fun stopLocation() {
        val l = locationListener ?: return
        locationListener = null
        try { context.getSystemService(LocationManager::class.java)?.removeUpdates(l) } catch (e: Exception) { Log.w(TAG, "removeUpdates", e) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < LOCATION_MAX_AGE_MS }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
    }
}
