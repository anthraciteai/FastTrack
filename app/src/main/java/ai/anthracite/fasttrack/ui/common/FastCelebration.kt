package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.DialogFastCompleteBinding
import ai.anthracite.fasttrack.util.TimeFmt
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.random.Random

/**
 * A celebratory "Fast complete!" popup: the trophy badge pops in with an overshoot bounce and a
 * burst of confetti rains down over the dialog. Dependency-free (pure view animation), so it works
 * from any screen that finishes a fast.
 */
object FastCelebration {

    fun show(context: Context, durationMs: Long, onDismiss: (() -> Unit)? = null) {
        val binding = DialogFastCompleteBinding.inflate(LayoutInflater.from(context))
        binding.completeSubtitle.text =
            "You fasted for ${TimeFmt.hmShort(durationMs)} and closed your window. " +
                "That's real discipline — see you at the next one."

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.completeButton.setOnClickListener { dialog.dismiss() }
        onDismiss?.let { cb -> dialog.setOnDismissListener { cb() } }

        dialog.setOnShowListener {
            // Badge pop with an overshoot bounce.
            binding.completeBadge.apply {
                scaleX = 0f
                scaleY = 0f
                animate().scaleX(1f).scaleY(1f)
                    .setStartDelay(80)
                    .setDuration(520)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
            // Title + subtitle gentle fade/rise.
            listOf(binding.completeTitle, binding.completeSubtitle).forEachIndexed { i, v ->
                v.alpha = 0f
                v.translationY = 24f
                v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(220L + i * 90L)
                    .setDuration(380)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    binding.completeBadge.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    binding.completeBadge.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
            }
            binding.confettiLayer.post { launchConfetti(binding.confettiLayer) }
        }

        dialog.show()
    }

    private fun launchConfetti(layer: FrameLayout) {
        val ctx = layer.context
        val width = layer.width.takeIf { it > 0 } ?: return
        val height = layer.height.takeIf { it > 0 } ?: return
        val colors = intArrayOf(
            ContextCompat.getColor(ctx, R.color.accent_fasting),
            ContextCompat.getColor(ctx, R.color.accent_water),
            ContextCompat.getColor(ctx, R.color.accent_nutrition),
            ContextCompat.getColor(ctx, R.color.accent_exercise),
            ContextCompat.getColor(ctx, R.color.md_primary)
        )
        val density = ctx.resources.displayMetrics.density
        repeat(28) { i ->
            val size = ((8 + Random.nextInt(8)) * density).toInt()
            val piece = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = if (Random.nextBoolean()) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                    cornerRadius = 3f * density
                    setColor(colors[Random.nextInt(colors.size)])
                }
            }
            val lp = FrameLayout.LayoutParams(size, size)
            lp.leftMargin = Random.nextInt(width)
            lp.topMargin = -size
            layer.addView(piece, lp)

            val drift = (Random.nextFloat() - 0.5f) * width * 0.6f
            piece.rotation = Random.nextFloat() * 360f
            piece.animate()
                .translationY(height.toFloat() + size)
                .translationX(drift)
                .rotationBy((Random.nextFloat() - 0.5f) * 720f)
                .alpha(0f)
                .setStartDelay(Random.nextLong(0, 320))
                .setDuration(900L + Random.nextLong(0, 900))
                .setInterpolator(AccelerateInterpolator(1.1f))
                .withEndAction { layer.removeView(piece) }
                .start()
        }
    }
}
