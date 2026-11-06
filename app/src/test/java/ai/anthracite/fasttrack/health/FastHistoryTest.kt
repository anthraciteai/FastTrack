package ai.anthracite.fasttrack.health

import ai.anthracite.fasttrack.data.health.DailyRecapEngine
import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.data.health.RecapKind
import ai.anthracite.fasttrack.data.health.RecoveryEngine
import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.util.DayKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastHistoryTest {

    private val h = 3_600_000L
    private val base = DayKeys.startMs("2026-10-21")

    private fun fast(id: Long, startH: Double, endH: Double?, goal: Double = 18.0) = FastingSession(
        id = id,
        startEpochMs = base + (startH * h).toLong(),
        endEpochMs = endH?.let { base + (it * h).toLong() },
        goalHours = goal,
        protocol = "EIGHTEEN_SIX",
        completed = endH != null && endH - startH >= goal
    )

    /** Mirrors the real history that produced a bogus "longest fast 24h". */
    private val history = listOf(
        fast(1, 20.6, 39.45),           // live 18.8h fast
        fast(2, 15.8, 39.85, 20.0),     // later-recorded 24h fast stacked on top of #1
        fast(3, 40.38, 40.381),         // 3-second accidental start/stop
        fast(4, 44.6, 62.9),
        fast(6, 70.5, 88.6),
        fast(5, 94.3, 72.05),           // ends before it starts
        fast(7, 97.75, 108.8),
        fast(8, 116.25, 135.1)
    )

    @Test fun dropsOverlapsZeroAndNegativeFasts() {
        assertEquals(setOf(2L, 3L, 5L), FastHistory.invalidIds(history))
    }

    @Test fun longestFastAfterRepairIsTheRealOne() {
        val bad = FastHistory.invalidIds(history)
        val longest = history.filterNot { it.id in bad }
            .maxOf { (it.endEpochMs!! - it.startEpochMs) / h.toDouble() }
        assertEquals(18.85, longest, 0.01)
    }

    @Test fun neverDropsTheActiveFast() {
        val sessions = listOf(fast(1, 0.0, 18.0), fast(2, 10.0, null))
        assertTrue(FastHistory.invalidIds(sessions).isEmpty())
    }

    @Test fun fastsCountTowardTheDayTheyEnd() {
        // Started 10:30 PM Oct 23, ended 4:37 PM Oct 24 → an Oct 24 fast.
        assertEquals("2026-10-24", FastHistory.dayOf(fast(6, 70.5, 88.6)))
    }

    @Test fun streakSurvivesStartingAfterMidnight() {
        // Real week: started 8:37 PM, 8:37 PM, 10:30 PM, then 8:15 PM two days later after a 1:45 AM
        // short fast. By start day Oct 24 had no fast and the streak broke; by end day it holds.
        val bad = FastHistory.invalidIds(history)
        val stats = ai.anthracite.fasttrack.data.health.FastingGamification.compute(history.filterNot { it.id in bad })
        assertEquals(setOf("2026-10-22", "2026-10-23", "2026-10-24", "2026-10-26"), stats.completedDays)
        assertEquals(setOf("2026-10-25"), stats.partialDays)
        assertEquals(3, stats.longestStreak)
    }

    @Test fun completionIsDerivedFromTimesNotTheStoredFlag() {
        val stale = fast(9, 0.0, 18.5).copy(completed = false)
        assertTrue(FastHistory.isCompleted(stale))
        assertFalse(FastHistory.isCompleted(fast(10, 0.0, 17.9).copy(completed = true)))
        assertFalse(FastHistory.isCompleted(fast(11, 0.0, null)))
    }

    @Test fun checkInOnlyAfterHalfTheGoal() {
        assertTrue(FastHistory.qualifiesForCheckIn(fast(1, 0.0, 18.0)))   // goal met
        assertTrue(FastHistory.qualifiesForCheckIn(fast(2, 0.0, 9.0)))    // exactly 50% of 18h
        assertTrue(FastHistory.qualifiesForCheckIn(fast(3, 0.0, 11.08)))  // your Oct 25 fast
        assertFalse(FastHistory.qualifiesForCheckIn(fast(4, 0.0, 8.9)))
        assertFalse(FastHistory.qualifiesForCheckIn(fast(5, 0.0, null)))  // still running
    }

    @Test fun rejectsSavingAnOverlappingFast() {
        val existing = listOf(fast(1, 20.0, 38.0))
        assertTrue(FastHistory.conflicts(fast(0, 15.0, 39.0), existing))
        assertTrue(FastHistory.conflicts(fast(0, 30.0, null), existing))
        assertFalse(FastHistory.conflicts(fast(0, 40.0, 58.0), existing))
        // Editing a fast's own times never conflicts with itself.
        assertFalse(FastHistory.conflicts(fast(1, 19.0, 38.5), existing))
        // Zero-length is rejected.
        assertTrue(FastHistory.conflicts(fast(0, 50.0, 50.0), existing))
    }
}

class DailyRecapYesterdayTest {

    private fun metric(day: String, stress: Int?) =
        DailyHealthMetric(dayKey = day, epochMs = DayKeys.startMs(day), stressAvg = stress)

    private fun walk(day: String, min: Int) = ExerciseLog(
        epochMs = DayKeys.startMs(day) + 12 * 3_600_000L, dayKey = day,
        type = "Walk", durationMin = min, calories = min * 5
    )

    @Test fun recapsYesterdayNotTheLatestDayWithData() {
        val r = DailyRecapEngine.yesterday(
            listOf(metric("2026-10-24", 45), metric("2026-10-26", 26)),
            listOf(walk("2026-10-24", 29), walk("2026-10-26", 50)),
            emptyMap(), 2000, todayKey = "2026-10-26"
        )
        // Oct 25 has nothing synced, so the update must not replay Oct 24 (or today) as "yesterday".
        assertFalse(r.hasData)
    }

    @Test fun usesYesterdaysOwnNumbers() {
        val r = DailyRecapEngine.yesterday(
            listOf(metric("2026-10-25", 30)),
            listOf(walk("2026-10-25", 40), walk("2026-10-26", 90)),
            mapOf("2026-10-25" to 1800), 2000, todayKey = "2026-10-26"
        )
        assertTrue(r.hasData)
        assertTrue(r.summary, r.summary.contains("40 min"))
        assertTrue(r.summary, r.summary.contains("1800 kcal"))
    }

    @Test fun emptyGarminRowIsNotData() {
        val empty = DailyHealthMetric(dayKey = "2026-10-25", epochMs = 0L)
        assertFalse(DailyRecapEngine.yesterday(listOf(empty), emptyList(), emptyMap(), 2000, "2026-10-26").hasData)
    }

    @Test fun withoutAWatchTheRecapIsBuiltFromFoodAlone() {
        val r = DailyRecapEngine.yesterday(emptyList(), emptyList(), mapOf("2026-10-25" to 1700), 2000, "2026-10-26")
        assertTrue(r.hasData)
        assertTrue(r.stats.none { it.kind == RecapKind.SLEEP || it.kind == RecapKind.STRESS || it.kind == RecapKind.RESTING_HR })
        assertFalse(r.summary, r.summary.contains("watch", ignoreCase = true))
        assertEquals(null, RecoveryEngine.assess(emptyList(), "2026-10-26", "2026-10-25"))
    }
}
