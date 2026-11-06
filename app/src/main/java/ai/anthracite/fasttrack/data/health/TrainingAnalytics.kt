package ai.anthracite.fasttrack.data.health

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time-series helpers for training data: weekly bucketing and simple trend math. Weeks start on
 * Monday in the device's local timezone.
 */
object TrainingAnalytics {

    private val weekLabelFmt = SimpleDateFormat("M/d", Locale.US)

    data class WeekPoint(val weekStartMs: Long, val value: Float) {
        val label: String get() = weekLabelFmt.format(Date(weekStartMs))
    }

    /** Midnight of the Monday that starts the week containing [epochMs]. */
    fun weekStartMs(epochMs: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = epochMs
        val dowFromMonday = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 … Sun=6
        c.add(Calendar.DAY_OF_YEAR, -dowFromMonday)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Groups (epochMs -> value) pairs into weekly buckets, sorted oldest→newest.
     * @param sum when true the values are summed per week, otherwise averaged.
     */
    fun weekly(points: List<Pair<Long, Float>>, sum: Boolean): List<WeekPoint> {
        if (points.isEmpty()) return emptyList()
        val grouped = points.groupBy { weekStartMs(it.first) }
        return grouped.toSortedMap().map { (weekStart, bucket) ->
            val values = bucket.map { it.second }
            val agg = if (sum) values.sum() else values.average().toFloat()
            WeekPoint(weekStart, agg)
        }
    }

    /** Percent change between the last two values; positive means the latest is higher. */
    fun lastStepPercent(values: List<Float>): Int? {
        if (values.size < 2) return null
        val prev = values[values.size - 2]
        val last = values[values.size - 1]
        if (prev == 0f) return null
        return Math.round((last - prev) / Math.abs(prev) * 100f)
    }
}
