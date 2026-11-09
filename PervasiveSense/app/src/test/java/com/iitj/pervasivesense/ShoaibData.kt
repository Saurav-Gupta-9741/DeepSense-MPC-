package com.iitj.pervasivesense

import java.io.File

/**
 * Shoaib et al. (2014) "Fusion of smartphone motion sensors for physical activity
 * recognition" dataset: 10 people, five phones worn at the same time (left and right
 * jeans pocket, wrist, upper arm, belt), 50 Hz, about 3 min of corridor walking each.
 * Set SHOAIB_DIR to the folder with Participant_1.csv ... Participant_10.csv.
 */
enum class ShoaibPosition(val column: Int) { LEFT_POCKET(0), RIGHT_POCKET(14), WRIST(28), UPPER_ARM(42), BELT(56) }

/** The walking part of one participant at one position as [ax, ay, az, gx, gy, gz] (timestamps rebuilt at 50 Hz). */
fun shoaibWalk(root: String, participant: Int, position: ShoaibPosition, activity: String = "walking"): Recording? {
    val f = File(root, "Participant_$participant.csv")
    if (!f.exists()) return null
    val rows = ArrayList<FloatArray>()
    f.useLines { lines ->
        for (line in lines.drop(2)) {
            val c = line.split(',')
            if (c.size < 70 || c[69].trim() != activity) continue
            val o = position.column
            val v = FloatArray(6)
            try {
                v[0] = c[o + 1].toFloat(); v[1] = c[o + 2].toFloat(); v[2] = c[o + 3].toFloat()
                v[3] = c[o + 7].toFloat(); v[4] = c[o + 8].toFloat(); v[5] = c[o + 9].toFloat()
            } catch (e: NumberFormatException) { continue }
            rows += v
        }
    }
    if (rows.isEmpty()) return null
    return Recording(LongArray(rows.size) { it * 20_000_000L }, rows.toTypedArray())
}
