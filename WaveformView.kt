package com.example.beirus

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * WaveformView
 *
 * Renders a scrollable stereo (mono) waveform with 16-pad chop markers.
 * Call [updateWaveform], [updateChops], and [setActivePad] to update the display.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── State ─────────────────────────────────────────────────────────────
    private var samples: FloatArray = floatArrayOf()
    private var chops: IntArray = intArrayOf()
    private var totalSamples: Int = 1
    private var activePad: Int = -1

    // Down-sampled peaks for efficient drawing (max 2048 columns)
    private var peaks: FloatArray = floatArrayOf()

    // ── Paints ────────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0D0D0D")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        strokeWidth = 1f
        isAntiAlias = false
        style = Paint.Style.STROKE
    }

    private val waveTopPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        strokeWidth = 1.2f
    }

    private val markerPaint = Paint().apply {
        color = Color.parseColor("#FF3C3C")
        strokeWidth = 2.5f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val markerLabelPaint = Paint().apply {
        color = Color.parseColor("#FF3C3C")
        textSize = 22f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val activeRegionPaint = Paint().apply {
        color = Color.parseColor("#33FF6B00")   // semi-transparent amber
        style = Paint.Style.FILL
    }

    private val activeMarkerPaint = Paint().apply {
        color = Color.parseColor("#FF6B00")
        strokeWidth = 3.5f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val centerLinePaint = Paint().apply {
        color = Color.parseColor("#2A2A2A")
        strokeWidth = 1f
        isAntiAlias = false
    }

    // ── Public API ────────────────────────────────────────────────────────

    fun updateWaveform(data: FloatArray?) {
        samples = data ?: floatArrayOf()
        totalSamples = max(1, samples.size)
        peaks = buildPeaks(samples, MAX_COLUMNS)
        activePad = -1
        invalidate()
    }

    fun updateChops(points: IntArray, total: Int) {
        chops = points
        totalSamples = max(1, total)
        invalidate()
    }

    /** Highlight the pad that is currently playing (0-based index, -1 = none). */
    fun setActivePad(padIndex: Int) {
        activePad = padIndex
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        // Rebuild gradient whenever size changes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Background
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Grid lines (8 horizontal divisions)
        for (i in 1..7) {
            val y = h * i / 8f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val mid = h / 2f
        canvas.drawLine(0f, mid, w, mid, centerLinePaint)

        if (peaks.isEmpty()) {
            drawPlaceholder(canvas, w, h)
            return
        }

        // ── Active pad region highlight ───────────────────────────────────
        if (activePad in chops.indices) {
            val startX = sampleToX(chops[activePad], w)
            val endX = if (activePad + 1 < chops.size)
                sampleToX(chops[activePad + 1], w)
            else w
            canvas.drawRect(startX, 0f, endX, h, activeRegionPaint)
        }

        // ── Waveform ──────────────────────────────────────────────────────
        // Build a gradient that fades from green (top / loud) to teal (quiet)
        val gradient = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                Color.parseColor("#00E5FF"),
                Color.parseColor("#00BFA5"),
                Color.parseColor("#00BFA5"),
                Color.parseColor("#00E5FF")
            ),
            floatArrayOf(0f, 0.45f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        waveTopPaint.shader = gradient

        val path = Path()
        val numCols = peaks.size
        val colW = w / numCols.toFloat()

        for (col in peaks.indices) {
            val x = col * colW
            val amp = peaks[col] * (h * 0.46f)
            val top = mid - amp
            val bot = mid + amp
            if (col == 0) {
                path.moveTo(x, mid)
            }
            // Upper half
            path.lineTo(x, top)
        }
        for (col in peaks.indices.reversed()) {
            val x = col * colW
            val amp = peaks[col] * (h * 0.46f)
            val bot = mid + amp
            path.lineTo(x, bot)
        }
        path.close()
        canvas.drawPath(path, waveTopPaint)

        // ── Chop markers ──────────────────────────────────────────────────
        val padLabels = arrayOf(
            "1","2","3","4","5","6","7","8",
            "9","10","11","12","13","14","15","16"
        )
        for (i in chops.indices) {
            val x = sampleToX(chops[i], w)
            val paint = if (i == activePad) activeMarkerPaint else markerPaint
            canvas.drawLine(x, 0f, x, h, paint)

            // Pad number label (small pill at top)
            val label = padLabels.getOrElse(i) { "${i + 1}" }
            val lx = x + 4f
            val ly = 20f
            markerLabelPaint.color = if (i == activePad)
                Color.parseColor("#FF6B00") else Color.parseColor("#FF3C3C")
            if (lx + markerLabelPaint.measureText(label) < w) {
                canvas.drawText(label, lx, ly, markerLabelPaint)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun sampleToX(samplePos: Int, viewWidth: Float): Float =
        (samplePos.toFloat() / totalSamples.toFloat()) * viewWidth

    private fun drawPlaceholder(canvas: Canvas, w: Float, h: Float) {
        val p = Paint().apply {
            color = Color.parseColor("#2A2A2A")
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Load a WAV file to begin", w / 2f, h / 2f + 12f, p)
    }

    companion object {
        private const val MAX_COLUMNS = 2048

        /** Reduce [data] to at most [maxCols] peak values for fast drawing. */
        private fun buildPeaks(data: FloatArray, maxCols: Int): FloatArray {
            if (data.isEmpty()) return floatArrayOf()
            val cols = minOf(maxCols, data.size)
            val step = data.size.toFloat() / cols
            return FloatArray(cols) { col ->
                val start = (col * step).toInt()
                val end   = ((col + 1) * step).toInt().coerceAtMost(data.size)
                var peak  = 0f
                for (j in start until end) peak = max(peak, abs(data[j]))
                peak
            }
        }
    }
}
