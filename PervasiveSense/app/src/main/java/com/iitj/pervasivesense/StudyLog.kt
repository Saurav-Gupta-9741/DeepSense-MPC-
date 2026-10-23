package com.iitj.pervasivesense

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.util.Locale

/** Label of a validation session. Learning is paused for every label except OFF. */
enum class StudyCondition { OFF, SOBER, GOGGLES_LOW, GOGGLES_HIGH }

/**
 * Study log for validating the fit-to-drive check (impairment-simulation
 * goggles, docs/FIT_TO_DRIVE.md): while a session is labelled, every walking
 * segment, walk check and balance result is appended as one CSV row. The file
 * stays in private app storage until the user exports it.
 */
object StudyLog {
    private const val TAG = "StudyLog"
    private const val FILE = "fit_study_log.csv"
    const val HEADER = "time,participant,condition,event,trigger,level,distance_or_score,percentile," +
        "unusual_at,large_at,cadence_spm,stride_time_cv,stride_regularity,sway,sway_irregularity," +
        "turn_unsteadiness,balance_outcome,sway_accel,sway_gyro,balance_ratio"
    private val COLUMNS = HEADER.split(',').size

    @Volatile private var rows = -1

    private fun file(c: Context) = File(c.filesDir, FILE)

    @Synchronized
    fun append(c: Context, fields: List<Any?>) {
        require(fields.size == COLUMNS) { "expected $COLUMNS columns" }
        val f = file(c)
        try {
            val existed = f.exists()
            FileWriter(f, true).use { w ->
                if (!existed) w.write(HEADER + "\n")
                w.write(fields.joinToString(",") { format(it) } + "\n")
            }
            rows = if (rows < 0 || !existed) countRows(f) else rows + 1
        } catch (e: Exception) {
            Log.w(TAG, "append", e)
        }
    }

    @Synchronized
    fun rowCount(c: Context): Int {
        if (rows < 0) rows = countRows(file(c))
        return rows
    }

    /** Copies the log to a user-chosen document; false if there is nothing to export or it failed. */
    @Synchronized
    fun exportTo(c: Context, uri: Uri): Boolean {
        val f = file(c)
        if (!f.exists()) return false
        return try {
            c.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } != null
        } catch (e: Exception) {
            Log.w(TAG, "export", e)
            false
        }
    }

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
        rows = 0
    }

    private fun countRows(f: File): Int =
        if (!f.exists()) 0 else f.bufferedReader().useLines { lines -> (lines.count() - 1).coerceAtLeast(0) }

    private fun format(v: Any?): String = when (v) {
        null -> ""
        is Float -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.4f", v)
        is Double -> if (v.isNaN()) "" else String.format(Locale.ROOT, "%.4f", v)
        else -> v.toString().replace(Regex("[,\"\\r\\n]"), " ")
    }
}
