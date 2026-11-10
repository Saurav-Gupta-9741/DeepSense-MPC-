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
import android.telephony.TelephonyManager
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
        /** No signal (e.g. a tunnel, a basement car park): a real alert is tried again every 30 s, 4 more times. */
        private const val RETRY_ROUNDS = 4
        private const val RETRY_MS = 30_000L
        private const val ANSWER_TIMEOUT_MS = 45_000L
        private const val CODE_TIMEOUT = -100
        private const val CODE_EXCEPTION = -101
        private const val PREFS = "contact_alert"
        private const val KEY_LAST_SUB = "last_working_sim"

        /**
         * WhatsApp chat with the contact, the alert written in (sent over the internet, so it works
         * when the SIM cannot text). A 10-digit number gets the SIM's country code (India: 91).
         */
        fun whatsAppIntent(c: Context, phone: String, text: String): Intent {
            val digits = phone.filter { it.isDigit() }
            val iso = runCatching { c.getSystemService(TelephonyManager::class.java)?.simCountryIso }.getOrNull().orEmpty()
            val intl = when {
                phone.trim().startsWith("+") -> digits
                digits.startsWith("00") -> digits.drop(2)
                iso.equals("in", true) && digits.length == 11 && digits.startsWith("0") -> "91" + digits.drop(1)
                iso.equals("in", true) && digits.length == 10 -> "91$digits"
                else -> digits
            }
            return Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl?text=" + Uri.encode(text)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        private const val LOCATION_MAX_AGE_MS = 15 * 60_000L

        fun hasSmsPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

        /** Lets the alert find every SIM (dual SIM: the default one may have no signal). Optional. */
        fun hasPhoneStatePermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

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
        SensingRepository.setFailedSms(null)
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
        deliver(k, FitToDriveStore.contactPhone(context), text, 0)
    }

    /**
     * "Sent" only once the network confirms. Every SIM is tried; a real alert that finds no
     * signal is tried again [RETRY_ROUNDS] times; on failure Messages is one tap away.
     */
    private fun deliver(k: AlertKind, phone: String, text: String, round: Int) {
        sendSms(phone, text) { ok, code, tried ->
            val noSignal = code == SmsManager.RESULT_ERROR_NO_SERVICE || code == SmsManager.RESULT_ERROR_RADIO_OFF ||
                code == SmsManager.RESULT_ERROR_GENERIC_FAILURE
            if (!ok && k != AlertKind.TEST && noSignal && round < RETRY_ROUNDS) {
                Log.w(TAG, "SMS failed ($code), trying again in ${RETRY_MS / 1000} s")
                handler.postDelayed({ deliver(k, phone, text, round + 1) }, RETRY_MS)
                return@sendSms
            }
            if (ok) lastSent[k] = SystemClock.elapsedRealtime()
            val at = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val result = if (ok) context.getString(R.string.fit_alert_sent, contactName(), at)
                else context.getString(R.string.fit_alert_failed, contactName(), at, describe(code)) + " " +
                    context.resources.getQuantityString(R.plurals.fit_sims_tried, tried, tried)
            val n = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(result)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(if (ok) openScreen() else openMessages(phone, text))
                .setAutoCancel(true)
            if (!ok) n.setContentText(context.getString(R.string.fit_alert_open_messages))
                .setStyle(NotificationCompat.BigTextStyle().bigText(result + "\n" + context.getString(R.string.fit_alert_open_messages)))
                .addAction(0, context.getString(R.string.fit_alert_send_whatsapp), PendingIntent.getActivity(context, 34,
                    whatsAppIntent(context, phone, text), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            notificationManager.notify(NOTIFICATION_ID, n.build())
            SensingRepository.setFailedSms(if (ok) null else phone to text)
            SensingRepository.setMessage(result)
            say(context.getString(if (ok) R.string.fit_say_alert_sent else R.string.fit_say_alert_failed))
        }
    }

    private fun describe(code: Int?) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.fit_sms_err_no_service)
        SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.fit_sms_err_radio_off)
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> context.getString(R.string.fit_sms_err_refused)
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> context.getString(R.string.fit_sms_err_limit)
        CODE_TIMEOUT -> context.getString(R.string.fit_sms_err_timeout)
        null, CODE_EXCEPTION -> context.getString(R.string.fit_sms_err_blocked)
        else -> context.getString(R.string.fit_sms_err_other, code)
    }

    /**
     * SIMs to text from, best first: the default SMS SIM, the calls SIM, the mobile-data SIM, then
     * any other active SIM (needs READ_PHONE_STATE). A dual-SIM phone's default may have no signal
     * ("error 4" on a real phone while the other SIM worked), so each is tried in turn.
     */
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission") // checked
    private fun smsSubscriptions(): List<Int> {
        val ids = LinkedHashSet<Int>()
        ids += prefs.getInt(KEY_LAST_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID) // the SIM that worked last time
        ids += SubscriptionManager.getDefaultSmsSubscriptionId()
        ids += SubscriptionManager.getDefaultVoiceSubscriptionId()
        ids += SubscriptionManager.getDefaultDataSubscriptionId()
        ids += SubscriptionManager.getDefaultSubscriptionId()
        ids += slotSubscriptions() // every SIM slot: needs no permission (Android 10+)
        ids.remove(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        ids.removeAll { it < 0 }
        if (hasPhoneStatePermission(context)) {
            val active = runCatching {
                context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList?.map { it.subscriptionId }
            }.getOrNull()
            if (!active.isNullOrEmpty()) { ids.retainAll(active.toSet()); ids += active }
        }
        return ids.toList()
    }

    /**
     * The SIM in each slot. Without the phone-state permission Android hides the list of SIMs, so on
     * a real dual-SIM phone only the default one was tried (one failed text in the thread, not two).
     */
    private fun slotSubscriptions(): List<Int> = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return@runCatching emptyList<Int>()
        @Suppress("DEPRECATION")
        val slots = if (Build.VERSION.SDK_INT >= 30) tm.activeModemCount else tm.phoneCount
        val sm = context.getSystemService(SubscriptionManager::class.java)
        (0 until slots).mapNotNull { slot ->
            if (Build.VERSION.SDK_INT >= 34) SubscriptionManager.getSubscriptionId(slot)
            else if (Build.VERSION.SDK_INT >= 29) {
                @Suppress("DEPRECATION")
                sm?.getSubscriptionIds(slot)?.firstOrNull()
            } else null
        }
    }.getOrDefault(emptyList())

    private fun smsManager(id: Int?): SmsManager = if (Build.VERSION.SDK_INT >= 31) {
        val base = context.getSystemService(SmsManager::class.java)
        if (id != null) base.createForSubscriptionId(id) else base
    } else {
        @Suppress("DEPRECATION")
        if (id != null) SmsManager.getSmsManagerForSubscriptionId(id) else SmsManager.getDefault()
    }

    /** Tries each SIM until one sends; [done] gets the last error code (null: could not even try) and the SIMs tried. */
    private fun sendSms(phone: String, text: String, done: (Boolean, Int?, Int) -> Unit) {
        val subs: List<Int?> = smsSubscriptions().ifEmpty { listOf(null) } // null: Android's default
        Log.i(TAG, "SMS: trying SIM subscriptions $subs")
        var lastCode: Int? = null
        fun attempt(i: Int) {
            if (i >= subs.size) { done(false, lastCode, subs.size); return }
            sendOnce(subs[i], phone, text) { code ->
                when (code) {
                    Activity.RESULT_OK -> {
                        subs[i]?.let { prefs.edit().putInt(KEY_LAST_SUB, it).apply() }
                        done(true, null, i + 1)
                    }
                    // No answer: it may still go out; trying another SIM could send it twice.
                    CODE_TIMEOUT -> done(false, code, i + 1)
                    else -> { Log.w(TAG, "SMS on subscription ${subs[i]} failed: $code"); lastCode = code; attempt(i + 1) }
                }
            }
        }
        attempt(0)
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag") // permission checked in ready()
    private fun sendOnce(sub: Int?, phone: String, text: String, result: (Int) -> Unit) {
        val number = phone.filter { it.isDigit() || it == '+' }
        val action = "com.iitj.pervasivesense.SMS_SENT." + SystemClock.elapsedRealtime() + "." + (sub ?: "default")
        var finished = false
        var receiver: BroadcastReceiver? = null
        fun finish(code: Int) {
            if (finished) return
            finished = true
            receiver?.let { r -> runCatching { context.unregisterReceiver(r) } }
            result(code)
        }
        try {
            val sms = smsManager(sub)
            val parts = sms.divideMessage(text)
            var pending = parts.size
            var failed: Int? = null
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (resultCode != Activity.RESULT_OK && failed == null) failed = resultCode
                    if (--pending > 0) return
                    finish(failed ?: Activity.RESULT_OK)
                }
            }
            receiver = r
            val filter = IntentFilter(action)
            // Delivered on the sensing thread, like everything else here.
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(r, filter, null, handler)
            val sent = ArrayList(parts.indices.map {
                PendingIntent.getBroadcast(context, 40 + it, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            })
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            // No answer from the network: say so rather than claim it was sent.
            handler.postDelayed({ finish(CODE_TIMEOUT) }, ANSWER_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "SMS failed", e)
            finish(CODE_EXCEPTION)
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
