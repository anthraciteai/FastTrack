package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.DailyCheckIn
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/**
 * Correlates daily mood / energy / hunger check-ins with fasting: how do you feel on days a fast
 * ended versus days it didn't, and do longer fasts change that? Needs a few days on each side
 * before it says anything, so early findings aren't just noise.
 */
object CheckInInsights {

    const val MIN_PER_GROUP = 3
    /** A difference smaller than this (on the 1–5 scale) is reported as "no real difference". */
    const val MEANINGFUL_DELTA = 0.4

    val MOOD_LABELS = listOf("Rough", "Low", "Okay", "Good", "Great")
    val ENERGY_LABELS = listOf("Drained", "Tired", "Steady", "Energized", "Buzzing")
    val HUNGER_LABELS = listOf("Not hungry", "Peckish", "Hungry", "Very hungry", "Starving")

    data class Insights(
        val recentDays: Int,
        val avgMood: Double?,
        val avgEnergy: Double?,
        val avgHunger: Double?,
        /** Plain-language findings, most interesting first. */
        val findings: List<String>
    ) {
        val headline: String? get() = findings.firstOrNull()
    }

    /**
     * @param fastHoursByEndDay day key -> hours of the longest completed fast that ended that day.
     */
    fun compute(
        checkIns: List<DailyCheckIn>,
        fastHoursByEndDay: Map<String, Double>,
        todayKey: String
    ): Insights {
        val today = LocalDate.parse(todayKey)
        val recentFrom = today.minusDays(6).toString()
        val recent = checkIns.filter { it.dayKey >= recentFrom && it.dayKey <= todayKey }

        val findings = mutableListOf<String>()
        val fastDays = checkIns.filter { (fastHoursByEndDay[it.dayKey] ?: 0.0) >= 12.0 }
        val otherDays = checkIns.filter { (fastHoursByEndDay[it.dayKey] ?: 0.0) < 12.0 }
        if (fastDays.size >= MIN_PER_GROUP && otherDays.size >= MIN_PER_GROUP) {
            compare("energy", fastDays.map { it.energy }, otherDays.map { it.energy }, "on fasting days")?.let(findings::add)
            compare("mood", fastDays.map { it.mood }, otherDays.map { it.mood }, "on fasting days")?.let(findings::add)
            compare("hunger", fastDays.map { it.hunger }, otherDays.map { it.hunger }, "on fasting days")?.let(findings::add)
        }

        val long = fastDays.filter { (fastHoursByEndDay[it.dayKey] ?: 0.0) >= 16.0 }
        val short = fastDays.filter { (fastHoursByEndDay[it.dayKey] ?: 0.0) < 16.0 }
        if (long.size >= MIN_PER_GROUP && short.size >= MIN_PER_GROUP) {
            compare("energy", long.map { it.energy }, short.map { it.energy }, "after 16h+ fasts than shorter ones")
                ?.let(findings::add)
        }

        if (findings.isEmpty()) {
            val need = (MIN_PER_GROUP * 2 - checkIns.size).coerceAtLeast(0)
            findings += if (need > 0) {
                "Check in $need more day${if (need == 1) "" else "s"} to see how fasting affects your energy and mood."
            } else {
                "Keep checking in on both fasting and non-fasting days to unlock your first pattern."
            }
        }

        return Insights(
            recentDays = recent.size,
            avgMood = recent.takeIf { it.isNotEmpty() }?.map { it.mood }?.average(),
            avgEnergy = recent.takeIf { it.isNotEmpty() }?.map { it.energy }?.average(),
            avgHunger = recent.takeIf { it.isNotEmpty() }?.map { it.hunger }?.average(),
            findings = findings
        )
    }

    private fun compare(metric: String, a: List<Int>, b: List<Int>, context: String): String? {
        val ma = a.average()
        val mb = b.average()
        val delta = ma - mb
        if (abs(delta) < MEANINGFUL_DELTA) {
            return if (metric == "energy") "Your energy is about the same $context — fasting isn't costing you." else null
        }
        val pct = if (mb > 0) abs(delta / mb * 100).toInt() else 0
        val dir = if (delta > 0) "higher" else "lower"
        return String.format(
            Locale.US, "Your %s runs %d%% %s %s (%.1f vs %.1f).", metric, pct, dir, context, ma, mb
        )
    }
}
