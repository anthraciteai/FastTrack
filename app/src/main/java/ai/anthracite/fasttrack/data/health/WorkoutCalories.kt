package ai.anthracite.fasttrack.data.health

import kotlin.math.roundToInt

/** MET-based calorie estimate for a live workout (type × body weight × hours). */
object WorkoutCalories {

    fun met(type: String): Double = when (type.trim().lowercase()) {
        "run" -> 9.8
        "walk" -> 3.5
        "cycling" -> 7.5
        "strength" -> 6.0
        "yoga" -> 3.0
        "swim" -> 8.0
        "hiit" -> 8.0
        "hike" -> 6.0
        "row" -> 7.0
        "soccer" -> 7.0
        "basketball" -> 8.0
        "tennis" -> 7.0
        else -> 5.0
    }

    fun estimate(type: String, durationMin: Int, weightKg: Double): Int {
        val hours = durationMin.coerceAtLeast(0) / 60.0
        val kg = weightKg.takeIf { it > 0 } ?: 80.0
        return (met(type) * kg * hours).roundToInt().coerceAtLeast(if (durationMin > 0) 1 else 0)
    }

    fun estimateElapsed(type: String, elapsedMs: Long, weightKg: Double): Int {
        val hours = elapsedMs.coerceAtLeast(0) / 3_600_000.0
        val kg = weightKg.takeIf { it > 0 } ?: 80.0
        return (met(type) * kg * hours).roundToInt()
    }
}
