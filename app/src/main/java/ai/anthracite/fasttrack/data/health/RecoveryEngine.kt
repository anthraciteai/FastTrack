package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import kotlin.math.roundToInt

/**
 * Readiness score (0–100) from Garmin wellness data — Body Battery, sleep, stress and resting
 * heart rate versus the user's own 14-day baseline — and what it means for today's fast and
 * training. Components that are missing are dropped and the rest re-weighted.
 */
object RecoveryEngine {

    enum class Level { LOW, MODERATE, HIGH }

    data class Readiness(
        val score: Int,
        val level: Level,
        val headline: String,
        /** Short human reasons, e.g. "Sleep 5.6h", "Resting HR +6 bpm vs usual". */
        val factors: List<String>,
        val fastingAdvice: String,
        val trainingAdvice: String,
        /** Upper bound we'd suggest for today's fast, or null for "no change". */
        val suggestedMaxFastHours: Double?
    )

    private const val BASELINE_DAYS = 14
    private const val SLEEP_TARGET_MIN = 450.0

    /**
     * Uses [todayKey]'s row, falling back to yesterday's (Garmin often posts the morning's
     * numbers late). Returns null if neither day has any usable signal.
     */
    fun assess(metrics: List<DailyHealthMetric>, todayKey: String, yesterdayKey: String): Readiness? {
        val byDay = metrics.associateBy { it.dayKey }
        val day = byDay[todayKey]?.takeIf { it.hasSignal() } ?: byDay[yesterdayKey]?.takeIf { it.hasSignal() }
            ?: return null
        val history = metrics.filter { it.dayKey < day.dayKey }.sortedBy { it.dayKey }.takeLast(BASELINE_DAYS)

        val parts = mutableListOf<Pair<Double, Double>>() // (score 0..1, weight)
        val factors = mutableListOf<String>()

        day.bodyBattery?.let { bb ->
            parts += (bb.coerceIn(0, 100) / 100.0) to 0.40
            factors += "Body Battery $bb"
        }
        day.sleepMinutes?.let { min ->
            parts += (min / SLEEP_TARGET_MIN).coerceIn(0.0, 1.0) to 0.30
            factors += "Sleep ${String.format(java.util.Locale.US, "%.1f", min / 60.0)}h"
        }
        day.stressAvg?.let { s ->
            parts += (1.0 - (s.coerceIn(0, 100) - 20) / 60.0).coerceIn(0.0, 1.0) to 0.15
            factors += "Stress $s"
        }
        day.restingHeartRate?.let { rhr ->
            val baseline = history.mapNotNull { it.restingHeartRate }.takeIf { it.size >= 3 }?.average()
            if (baseline != null) {
                val delta = rhr - baseline
                parts += (1.0 - (delta / 10.0)).coerceIn(0.0, 1.0).let { if (delta <= 0) 1.0 else it } to 0.15
                val d = delta.roundToInt()
                factors += when {
                    d >= 3 -> "Resting HR +$d bpm vs usual"
                    d <= -3 -> "Resting HR $d bpm vs usual"
                    else -> "Resting HR at baseline"
                }
            }
        }
        if (parts.isEmpty()) return null

        val totalWeight = parts.sumOf { it.second }
        val score = (parts.sumOf { it.first * it.second } / totalWeight * 100).roundToInt().coerceIn(0, 100)
        val level = when {
            score < 45 -> Level.LOW
            score < 70 -> Level.MODERATE
            else -> Level.HIGH
        }
        return Readiness(
            score = score,
            level = level,
            headline = when (level) {
                Level.LOW -> "Recovery is low today"
                Level.MODERATE -> "Recovery is moderate"
                Level.HIGH -> "You're well recovered"
            },
            factors = factors,
            fastingAdvice = when (level) {
                Level.LOW -> "Keep today's fast to your usual window or shorter and skip anything 20h+. Break it with protein and electrolytes."
                Level.MODERATE -> "Stick to your plan. Front-load water and salt, and end early if you feel shaky."
                Level.HIGH -> "Good day for your full fast — or the longer one you've been planning."
            },
            trainingAdvice = when (level) {
                Level.LOW -> "Go easy: a walk or mobility. Save intervals for a better day."
                Level.MODERATE -> "Moderate effort is fine. Avoid hard sessions late in a long fast."
                Level.HIGH -> "Green light for intensity. Train near the end of your eating window for best fuel."
            },
            suggestedMaxFastHours = if (level == Level.LOW) 16.0 else null
        )
    }

    private fun DailyHealthMetric.hasSignal(): Boolean =
        bodyBattery != null || sleepMinutes != null || stressAvg != null || restingHeartRate != null
}
