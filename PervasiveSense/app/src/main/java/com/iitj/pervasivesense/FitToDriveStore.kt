package com.iitj.pervasivesense

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fit-to-drive settings (SharedPreferences) and learned data (a private JSON
 * file, written atomically). Nothing here ever leaves the phone.
 */
object FitToDriveStore {
    private const val TAG = "FitToDriveStore"
    private const val PREFS = "fit_to_drive"
    private const val FILE = "fit_to_drive.json"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("enabled", on).apply()

    /** Detect the car's Bluetooth to check before the car moves (opt-in). */
    fun useCarBluetooth(c: Context) = prefs(c).getBoolean("car_bluetooth", false)
    fun setUseCarBluetooth(c: Context, on: Boolean) = prefs(c).edit().putBoolean("car_bluetooth", on).apply()

    fun contactName(c: Context): String = prefs(c).getString("contact_name", "") ?: ""
    fun contactPhone(c: Context): String = prefs(c).getString("contact_phone", "") ?: ""
    fun setContact(c: Context, name: String, phone: String) =
        prefs(c).edit().putString("contact_name", name.trim()).putString("contact_phone", phone.trim()).apply()

    /** Study session label (validation with impairment-simulation goggles); OFF = everyday use. */
    fun studyCondition(c: Context): StudyCondition =
        StudyCondition.entries.firstOrNull { it.name == prefs(c).getString("study_condition", null) } ?: StudyCondition.OFF
    fun setStudyCondition(c: Context, s: StudyCondition) = prefs(c).edit().putString("study_condition", s.name).apply()
    fun studyParticipant(c: Context): String = prefs(c).getString("study_participant", "") ?: ""
    fun setStudyParticipant(c: Context, id: String) = prefs(c).edit().putString("study_participant", id.trim()).apply()

    // ------------------------------------------------------------------ learned data
    fun save(c: Context, s: FitToDriveState) {
        val baseline = JSONArray()
        for (e in s.baseline) {
            val row = JSONArray().put(e.wallMillis).put(e.dayKey)
            e.values.forEach { row.put(num(it)) }
            baseline.put(row)
        }
        val balance = JSONArray()
        for (r in s.balanceReferences) balance.put(JSONArray().put(r.wallMillis).put(num(r.swayAccel)).put(num(r.swayGyro)))
        val json = JSONObject().put("version", 1).put("baseline", baseline).put("balance", balance).toString()

        val file = AtomicFile(File(c.filesDir, FILE))
        val out = try { file.startWrite() } catch (e: Exception) { Log.w(TAG, "save", e); return }
        try {
            out.write(json.toByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            Log.w(TAG, "save", e)
            file.failWrite(out)
        }
    }

    fun load(c: Context): FitToDriveState? = try {
        val o = JSONObject(String(AtomicFile(File(c.filesDir, FILE)).readFully()))
        val b = o.getJSONArray("baseline")
        val baseline = (0 until b.length()).map { i ->
            val row = b.getJSONArray(i)
            BaselineEntry(row.getLong(0), row.getString(1), FloatArray(GaitFeature.COUNT) { k -> flt(row, 2 + k) })
        }
        val r = o.getJSONArray("balance")
        val refs = (0 until r.length()).map { i ->
            val row = r.getJSONArray(i)
            BalanceReference(row.getLong(0), flt(row, 1), flt(row, 2))
        }
        FitToDriveState(baseline, refs)
    } catch (e: java.io.FileNotFoundException) {
        null
    } catch (e: Exception) {
        Log.w(TAG, "load: discarding unreadable data", e)
        null
    }

    /** JSON has no NaN (no gyroscope): stored as null. */
    private fun num(v: Float): Any = if (v.isNaN()) JSONObject.NULL else v.toDouble()
    private fun flt(a: JSONArray, i: Int): Float = if (i >= a.length() || a.isNull(i)) Float.NaN else a.getDouble(i).toFloat()
}
