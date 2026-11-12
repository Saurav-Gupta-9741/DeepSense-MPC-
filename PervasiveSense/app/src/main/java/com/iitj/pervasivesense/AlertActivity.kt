package com.iitj.pervasivesense

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.iitj.pervasivesense.databinding.ActivityAlertBinding
import kotlinx.coroutines.launch

/**
 * A contact alert counting down, full screen and over the lock screen (opened by the alert's
 * full-screen notification, or directly with "Display over other apps"): one large answer, no
 * unlocking. It vibrates until answered. Closes itself when the countdown ends (sent or cancelled).
 */
class AlertActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlertBinding
    private var vibrating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnAlertScreenOk.setOnClickListener { SensingRepository.driverOk(); finish() }
        binding.btnAlertScreenHelp.setOnClickListener { SensingRepository.helpNow() }
        binding.btnAlertScreenCancel.setOnClickListener { SensingRepository.cancelContactAlert(); finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SensingRepository.alert.collect { render(it) }
            }
        }
    }

    override fun onStop() {
        vibrate(false)
        super.onStop()
    }

    private fun render(a: AlertCountdown?) = with(binding) {
        if (a == null) { finish(); return@with }
        val asking = ContactAlert.asks(a.kind)
        val fall = a.kind == AlertKind.FALL
        tvAlertScreenTitle.text = (if (a.test) getString(R.string.fit_test_drive_tag) else "") + when {
            fall -> getString(R.string.fall_alert_title)
            asking -> getString(R.string.fit_alert_ask_title)
            else -> getString(R.string.fit_alert_notify_title, a.contactName)
        }
        tvAlertScreenSeconds.text = getString(R.string.fit_balance_seconds, a.secondsLeft)
        tvAlertScreenDetail.text = ContactAlert.detailText(this@AlertActivity, a.kind, a.contactName)
        btnAlertScreenOk.visibility = if (asking) View.VISIBLE else View.GONE
        btnAlertScreenHelp.visibility = if (fall) View.VISIBLE else View.GONE
        btnAlertScreenCancel.visibility = if (fall) View.GONE else View.VISIBLE
        btnAlertScreenCancel.setText(if (a.kind == AlertKind.TEST) R.string.fit_cancel else R.string.fit_alert_cancel)
        vibrate(asking)
    }

    /** A repeating buzz while a question waits for an answer (the phone may be in a pocket or on the floor). */
    private fun vibrate(on: Boolean) {
        if (on == vibrating) return
        vibrating = on
        @Suppress("DEPRECATION")
        val v = getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        if (!on) { v.cancel(); return }
        val pattern = longArrayOf(0, 600, 400, 600, 1400)
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, 0))
    }
}
