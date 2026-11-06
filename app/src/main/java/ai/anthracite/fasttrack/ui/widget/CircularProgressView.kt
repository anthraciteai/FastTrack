package ai.anthracite.fasttrack.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import ai.anthracite.fasttrack.R

/**
 * A thick rounded progress ring with a track, used for fasting / water / calorie goals.
 * Set [progress] (0f..1f) and optionally [ringColor]; it animates smoothly.
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private var strokeWidthPx = 16f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val oval = RectF()
    private var animatedProgress = 0f

    var ringColor: Int = ContextCompat.getColor(context, R.color.md_primary)
        set(value) {
            field = value
            progressPaint.color = value
            invalidate()
        }

    var trackColor: Int = ContextCompat.getColor(context, R.color.md_surface_variant)
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            field = clamped
            animateTo(clamped)
        }

    init {
        trackPaint.color = trackColor
        progressPaint.color = ringColor
        context.obtainStyledAttributes(attrs, R.styleable.CircularProgressView).apply {
            strokeWidthPx = getDimension(
                R.styleable.CircularProgressView_cpvStrokeWidth, strokeWidthPx
            )
            ringColor = getColor(R.styleable.CircularProgressView_cpvRingColor, ringColor)
            recycle()
        }
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    private fun animateTo(target: Float) {
        ValueAnimator.ofFloat(animatedProgress, target).apply {
            duration = 650
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = strokeWidthPx / 2f + 2f
        oval.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, 360f * animatedProgress, false, progressPaint)
    }
}
