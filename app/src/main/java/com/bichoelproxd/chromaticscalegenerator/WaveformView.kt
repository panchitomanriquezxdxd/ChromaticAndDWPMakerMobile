package com.bichoelproxd.chromaticscalegenerator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Forma de onda con los markers en ambar (como en el HTML). Tocarla pone un marker; tocar uno
 * existente lo quita. Sin audio, muestra un ejemplo con 3 markers.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Un marker y la nota que abre. */
    class Mark(val frame: Int, val name: String)

    /** (frame tocado, tolerancia en frames) */
    var onTap: ((Int, Int) -> Unit)? = null

    private var audio: Audio? = null
    private var marks: List<Mark> = emptyList()
    private var peaksWidth = -1
    private var peakMax = FloatArray(0)
    private var peakMin = FloatArray(0)

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.parseColor("#F7F9FB") }
    private val line = Paint().apply { color = Color.parseColor("#D3DAE2") }
    private val wave = Paint().apply { color = Color.parseColor("#0A6C88") }
    private val mark = Paint().apply { color = Color.parseColor("#B9520A"); strokeWidth = 1.5f * density }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B9520A"); textSize = 11f * density; typeface = Typeface.MONOSPACE
    }

    fun setContent(audio: Audio?, marks: List<Mark>) {
        if (audio !== this.audio) peaksWidth = -1
        this.audio = audio
        this.marks = marks
        invalidate()
    }

    private fun computePeaks(width: Int) {
        peakMax = FloatArray(width)
        peakMin = FloatArray(width)
        val a = audio
        if (a == null) {
            // Ejemplo: 3 sonidos que decaen
            val cuts = doubleArrayOf(0.0, 0.45, 0.72, 1.0)
            for (c in 0 until width) {
                val x = c.toDouble() / width
                var s = 0
                while (s < 2 && x >= cuts[s + 1]) s++
                val t = (x - cuts[s]) / (cuts[s + 1] - cuts[s])
                val env = min(1.0, t * 14) * Math.pow(1 - t, 1.6) * (0.55 + 0.4 * Math.sin(s * 2.3 + 1))
                val j = 0.75 + 0.25 * Math.sin(c * 1.7 + s * 4) * Math.cos(c * 0.37)
                peakMax[c] = (env * j).toFloat()
                peakMin[c] = (-env * (0.7 + 0.3 * Math.cos(c * 1.3 + s))).toFloat()
            }
        } else {
            val x = a.channels[0]
            val fpc = a.frames.toDouble() / width
            val k = 48
            var top = 1e-9
            for (c in 0 until width) {
                var hi = Double.NEGATIVE_INFINITY
                var lo = Double.POSITIVE_INFINITY
                for (i in 0 until k) {
                    val v = x[min(a.frames - 1, (c * fpc + i * fpc / k).toInt())]
                    if (v > hi) hi = v
                    if (v < lo) lo = v
                }
                peakMax[c] = hi.toFloat(); peakMin[c] = lo.toFloat()
                top = max(top, max(abs(hi), abs(lo)))
            }
            for (c in 0 until width) { peakMax[c] = (peakMax[c] / top).toFloat(); peakMin[c] = (peakMin[c] / top).toFloat() }
        }
        peaksWidth = width
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        if (peaksWidth != w) computePeaks(w)
        val top = 20f * density
        val mid = top + (h - top) / 2
        val amp = (h - top) / 2 - 4f * density
        canvas.drawRect(0f, mid, w.toFloat(), mid + max(1f, density), line)
        for (x in 0 until w) {
            val a = mid - peakMax[x] * amp
            val b = mid - peakMin[x] * amp
            canvas.drawRect(x.toFloat(), a, x + 1f, a + max(1f, b - a), wave)
        }
        val frames = audio?.frames
        val shown = if (frames == null) {
            listOf(0.0 to "C5", 0.45 to "C#5", 0.72 to "D5")
        } else marks.map { it.frame.toDouble() / frames to it.name }
        var lastEnd = -1e9f
        for ((at, name) in shown) {
            val x = min(w - 1f, Math.round(at * w).toFloat())
            canvas.drawRect(x, 0f, x + max(1f, 1.5f * density), h.toFloat(), mark)
            val tw = text.measureText(name)
            val tx = x + 4f * density
            if (tx > lastEnd + 3f * density && tx + tw < w) {
                canvas.drawText(name, tx, 3f * density + text.textSize, text)
                lastEnd = tx + tw
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val a = audio ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val w = max(1, width)
                val frame = Math.round(event.x / w * a.frames).toInt().coerceIn(0, a.frames - 1)
                val tol = (12f / w * a.frames).toInt()
                onTap?.invoke(frame, tol)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
