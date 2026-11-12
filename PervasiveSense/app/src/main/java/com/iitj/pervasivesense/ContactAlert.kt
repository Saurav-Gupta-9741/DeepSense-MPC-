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
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
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

enum class AlertKind {
    DRIVING_AFTER_WARNING,
    ERRATIC_DRIVING,
    /** Unsteady driving with no warning: the driver is asked "Are you OK?" first; no answer reaches the contact. */
    UNSTEADY_DRIVING,
    /** A fall: "Are you OK?" first ("I'm OK" or "Get help now"); no answer reaches the contact. */
    FALL,
    TEST
}

/** A contact alert counting down (shown on the fit-to-drive screen). */
data class AlertCountdown(val kind: AlertKind, val secondsLeft: Int, val contactName: String,
                          /** From a "Test drive (no car)": the text says TEST. */
                          val test: Boolean = false)

/**
 * Opt-in alert to the trusted contact: "I may be driving after a fit-to-drive
 * warning". Always a countdown first ([COUNTDOWN_SEC], spoken, with a Cancel
 * button in the notification and on the screen), so a passenger or a false
 * alarm never reaches the contact. Then a text message from the user's own
 * phone, with the location if the user allowed it, then (if allowed) a phone
 * call to the contact on speaker; a fall is never followed by a call ([callsFor]).
 * At most one alert of each kind per hour.
 * Unsteady driving with no warning ([AlertKind.UNSTEADY_DRIVING]) asks the driver
 * first, for [ASK_SEC], spoken again near the end: "I'm OK" or "I'm a passenger"
 * stops it, no answer sends it. The countdown also opens [AlertActivity] over the
 * lock screen. Runs on the service's sensing thread ([handler]).
 */
class ContactAlert(
    private val context: Context,
    private val handler: Handler,
    private val notificationManager: NotificationManager,
    private val channelId: String,
    private val say: (String) -> Unit,
    /** Cancelled with "I'm a passenger": stop watching this trip. */
    private val onPassenger: () -> Unit,
    /** "I'm OK" when asked (unsteady driving: not asked again for a while on this trip; a fall: acknowledged). */
    private val onOk: (AlertKind) -> Unit = {}
) {
    companion object {
        private const val TAG = "ContactAlert"
        const val COUNTDOWN_SEC = 30
        const val TEST_COUNTDOWN_SEC = 10
        /** Unsteady driving with no warning: time to answer "Are you OK?" before the contact is alerted. */
        const val ASK_SEC = 60
        /** ...asked again (spoken) when this many seconds are left. */
        private val ASK_AGAIN_AT = setOf(30, 10)
        const val NOTIFICATION_ID = 1005
        const val ACTION_CANCEL = "com.iitj.pervasivesense.action.CANCEL_ALERT"
        const val ACTION_OK = "com.iitj.pervasivesense.action.DRIVER_OK"
        /** "Get help now" (a fall): the text goes out at once (a fall never calls, see [callsFor]). */
        const val ACTION_HELP_NOW = "com.iitj.pervasivesense.action.HELP_NOW"
        /** The status line ("Alert sent to ...", "Calling ...") clears itself after this long. */
        private const val STATUS_MS = 10 * 60_000L

        /**
         * Whether this kind of alert may phone the contact after the text. A fall only texts: the
         * phone may be lying on the floor away from the person, and an automatic call on speaker
         * from there helps nobody; the text carries the location and asks the contact to call.
         */
        fun callsFor(k: AlertKind) = k != AlertKind.FALL

        /** The question kinds: the person answers "I'm OK" (or nobody answers). */
        fun asks(k: AlertKind) = k == AlertKind.UNSTEADY_DRIVING || k == AlertKind.FALL

        /** "Display over other apps": the alert screen can then be opened directly, on every brand of phone. */
        fun canOpenOverOtherApps(c: Context) = android.provider.Settings.canDrawOverlays(c)

        fun countdownSec(k: AlertKind) = when (k) {
            AlertKind.TEST -> TEST_COUNTDOWN_SEC
            AlertKind.UNSTEADY_DRIVING, AlertKind.FALL -> ASK_SEC
            AlertKind.DRIVING_AFTER_WARNING, AlertKind.ERRATIC_DRIVING -> COUNTDOWN_SEC
        }

        /** What the countdown is about. */
        fun detailText(c: Context, k: AlertKind, contactName: String): String = when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> c.getString(R.string.fit_alert_detail_driving)
            AlertKind.ERRATIC_DRIVING -> c.getString(R.string.fit_alert_detail_erratic)
            AlertKind.UNSTEADY_DRIVING -> c.getString(R.string.fit_alert_detail_unsteady, contactName)
            AlertKind.FALL -> c.getString(R.string.fall_alert_detail, contactName)
            AlertKind.TEST -> c.getString(R.string.fit_alert_detail_test)
        }

        /** Android 14+ lets an app open a screen over the lock screen only with this permission. */
        fun canUseFullScreen(c: Context) = Build.VERSION.SDK_INT < 34 ||
            c.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
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
        /** A fix this accurate (metres) ends the location search early. */
        private const val LOCATION_GOOD_M = 50f
        /** The call follows the text after this long (the text goes first: it carries the location). */
        private const val CALL_DELAY_MS = 10_000L

        fun hasCallPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        /** Opted in to the call, allowed, and the phone can call. */
        fun callsContact(c: Context) = FitToDriveStore.alertCall(c) && hasCallPermission(c) && canSendSms(c)

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
    /** A "Test drive (no car)" alert: the text says TEST, and it never blocks a real alert. */
    private var testRun = false
    private var deadline = 0L
    private var location: Location? = null
    private var locationListeners: List<LocationListener> = emptyList()
    private var callPhone = ""
    private val sayCalling = Runnable { say(context.getString(R.string.fit_say_calling, contactName())) }
    private val call = Runnable { placeCall(callPhone) }
    private val lastSent = HashMap<AlertKind, Long>()
    private val tick = Runnable { update() }
    /** Seconds-left marks already spoken ("Are you OK?" asked again). */
    private val askedAgain = HashSet<Int>()

    val running get() = kind != null

    /**
     * Starts a countdown; false if not set up, one is running or this kind was sent within the hour.
     * [test]: from a "Test drive (no car)".
     */
    fun start(k: AlertKind, test: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        val current = kind
        if (current != null) {
            // Unsteady driving during the "driving after a warning" countdown: say so in the message.
            if (k == AlertKind.ERRATIC_DRIVING && current == AlertKind.DRIVING_AFTER_WARNING) kind = k
            return false
        }
        val last = lastSent[k]
        // At most one alert of a kind per hour (no repeated texts on one bad trip); never for a fall.
        if (k != AlertKind.TEST && k != AlertKind.FALL && !test && last != null && now - last < REPEAT_MS) return false
        if (!ready(context)) return false
        SensingRepository.setFailedSms(null)
        kind = k
        testRun = test
        deadline = now + 1000L * countdownSec(k)
        askedAgain.clear()
        location = null
        requestLocation()
        say(context.getString(when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_say_alert_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_say_alert_erratic
            AlertKind.UNSTEADY_DRIVING -> R.string.fit_say_alert_unsteady
            AlertKind.FALL -> R.string.fall_say_alert
            AlertKind.TEST -> R.string.fit_say_alert_test
        }))
        update() // the countdown is published before the alert screen opens and reads it
        postCountdownNotification(k)
        openAlertScreenNow()
        return true
    }

    /**
     * With "Display over other apps" allowed, the alert screen opens directly as well: some phones
     * (Xiaomi, Oppo, Realme, Vivo...) block the full-screen notification from the background.
     */
    private fun openAlertScreenNow() {
        if (!canOpenOverOtherApps(context)) return
        try {
            context.startActivity(Intent(context, AlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            Log.w(TAG, "alert screen not opened", e)
        }
    }

    /** "Get help now" (a fall): no need to wait for the countdown. */
    fun sendNow() {
        val k = kind ?: return
        if (k == AlertKind.TEST) return
        handler.removeCallbacks(tick)
        deadline = SystemClock.elapsedRealtime()
        update()
    }

    /** A countdown of [k] is no longer needed (the fall was acknowledged on the dashboard, or the person got up). */
    fun cancelKind(k: AlertKind) {
        if (kind != k) return
        stop()
        notificationManager.cancel(NOTIFICATION_ID)
        say(context.getString(R.string.fit_say_alert_cancelled))
    }

    /** [asPassenger]: the user is not driving; the trip is no longer watched. */
    fun cancel(asPassenger: Boolean) {
        val k = kind ?: return
        stop()
        notificationManager.cancel(NOTIFICATION_ID)
        if (asPassenger && k != AlertKind.TEST) onPassenger()
        say(context.getString(R.string.fit_say_alert_cancelled))
    }

    /**
     * "I'm OK", only for the question asked after unsteady driving with no warning (after a warning,
     * only "I'm a passenger" stops the alert: someone impaired would also say they are OK).
     */
    fun ok() {
        val k = kind ?: return
        if (!asks(k)) return
        stop()
        notificationManager.cancel(NOTIFICATION_ID)
        onOk(k)
        say(context.getString(if (k == AlertKind.FALL) R.string.fall_say_ok else R.string.fit_say_alert_ok))
    }

    fun shutdown() {
        stop()
        handler.removeCallbacks(sayCalling)
        handler.removeCallbacks(call)
        handler.removeCallbacks(watchCall)
        handler.removeCallbacks(clearStatus)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    // ------------------------------------------------------------------ the status line
    private var statusText: String? = null
    private val clearStatus = Runnable { if (SensingRepository.message.value == statusText) SensingRepository.setMessage(null) }

    /** A status line on the screens that clears itself after [STATUS_MS] (unless something else replaced it). */
    private fun status(text: String) {
        statusText = text
        SensingRepository.setMessage(text)
        handler.removeCallbacks(clearStatus)
        handler.postDelayed(clearStatus, STATUS_MS)
    }

    /** When the alert text went out ("HH:mm"), for the final status line. */
    private var sentAt = ""
    private var callStart = 0L
    private var sawInCall = false
    private val watchCall = Runnable { checkCall() }

    /**
     * "Calling ..." until the call ends, then "Alert sent to ... : text and call." Android says whether
     * a call is going on only with the phone-state permission; without it the call is taken as over
     * after a minute.
     */
    @SuppressLint("MissingPermission") // a refusal is caught
    private fun checkCall() {
        val elapsed = SystemClock.elapsedRealtime() - callStart
        val inCall = runCatching { context.getSystemService(TelecomManager::class.java)?.isInCall }.getOrNull()
        val goingOn = when (inCall) {
            true -> { sawInCall = true; elapsed < 30 * 60_000L }
            false -> !sawInCall && elapsed < 15_000L // the phone app may not have started the call yet
            null -> elapsed < 60_000L
        }
        if (goingOn) { handler.postDelayed(watchCall, 3_000L); return }
        status(context.getString(R.string.fit_alert_called, contactName(), sentAt))
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
        // The driver may not have heard the first question (music, a call): ask again.
        if (asks(k) && left in ASK_AGAIN_AT && askedAgain.add(left)) {
            say(context.getString(R.string.fit_say_alert_unsteady_again, left))
        }
        SensingRepository.setAlert(AlertCountdown(k, left, contactName(), testRun))
        handler.postDelayed(tick, 1000L)
    }

    private fun contactName() = FitToDriveStore.contactName(context).ifBlank { FitToDriveStore.contactPhone(context) }

    private fun send(k: AlertKind) {
        val loc = location ?: lastKnownLocation()
        val test = testRun
        stop()
        var text = (if (test) context.getString(R.string.fit_sms_test_drive_prefix) else "") + context.getString(when (k) {
            AlertKind.DRIVING_AFTER_WARNING -> R.string.fit_sms_driving
            AlertKind.ERRATIC_DRIVING -> R.string.fit_sms_erratic
            AlertKind.UNSTEADY_DRIVING -> R.string.fit_sms_unsteady
            AlertKind.FALL -> R.string.fall_sms
            AlertKind.TEST -> R.string.fit_sms_test
        })
        if (loc != null) {
            text += context.getString(R.string.fit_sms_location,
                String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", loc.latitude, loc.longitude))
            val ageMin = ((System.currentTimeMillis() - loc.time) / 60_000L).toInt()
            if (ageMin >= 2) text += context.getString(R.string.fit_sms_location_age, ageMin)
        }
        val phone = FitToDriveStore.contactPhone(context)
        deliver(k, phone, text, 0, test)
        // Then a call (a call can get through when a text cannot, and the contact hears the driver).
        // Never after a fall: that alert is the text only.
        if (callsFor(k) && callsContact(context)) {
            callPhone = phone
            handler.removeCallbacks(sayCalling); handler.removeCallbacks(call)
            handler.postDelayed(sayCalling, CALL_DELAY_MS - 4_000L)
            handler.postDelayed(call, CALL_DELAY_MS)
        }
    }

    /**
     * Calls the contact on speaker through Android's phone app (works with the screen locked and
     * the app in the background: no screen of ours is opened). A dual-SIM phone set to "ask every
     * time" would stop to ask, so a SIM is chosen then. On failure: a notification to call by hand.
     */
    @SuppressLint("MissingPermission") // checked
    private fun placeCall(phone: String) {
        if (!hasCallPermission(context) || phone.isBlank()) return
        val number = phone.filter { it.isDigit() || it == '+' }
        val ok = try {
            val tm = context.getSystemService(TelecomManager::class.java) ?: throw IllegalStateException("no telecom")
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)
                callAccount(tm)?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
            tm.placeCall(Uri.fromParts("tel", number, null), extras)
            Log.i(TAG, "calling the contact")
            true
        } catch (e: Exception) {
            Log.e(TAG, "call failed", e)
            false
        }
        if (ok) {
            status(context.getString(R.string.fit_calling, contactName()))
            callStart = SystemClock.elapsedRealtime()
            sawInCall = false
            handler.removeCallbacks(watchCall)
            handler.postDelayed(watchCall, 3_000L)
            return
        }
        val dial = PendingIntent.getActivity(context, 35,
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notificationManager.notify(NOTIFICATION_ID + 1, NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.fit_call_failed, contactName()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(dial)
            .setAutoCancel(true)
            .build())
    }

    /** Null: Android's default SIM for calls. With "ask every time", the SIM that last sent a text, else the first. */
    @SuppressLint("MissingPermission") // needs READ_PHONE_STATE: without it, Android asks
    private fun callAccount(tm: TelecomManager): PhoneAccountHandle? = runCatching {
        if (tm.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL) != null) return@runCatching null
        val accounts = tm.callCapablePhoneAccounts
        val sub = prefs.getInt(KEY_LAST_SUB, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val byText = if (Build.VERSION.SDK_INT >= 31 && sub >= 0) runCatching {
            context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(sub)?.phoneAccountHandle
        }.getOrNull() else null
        byText?.takeIf { it in accounts } ?: accounts.firstOrNull()
    }.getOrNull()

    /**
     * "Sent" only once the network confirms. Every SIM is tried; a real alert that finds no
     * signal is tried again [RETRY_ROUNDS] times; on failure Messages is one tap away.
     */
    private fun deliver(k: AlertKind, phone: String, text: String, round: Int, test: Boolean = false) {
        sendSms(phone, text) { ok, code, tried ->
            val noSignal = code == SmsManager.RESULT_ERROR_NO_SERVICE || code == SmsManager.RESULT_ERROR_RADIO_OFF ||
                code == SmsManager.RESULT_ERROR_GENERIC_FAILURE
            if (!ok && k != AlertKind.TEST && !test && noSignal && round < RETRY_ROUNDS) {
                Log.w(TAG, "SMS failed ($code), trying again in ${RETRY_MS / 1000} s")
                handler.postDelayed({ deliver(k, phone, text, round + 1) }, RETRY_MS)
                return@sendSms
            }
            if (ok && !test) lastSent[k] = SystemClock.elapsedRealtime() // a test never blocks a real alert
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
            if (ok) { sentAt = at; status(result) } else SensingRepository.setMessage(result) // a failure stays until handled
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
        val title = (if (testRun) context.getString(R.string.fit_test_drive_tag) else "") +
            context.getString(when (k) {
                AlertKind.FALL -> R.string.fall_alert_notify_title
                AlertKind.UNSTEADY_DRIVING -> R.string.fit_alert_ask_notify_title
                else -> R.string.fit_alert_notify_title
            }, contactName())
        val text = detailText(context, k, contactName())
        val b = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // readable on the lock screen
            .setWhen(System.currentTimeMillis() + secs * 1000L)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(openAlertScreen(33))
            // Screen off or locked: the alert screen opens over the lock screen (Android 14+: only with
            // the full-screen permission; without it this is a heads-up notification).
            .setFullScreenIntent(openAlertScreen(36), true)
        if (asks(k)) {
            val ok = PendingIntent.getService(context, 37,
                Intent(context, SensingForegroundService::class.java).setAction(ACTION_OK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, context.getString(R.string.fit_alert_ok), ok)
        }
        if (k == AlertKind.FALL) {
            val help = PendingIntent.getService(context, 38,
                Intent(context, SensingForegroundService::class.java).setAction(ACTION_HELP_NOW),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, context.getString(R.string.fall_help_now), help)
        } else {
            b.addAction(0, context.getString(if (k == AlertKind.TEST) R.string.fit_cancel else R.string.fit_alert_cancel), cancel)
        }
        notificationManager.notify(NOTIFICATION_ID, b.build())
    }

    private fun openAlertScreen(code: Int) = PendingIntent.getActivity(context, code,
        Intent(context, AlertActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // ------------------------------------------------------------------ location (optional)
    /** A fresh fix during the countdown, from the network (fast) and GPS (accurate): the more accurate one wins. */
    @SuppressLint("MissingPermission")
    private fun requestLocation() {
        if (!hasLocationPermission(context)) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val listeners = ArrayList<LocationListener>()
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(provider)) continue
                val listener = LocationListener { loc ->
                    val best = location
                    if (best == null || !best.hasAccuracy() || (loc.hasAccuracy() && loc.accuracy <= best.accuracy)) location = loc
                    if (loc.hasAccuracy() && loc.accuracy <= LOCATION_GOOD_M) stopLocation()
                }
                lm.requestLocationUpdates(provider, 0L, 0f, listener, handler.looper)
                listeners += listener
            } catch (e: Exception) {
                Log.w(TAG, "location from $provider unavailable", e)
            }
        }
        locationListeners = listeners
    }

    private fun stopLocation() {
        val ls = locationListeners
        if (ls.isEmpty()) return
        locationListeners = emptyList()
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        for (l in ls) try { lm.removeUpdates(l) } catch (e: Exception) { Log.w(TAG, "removeUpdates", e) }
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
