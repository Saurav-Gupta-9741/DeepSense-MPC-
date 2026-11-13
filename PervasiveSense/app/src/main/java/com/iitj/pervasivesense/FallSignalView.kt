package com.iitj.pervasivesense

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The acceleration of one judged drop, |a| in g against time (0 = the impact): the fall (lighter than
 * 0.75 g, shaded amber), the impact (red line), and the window where it must lie still (shaded green).
 * Made to be screenshotted for a report.
 */
@SuppressLint("ViewConstructor")
class FallSignalView(context: Context, private val v: FallVerdict) : View(context) {

    private val dp = resources.displayMetrics.density
    private fun col(id: Int) = ContextCompat.getColor(context, id)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.primary_cyan); style = Paint.Style.STROKE; strokeWidth = 2f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.card_border); style = Paint.Style.STROKE; strokeWidth = 1f * dp
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.text_muted); style = Paint.Style.STROKE; strokeWidth = 1f * dp
        pathEffect = DashPathEffect(floatArrayOf(6f * dp, 4f * dp), 0f)
    }
    private val impact = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = col(R.color.accent_rose); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp
    }
    private val fallShade = Paint().apply { color = col(R.color.accent_amber); alpha = 70 }
    private val stillShade = Paint().apply { color = col(R.color.accent_emerald); alpha = 45 }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col(R.color.text_muted); textSize = 10f * dp }
    private val bg = Paint().apply { color = col(R.color.card_dark) }
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (230 * dp).toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val tr = v.trace
        if (tr.size < 2) return
        val left = 34f * dp; val right = width - 10f * dp
        val top = 12f * dp; val bottom = height - 22f * dp
        val n = tr.size
        val yMax = min(16f, max(4f, ceil(tr.max())))
        fun x(i: Int) = left + (right - left) * i / (n - 1).toFloat()
        fun y(g: Float) = bottom - (bottom - top) * min(g, yMax) / yMax

        // The fall: the run lighter than 0.75 g that ends closest before the impact.
        var end = v.impactIndex - 1
        while (end > 0 && tr[end] >= 0.75f && v.impactIndex - end < 15) end--
        var start = end
        while (start > 0 && tr[start - 1] < 0.75f) start--
        if (end >= 0 && tr[end] < 0.75f) c.drawRect(x(start), top, x(end + 1), bottom, fallShade)
        // Where it must lie still: 1.5 s after the impact, for 6 s.
        val s0 = v.impactIndex + 75; val s1 = min(n - 1, v.impactIndex + 375)
        if (s0 < s1) c.drawRect(x(s0), top, x(s1), bottom, stillShade)

        // Horizontal guides: 0, 1 g (at rest), 2.5 g (an impact), and the top.
        c.drawLine(left, bottom, right, bottom, grid)
        c.drawLine(left, top, left, bottom, grid)
        for ((g, text) in listOf(1f to "1 g", 2.5f to "2.5 g", 0.75f to "0.75")) {
            c.drawLine(left, y(g), right, y(g), dashed)
            c.drawText(text, 2f * dp, y(g) + 3f * dp, label)
        }
        c.drawText("%.0f g".format(yMax), 2f * dp, top + 8f * dp, label)
        // Seconds from the impact.
        val first = -(v.impactIndex / 50)
        val last = (n - 1 - v.impactIndex) / 50
        for (s in first..last) {
            val i = v.impactIndex + s * 50
            if (i < 0 || i >= n) continue
            c.drawLine(x(i), bottom, x(i), bottom + 3f * dp, grid)
            val t = if (s == 0) "0 s" else "%+d".format(s)
            c.drawText(t, x(i) - label.measureText(t) / 2, bottom + 14f * dp, label)
        }
        c.drawLine(x(v.impactIndex), top, x(v.impactIndex), bottom, impact)

        path.reset()
        path.moveTo(x(0), y(tr[0]))
        for (i in 1 until n) path.lineTo(x(i), y(tr[i]))
        c.drawPath(path, line)
    }
}
