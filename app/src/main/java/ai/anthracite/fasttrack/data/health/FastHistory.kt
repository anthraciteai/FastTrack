package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.util.DayKeys
import java.time.Instant
import java.time.ZoneId

/**
 * The single source of truth for what a fast "counts as". You can only be in one fast at a time,
 * so overlapping sessions (e.g. a back-dated manual log on top of a live fast, or a sync
 * duplicate) and zero/negative-length sessions are impossible and poison every stat.
 *
 * A finished fast belongs to the calendar day it **ended**: overnight fasts start before or after
 * midnight from night to night, so crediting the start day skips days you actually fasted.
 */
object FastHistory {

    /** Finished fasts shorter than this are accidental start/stop taps. */
    const val MIN_DURATION_MS = 60_000L

    fun hours(s: FastingSession, nowMs: Long = System.currentTimeMillis()): Double =
        ((s.endEpochMs ?: nowMs) - s.startEpochMs) / 3_600_000.0

    /** Reached its goal — derived from the times, never trusted from a stored flag. */
    fun isCompleted(s: FastingSession): Boolean =
        s.endEpochMs != null && hours(s) >= s.goalHours - 1e-9

    /** Share of the goal a fast must reach before we ask how it felt. */
    const val CHECK_IN_MIN_FRACTION = 0.5

    /** Worth a "how do you feel?" check-in: ended at or past half its goal. */
    fun qualifiesForCheckIn(s: FastingSession): Boolean =
        s.endEpochMs != null && isValidSpan(s) && hours(s) >= s.goalHours * CHECK_IN_MIN_FRACTION

    /** The day a fast is credited to: its end day (or today's key while still running). */
    fun dayOf(s: FastingSession, zone: ZoneId? = null): String {
        val at = s.endEpochMs ?: System.currentTimeMillis()
        return if (zone == null) DayKeys.of(at)
        else Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    }

    /** Longest completed fast (hours) credited to each day. */
    fun completedHoursByDay(sessions: List<FastingSession>, zone: ZoneId? = null): Map<String, Double> =
        sessions.filter(::isCompleted).groupBy { dayOf(it, zone) }.mapValues { (_, f) -> f.maxOf { hours(it) } }

    /** Longest finished fast (hours, completed or not) credited to each day. */
    fun finishedHoursByDay(sessions: List<FastingSession>, zone: ZoneId? = null): Map<String, Double> =
        sessions.filter { it.endEpochMs != null }.groupBy { dayOf(it, zone) }.mapValues { (_, f) -> f.maxOf { hours(it) } }

    fun isValidSpan(s: FastingSession): Boolean {
        val end = s.endEpochMs ?: return true
        return end - s.startEpochMs >= MIN_DURATION_MS
    }

    fun overlaps(a: FastingSession, b: FastingSession): Boolean {
        val aEnd = a.endEpochMs ?: Long.MAX_VALUE
        val bEnd = b.endEpochMs ?: Long.MAX_VALUE
        return a.startEpochMs < bEnd && b.startEpochMs < aEnd
    }

    /** True when [candidate] can't be saved alongside [others] (its own row excluded). */
    fun conflicts(candidate: FastingSession, others: List<FastingSession>): Boolean =
        !isValidSpan(candidate) ||
            others.any { it.id != candidate.id && overlaps(candidate, it) }

    /**
     * Ids to delete so the remaining history is consistent. Among overlapping finished fasts the
     * earliest-recorded row (lowest id) wins; an in-progress fast is never removed.
     */
    fun invalidIds(sessions: List<FastingSession>): Set<Long> {
        val drop = sessions.filterNot(::isValidSpan).map { it.id }.toMutableSet()
        val kept = mutableListOf<FastingSession>()
        for (s in sessions.filter { it.id !in drop }.sortedBy { it.startEpochMs }) {
            val clash = kept.firstOrNull { overlaps(it, s) && it.endEpochMs != null && s.endEpochMs != null }
            when {
                clash == null -> kept += s
                s.id > clash.id -> drop += s.id
                else -> {
                    drop += clash.id
                    kept.remove(clash)
                    kept += s
                }
            }
        }
        return drop
    }
}
