package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.R
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * A physiological stage of a fast, keyed by the number of hours elapsed at which the body
 * typically enters it. These are educational, general-population estimates — the exact timing
 * varies per person, last meal, and activity.
 */
data class FastingStage(
    val id: String,
    /** Hours into the fast at which this stage typically begins. */
    val startHour: Double,
    val title: String,
    /** Short "mode" label, e.g. "Fat burning" or "Cellular cleanup". */
    val bodyState: String,
    val description: String,
    @param:DrawableRes val iconRes: Int,
    @param:ColorRes val colorRes: Int
)

object FastingStages {

    val ALL: List<FastingStage> = listOf(
        FastingStage(
            id = "fed",
            startHour = 0.0,
            title = "Fed State",
            bodyState = "Anabolic",
            description = "Your body is digesting and absorbing your last meal. Blood sugar and " +
                "insulin are elevated while nutrients are stored for later.",
            iconRes = R.drawable.ic_stage_meal,
            colorRes = R.color.accent_nutrition
        ),
        FastingStage(
            id = "sugar",
            startHour = 3.0,
            title = "Blood Sugar Settling",
            bodyState = "Post-absorptive",
            description = "Digestion wraps up. Insulin starts to fall and your body begins drawing " +
                "on stored glycogen for steady energy.",
            iconRes = R.drawable.ic_stage_glucose,
            colorRes = R.color.accent_water
        ),
        FastingStage(
            id = "burn",
            startHour = 8.0,
            title = "Fat Burning Begins",
            bodyState = "Catabolic",
            description = "Glycogen stores run low, so your body flips the switch and starts burning " +
                "stored fat for fuel.",
            iconRes = R.drawable.ic_flame,
            colorRes = R.color.accent_exercise
        ),
        FastingStage(
            id = "ketosis",
            startHour = 12.0,
            title = "Ketosis Begins",
            bodyState = "Fat burning",
            description = "Your liver starts converting fat into ketones — a clean-burning energy " +
                "source for your brain and muscles.",
            iconRes = R.drawable.ic_stage_ketone,
            colorRes = R.color.brand_gold
        ),
        FastingStage(
            id = "autophagy",
            startHour = 16.0,
            title = "Autophagy Kicks In",
            bodyState = "Cellular cleanup",
            description = "Cells begin recycling old and damaged parts — a self-cleaning process " +
                "(\"autophagy\") linked to longevity and repair.",
            iconRes = R.drawable.ic_stage_autophagy,
            colorRes = R.color.accent_fasting
        ),
        FastingStage(
            id = "deep",
            startHour = 18.0,
            title = "Deep Ketosis",
            bodyState = "Peak fat burn",
            description = "Ketone levels climb and fat burning peaks. Many people report steady " +
                "energy and sharp mental focus here.",
            iconRes = R.drawable.ic_stage_spark,
            colorRes = R.color.brand_ember
        ),
        FastingStage(
            id = "growth",
            startHour = 24.0,
            title = "Growth Hormone & Repair",
            bodyState = "Autophagy peak",
            description = "Autophagy ramps up and human growth hormone rises to help protect lean " +
                "muscle while you keep burning fat.",
            iconRes = R.drawable.ic_stage_cell,
            colorRes = R.color.macro_protein
        ),
        FastingStage(
            id = "immune",
            startHour = 48.0,
            title = "Immune Reset",
            bodyState = "Deep repair",
            description = "Prolonged fasting may trigger stem-cell–driven renewal of immune cells. " +
                "Only attempt extended fasts with proper guidance.",
            iconRes = R.drawable.ic_stage_shield,
            colorRes = R.color.accent_water
        )
    )

    /** Index of the stage the body is currently in for [elapsedHours] (clamped to [0, last]). */
    fun indexFor(elapsedHours: Double): Int {
        var idx = 0
        for (i in ALL.indices) {
            if (elapsedHours >= ALL[i].startHour) idx = i else break
        }
        return idx
    }

    fun current(elapsedHours: Double): FastingStage = ALL[indexFor(elapsedHours)]

    fun next(elapsedHours: Double): FastingStage? = ALL.getOrNull(indexFor(elapsedHours) + 1)

    /** Progress [0,1] through the current stage toward the next one. */
    fun progressWithinStage(elapsedHours: Double): Float {
        val idx = indexFor(elapsedHours)
        val start = ALL[idx].startHour
        val next = ALL.getOrNull(idx + 1) ?: return 1f
        val span = next.startHour - start
        if (span <= 0) return 1f
        return ((elapsedHours - start) / span).toFloat().coerceIn(0f, 1f)
    }
}
