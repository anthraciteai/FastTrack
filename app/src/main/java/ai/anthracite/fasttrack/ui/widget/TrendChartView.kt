package ai.anthracite.fasttrack.ui.widget

import ai.anthracite.fasttrack.R
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight, dependency-free trend chart (line or bar) themed to the app palette. Feed it a
 * list of values and optional x-axis labels; it auto-scales, draws soft gridlines, point markers,
 * a gradient fill (line mode) and an optional dashed target line.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class Mode { LINE, BAR }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var values: List<Float> = emptyList()
    private var labels: List<String> = emptyList()
    private var targetValue: Float? = null

    var mode: Mode = Mode.LINE
        set(value) { field = value; invalidate() }

    /**
     * When true (default) the y-axis starts at 0, which suits counts/distance. For metrics like
     * body weight that hover in a narrow high range, set false so the axis fits the data and the
     * line's shape is actually visible.
     */
    var baselineZero: Boolean = true
        set(value) { field = value; invalidate() }

    var accent: Int = ContextCompat.getColor(context, R.color.accent_exercise)
        set(value) {
            field = value
            linePaint.color = value
            pointPaint.color = value
            barPaint.color = value
            invalidate()
        }

    private val gridColor by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val textColor by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = accent
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val pointHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f) }

    private val linePath = Path()
    private val fillPath = Path()

    init {
        context.obtainStyledAttributes(attrs, R.styleable.TrendChartView).apply {
            accent = getColor(R.styleable.TrendChartView_tcvAccent, accent)
            mode = if (getInt(R.styleable.TrendChartView_tcvMode, 0) == 1) Mode.BAR else Mode.LINE
            recycle()
        }
    }

    /** @param labels optional x labels (same size as [data]). [target] draws a dashed goal line. */
    fun setData(
        data: List<Float>,
        labels: List<String> = emptyList(),
        target: Float? = null
    ) {
        this.values = data
        this.labels = labels
        this.targetValue = target
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        gridPaint.color = gridColor
        textPaint.color = textColor
        targetPaint.color = MaterialColors.compositeARGBWithAlpha(accent, 150)
        pointHalo.color = MaterialColors.compositeARGBWithAlpha(accent, 60)

        val leftPad = dp(4f)
        val rightPad = dp(4f)
        val topPad = dp(12f)
        val bottomPad = if (labels.isNotEmpty()) dp(20f) else dp(8f)

        val chartLeft = leftPad
        val chartRight = width - rightPad
        val chartTop = topPad
        val chartBottom = height - bottomPad
        val chartW = chartRight - chartLeft
        val chartH = chartBottom - chartTop

        // Baseline gridlines (3 lines)
        for (i in 0..2) {
            val y = chartTop + chartH * i / 2f
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
        }

        if (values.isEmpty() || chartW <= 0 || chartH <= 0) return

        val rawMax = values.maxOrNull() ?: 0f
        val rawMin = values.minOrNull() ?: 0f
        val targetConsidered = targetValue ?: rawMax
        var maxV = max(rawMax, targetConsidered)
        var minV = if (baselineZero) min(rawMin, 0f) else min(rawMin, targetValue ?: rawMin)
        if (maxV == minV) maxV = minV + 1f
        // headroom (and bottom room too when the axis is fitted to the data)
        val span = maxV - minV
        maxV += span * 0.12f
        if (!baselineZero) minV -= span * 0.12f

        fun xFor(index: Int): Float =
            if (values.size == 1) chartLeft + chartW / 2f
            else chartLeft + chartW * index / (values.size - 1)

        fun yFor(v: Float): Float = chartBottom - (v - minV) / (maxV - minV) * chartH

        // Target line
        targetValue?.let { t ->
            val ty = yFor(t)
            canvas.drawLine(chartLeft, ty, chartRight, ty, targetPaint)
        }

        when (mode) {
            Mode.BAR -> {
                val slot = chartW / values.size
                val barW = min(slot * 0.55f, dp(22f))
                values.forEachIndexed { i, v ->
                    val cx = chartLeft + slot * (i + 0.5f)
                    val top = yFor(v)
                    val r = RectF(cx - barW / 2, top, cx + barW / 2, chartBottom)
                    barPaint.color = accent
                    canvas.drawRoundRect(r, dp(6f), dp(6f), barPaint)
                }
            }
            Mode.LINE -> {
                linePath.reset()
                fillPath.reset()
                values.forEachIndexed { i, v ->
                    val x = xFor(i)
                    val y = yFor(v)
                    if (i == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, chartBottom)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo(xFor(values.size - 1), chartBottom)
                fillPath.close()
                fillPaint.shader = LinearGradient(
                    0f, chartTop, 0f, chartBottom,
                    MaterialColors.compositeARGBWithAlpha(accent, 90),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(fillPath, fillPaint)
                canvas.drawPath(linePath, linePaint)
                values.forEachIndexed { i, v ->
                    val x = xFor(i)
                    val y = yFor(v)
                    canvas.drawCircle(x, y, dp(5f), pointHalo)
                    canvas.drawCircle(x, y, dp(3f), pointPaint)
                }
            }
        }

        // X labels (show a subset to avoid crowding)
        if (labels.isNotEmpty() && labels.size == values.size) {
            textPaint.textAlign = Paint.Align.CENTER
            val step = max(1, labels.size / 5)
            labels.forEachIndexed { i, label ->
                if (i % step == 0 || i == labels.size - 1) {
                    val x = if (mode == Mode.BAR) {
                        val slot = chartW / values.size
                        chartLeft + slot * (i + 0.5f)
                    } else xFor(i)
                    canvas.drawText(label, x, height - dp(5f), textPaint)
                }
            }
        }
    }
}
