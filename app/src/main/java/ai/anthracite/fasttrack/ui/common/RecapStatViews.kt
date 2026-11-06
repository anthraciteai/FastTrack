package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.RecapKind
import ai.anthracite.fasttrack.data.health.RecapStat
import ai.anthracite.fasttrack.data.health.RecapTone
import ai.anthracite.fasttrack.databinding.ItemRecapStatBinding
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * Renders a daily-recap stat row (sleep · activity · stress · resting HR) into a horizontal
 * [LinearLayout], one evenly-weighted column per stat. Shared by the dashboard and Trends recaps.
 */
fun LinearLayout.bindRecapStats(stats: List<RecapStat>) {
    removeAllViews()
    val inflater = LayoutInflater.from(context)
    stats.forEach { stat ->
        val b = ItemRecapStatBinding.inflate(inflater, this, false)
        b.root.layoutParams = LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        )
        val tint = ContextCompat.getColor(context, colorForTone(stat.tone))
        b.statIcon.setImageResource(iconForKind(stat.kind))
        b.statIcon.imageTintList = ColorStateList.valueOf(tint)
        b.statValue.text = stat.value
        b.statLabel.text = stat.label
        if (stat.delta.isBlank()) {
            b.statDelta.visibility = View.GONE
        } else {
            b.statDelta.visibility = View.VISIBLE
            b.statDelta.text = stat.delta
            b.statDelta.setTextColor(tint)
        }
        addView(b.root)
    }
}

private fun iconForKind(kind: RecapKind): Int = when (kind) {
    RecapKind.SLEEP -> R.drawable.ic_moon
    RecapKind.ACTIVITY -> R.drawable.ic_exercise
    RecapKind.STRESS -> R.drawable.ic_flame
    RecapKind.RESTING_HR -> R.drawable.ic_heart
    RecapKind.CALORIES -> R.drawable.ic_stage_meal
}

private fun colorForTone(tone: RecapTone): Int = when (tone) {
    RecapTone.GOOD -> R.color.accent_nutrition
    RecapTone.WARN -> R.color.macro_carbs
    RecapTone.NEUTRAL -> R.color.md_on_surface_variant
}
