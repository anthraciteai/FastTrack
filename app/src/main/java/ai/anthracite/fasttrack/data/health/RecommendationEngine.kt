package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class Recommendation(
    val id: String,
    val title: String,
    val detail: String,
    @param:DrawableRes val iconRes: Int,
    @param:ColorRes val accentRes: Int
)

/**
 * Turns the user's running history + guidance targets into actionable coaching tips. Everything
 * here is general-wellness guidance (e.g. the "10% rule" for safe mileage progression), not
 * medical advice.
 */
object RecommendationEngine {

    /**
     * @param weeklyRunMiles chronological weekly running distance in the user's unit (mi or km).
     * @param daysSinceLastRun null if the user has never logged a run.
     * @param recentPaces chronological min-per-unit pace for recent runs (optional).
     */
    fun generate(
        unit: UnitSystem,
        weeklyRunMiles: List<Float>,
        daysSinceLastRun: Int?,
        recentPaces: List<Float>,
        targets: GuidanceEngine.Targets,
        limit: Int = 5
    ): List<Recommendation> {
        val recs = mutableListOf<Recommendation>()
        val u = Units.distanceUnit(unit)

        // --- Mileage progression (the 10% rule) ---
        val lastWeek = weeklyRunMiles.lastOrNull() ?: 0f
        if (lastWeek > 0f) {
            val target = lastWeek * 1.1f
            recs += Recommendation(
                "weekly_target",
                "Build mileage gradually",
                "You ran ${dist(lastWeek)} $u last week. Aim for about ${dist(target)} $u this " +
                    "week — bumping ~10% at a time keeps injuries away.",
                R.drawable.ic_exercise,
                R.color.accent_exercise
            )

            if (weeklyRunMiles.size >= 2) {
                val prev = weeklyRunMiles[weeklyRunMiles.size - 2]
                if (prev > 0f && lastWeek > prev * 1.25f) {
                    val jump = ((lastWeek - prev) / prev * 100).roundToInt()
                    recs += Recommendation(
                        "ramp_warning",
                        "Ease off the ramp",
                        "Your weekly mileage jumped $jump%. Consider a lighter week so your body " +
                            "can absorb the training.",
                        R.drawable.ic_notifications,
                        R.color.macro_carbs
                    )
                }
            }
        }

        // --- Consistency / lapse ---
        when {
            daysSinceLastRun == null -> recs += Recommendation(
                "first_run",
                "Log your first run",
                "Add a run with its distance and we'll start charting your miles over time.",
                R.drawable.ic_exercise,
                R.color.accent_exercise
            )
            daysSinceLastRun >= 4 -> recs += Recommendation(
                "lapse",
                "Time to lace up",
                "It's been $daysSinceLastRun days since your last run. An easy 20–30 min jog keeps " +
                    "your momentum going.",
                R.drawable.ic_flame,
                R.color.accent_exercise
            )
        }

        // --- Pace trend ---
        if (recentPaces.size >= 4) {
            val mid = recentPaces.size / 2
            val firstAvg = recentPaces.subList(0, mid).average()
            val secondAvg = recentPaces.subList(mid, recentPaces.size).average()
            if (secondAvg < firstAvg) {
                recs += Recommendation(
                    "pace_up",
                    "Your pace is improving",
                    "Average pace has dropped to ${Units.paceLabel(secondAvg, unit)} — the speed " +
                        "work is paying off. Keep it up!",
                    R.drawable.ic_trophy,
                    R.color.accent_exercise
                )
            } else if (secondAvg > firstAvg * 1.03) {
                recs += Recommendation(
                    "pace_down",
                    "Add a tempo run",
                    "Pace has slipped a little lately. One tempo or interval run a week helps turn " +
                        "that around.",
                    R.drawable.ic_exercise,
                    R.color.accent_exercise
                )
            }
        }

        // --- Hydration around training ---
        recs += Recommendation(
            "hydration",
            "Hydrate around runs",
            "On run days add roughly 500 ml of water on top of your " +
                "${Units.volumeLabel(targets.waterMl, unit)} daily target.",
            R.drawable.ic_water,
            R.color.accent_water
        )

        // --- Recovery fueling ---
        recs += Recommendation(
            "protein",
            "Refuel to recover",
            "Aim for ${targets.proteinG} g of protein today to help repair muscle after training.",
            R.drawable.ic_nutrition,
            R.color.accent_nutrition
        )

        return recs.take(limit)
    }

    private fun dist(v: Float): String = String.format(Locale.US, "%.1f", abs(v))
}
