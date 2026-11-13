package com.iitj.pervasivesense

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken guidance for the fit-to-drive check: the phone is in a pocket or held
 * against the chest, so the screen cannot be read. Speaks in the phone's language
 * if the voice supports it, otherwise English; the beep always plays, so the
 * user gets a cue even on phones without a voice engine. Safe to call from any thread.
 */
class FitVoice(context: Context) {
    private val tone: ToneGenerator? = try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (e: RuntimeException) { null }
    @Volatile private var ready = false
    /** Said as soon as the voice engine is ready (the first sentence comes before it is). */
    @Volatile private var pending: String? = null
    @Volatile private var pendingAlarm = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS && chooseLanguage()
            val p = pending
            pending = null
            if (ready && p != null) tts?.speak(p, TextToSpeech.QUEUE_FLUSH, params(pendingAlarm), "fit")
        }
    }

    private fun chooseLanguage(): Boolean {
        val t = tts ?: return false
        for (locale in listOf(Locale.getDefault(), Locale.ENGLISH)) {
            val r = t.setLanguage(locale)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) return true
        }
        return false
    }

    /**
     * [interrupt] cuts off what is being said; otherwise the sentence is queued. [alarm]: on the alarm
     * volume, so an alert is heard with the phone on silent, vibrate or Do Not Disturb (alarms allowed).
     */
    fun say(text: String, interrupt: Boolean = true, alarm: Boolean = false) {
        if (ready) tts?.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params(alarm), "fit")
        else { pending = text; pendingAlarm = alarm }
        beep()
    }

    private fun params(alarm: Boolean): Bundle? =
        if (alarm) Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM) } else null

    fun beep() { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) }

    fun stop() { pending = null; tts?.stop() }

    fun shutdown() {
        tts?.shutdown(); tts = null
        tone?.release()
    }
}
