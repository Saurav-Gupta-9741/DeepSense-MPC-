package com.iitj.pervasivesense

import android.os.Build
import android.os.Bundle
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
 * full-screen notification): the driver answers with one large button, without unlocking.
 * Closes itself when the countdown ends (sent or cancelled).
 */
class AlertActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAlertBinding

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
        binding.btnAlertScreenCancel.setOnClickListener { SensingRepository.cancelContactAlert(); finish() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SensingRepository.alert.collect { render(it) }
            }
        }
    }

    private fun render(a: AlertCountdown?) = with(binding) {
        if (a == null) { finish(); return@with }
        val asking = a.kind == AlertKind.UNSTEADY_DRIVING
        tvAlertScreenTitle.text = if (asking) getString(R.string.fit_alert_ask_title)
            else getString(R.string.fit_alert_notify_title, a.contactName)
        tvAlertScreenSeconds.text = getString(R.string.fit_balance_seconds, a.secondsLeft)
        tvAlertScreenDetail.text = ContactAlert.detailText(this@AlertActivity, a.kind, a.contactName)
        btnAlertScreenOk.visibility = if (asking) View.VISIBLE else View.GONE
        btnAlertScreenCancel.setText(if (a.kind == AlertKind.TEST) R.string.fit_cancel else R.string.fit_alert_cancel)
    }
}
