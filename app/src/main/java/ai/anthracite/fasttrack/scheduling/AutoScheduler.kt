package ai.anthracite.fasttrack.scheduling

import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.data.local.ScheduleEvent
import java.util.Calendar

/**
 * Pure (no I/O) auto-rescheduler for the Schedule tab.
 *
 * When a calendar event (e.g. a meeting) lands on top of one of the user's app-created activities
 * (e.g. a run), [resolve] finds the earliest free slot that fits the activity's duration and returns
 * the moves to apply. The caller is responsible for persisting the moves (local DB + calendar).
 *
 * Design choices (deliberately conservative — see FastTrack Schedule notes):
 *  - Only [MOVABLE] events get moved. By default that's just workouts; meals/meetings are left
 *    where the user put them.
 *  - [OBSTACLES] are the things we refuse to overlap. Fasts are intentionally excluded because a
 *    fast spans many hours and you can still train during it.
 *  - Slots are searched inside a daily window ([dayStartHour]..[dayEndHour]) on a [stepMs] grid.
 */
object AutoScheduler {

    /** App activities we may automatically move around conflicts. */
    val MOVABLE: Set<EventType> = setOf(EventType.WORKOUT)

    /**
     * Events that act as obstacles (we won't place a moved activity on top of these). Fasts are
     * excluded on purpose so workouts can be scheduled during a fasting window.
     */
    val OBSTACLES: Set<EventType> = setOf(EventType.OTHER, EventType.MEAL, EventType.WORKOUT)

    /** A single reschedule: move [event] from its current slot to [newStartMs]..[newEndMs]. */
    data class Move(val event: ScheduleEvent, val newStartMs: Long, val newEndMs: Long)

    private data class Interval(val start: Long, val end: Long)

    /**
     * Computes the moves needed so that no [MOVABLE] event overlaps an [OBSTACLES] event.
     * Only future events are touched. Returns an empty list when nothing conflicts.
     */
    fun resolve(
        events: List<ScheduleEvent>,
        now: Long = System.currentTimeMillis(),
        dayStartHour: Int = 6,
        dayEndHour: Int = 22,
        stepMs: Long = 15 * 60_000L,
        searchDays: Int = 14
    ): List<Move> {
        fun typeOf(e: ScheduleEvent) = runCatching { EventType.valueOf(e.type) }.getOrNull()

        // Effective intervals of every obstacle, keyed by event id. Updated as we place moves so
        // later moves avoid earlier ones.
        val obstacleById = HashMap<Long, Interval>()
        events.filter { typeOf(it) in OBSTACLES }
            .forEach { obstacleById[it.id] = Interval(it.startEpochMs, it.endEpochMs) }

        val movable = events
            .filter { typeOf(it) in MOVABLE }
            .filter { it.endEpochMs > now }
            .sortedBy { it.startEpochMs }

        val moves = mutableListOf<Move>()
        for (ev in movable) {
            val duration = (ev.endEpochMs - ev.startEpochMs).coerceAtLeast(stepMs)
            val obstacles = merge(obstacleById.filterKeys { it != ev.id }.values.toList())

            // Only act on a genuine conflict in the (future part of the) current slot.
            if (!overlapsAny(ev.startEpochMs, ev.endEpochMs, obstacles)) continue

            val earliest = maxOf(ev.startEpochMs, now)
            val newStart = findSlot(earliest, duration, obstacles, dayStartHour, dayEndHour, stepMs, searchDays)
                ?: continue
            val newEnd = newStart + duration
            moves += Move(ev, newStart, newEnd)
            // The workout itself is an obstacle for subsequent movable events.
            obstacleById[ev.id] = Interval(newStart, newEnd)
        }
        return moves
    }

    /** Earliest grid-aligned start >= [earliest] that fits [duration] in a day window with no overlap. */
    private fun findSlot(
        earliest: Long,
        duration: Long,
        obstacles: List<Interval>,
        dayStartHour: Int,
        dayEndHour: Int,
        stepMs: Long,
        searchDays: Int
    ): Long? {
        var candidate = clampToWindow(alignUp(earliest, stepMs), dayStartHour, dayEndHour)
        val limit = earliest + searchDays * 24L * 3_600_000L
        while (candidate <= limit) {
            val end = candidate + duration
            val windowEnd = atHour(candidate, dayEndHour)
            if (end > windowEnd) {
                candidate = nextDayStart(candidate, dayStartHour)
                continue
            }
            val conflict = obstacles.firstOrNull { it.start < end && candidate < it.end }
            if (conflict == null) return candidate
            candidate = clampToWindow(alignUp(conflict.end, stepMs), dayStartHour, dayEndHour)
        }
        return null
    }

    // ---- interval helpers ----

    private fun merge(intervals: List<Interval>): List<Interval> {
        if (intervals.isEmpty()) return emptyList()
        val sorted = intervals.sortedBy { it.start }
        val out = mutableListOf(sorted.first())
        for (i in sorted.drop(1)) {
            val last = out.last()
            out[out.lastIndex] =
                if (i.start <= last.end) Interval(last.start, maxOf(last.end, i.end))
                else { out.add(i); continue }
        }
        return out
    }

    private fun overlapsAny(start: Long, end: Long, intervals: List<Interval>): Boolean =
        intervals.any { it.start < end && start < it.end }

    // ---- time-of-day helpers ----

    private fun alignUp(ms: Long, step: Long): Long = ((ms + step - 1) / step) * step

    private fun atHour(ms: Long, hour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextDayStart(ms: Long, startHour: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, startHour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Pull [ms] into the current day's window, rolling to the next day if it's past the end. */
    private fun clampToWindow(ms: Long, startHour: Int, endHour: Int): Long {
        val windowStart = atHour(ms, startHour)
        val windowEnd = atHour(ms, endHour)
        return when {
            ms < windowStart -> windowStart
            ms >= windowEnd -> nextDayStart(ms, startHour)
            else -> ms
        }
    }
}
