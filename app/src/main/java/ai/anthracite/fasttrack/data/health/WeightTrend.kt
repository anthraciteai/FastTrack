package ai.anthracite.fasttrack.data.health

import kotlin.math.abs

/**
 * Turns noisy weigh-ins into a trend line: an exponentially smoothed "trend weight" plus a
 * least-squares weekly rate over a recent window, and an ETA to the goal at that rate.
 */
object WeightTrend {

    /** Smoothing factor for the trend weight (≈ a 7–10 day memory). */
    const val ALPHA = 0.25
    const val WINDOW_DAYS = 28
    const val MIN_POINTS = 3
    const val MIN_SPAN_DAYS = 5

    private const val DAY_MS = 86_400_000L

    data class Point(val epochMs: Long, val kg: Double)

    data class Result(
        /** Smoothed weight per weigh-in, oldest first, aligned with the input order. */
        val smoothed: List<Point>,
        val latestTrendKg: Double,
        /** Signed rate in kg/week (negative = losing). Null until there's enough data. */
        val kgPerWeek: Double?,
        /** Epoch-ms when the trend line crosses the goal, if it's heading that way. */
        val etaEpochMs: Long?
    )

    fun compute(points: List<Point>, goalKg: Double?, now: Long = System.currentTimeMillis()): Result? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.epochMs }
        var trend = sorted.first().kg
        val smoothed = sorted.map { p ->
            trend += ALPHA * (p.kg - trend)
            Point(p.epochMs, trend)
        }
        val windowStart = now - WINDOW_DAYS * DAY_MS
        val recent = sorted.filter { it.epochMs >= windowStart }
        val rate = slopePerWeek(recent)
        val latest = smoothed.last().kg
        val eta = if (rate != null && goalKg != null) etaFor(latest, goalKg, rate, now) else null
        return Result(smoothed, latest, rate, eta)
    }

    /** Least-squares slope in kg/week, or null if the points are too few / too close together. */
    fun slopePerWeek(points: List<Point>): Double? {
        if (points.size < MIN_POINTS) return null
        val t0 = points.minOf { it.epochMs }
        val spanDays = (points.maxOf { it.epochMs } - t0) / DAY_MS.toDouble()
        if (spanDays < MIN_SPAN_DAYS) return null
        val xs = points.map { (it.epochMs - t0) / DAY_MS.toDouble() }
        val ys = points.map { it.kg }
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in xs.indices) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        if (den == 0.0) return null
        return num / den * 7.0
    }

    /** When [currentKg] reaches [goalKg] at [kgPerWeek], or null if it's moving the wrong way. */
    fun etaFor(currentKg: Double, goalKg: Double, kgPerWeek: Double, now: Long): Long? {
        val remaining = goalKg - currentKg
        if (abs(remaining) < 0.2) return now
        if (abs(kgPerWeek) < 0.05 || remaining * kgPerWeek <= 0) return null
        val weeks = (remaining / kgPerWeek).coerceAtMost(156.0)
        return now + (weeks * 7 * DAY_MS).toLong()
    }
}
