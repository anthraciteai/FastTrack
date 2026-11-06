package ai.anthracite.fasttrack.health

import ai.anthracite.fasttrack.data.health.CheckInInsights
import ai.anthracite.fasttrack.data.health.RecoveryEngine
import ai.anthracite.fasttrack.data.health.StreaksEngine
import ai.anthracite.fasttrack.data.health.WeightTrend
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.notifications.FastingMilestones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeightTrendTest {
    private val day = 86_400_000L
    private val t0 = 1_760_000_000_000L

    @Test
    fun steadyLossGivesNegativeWeeklyRateAndEta() {
        val points = (0..20).map { WeightTrend.Point(t0 + it * day, 100.0 - 0.1 * it) }
        val r = WeightTrend.compute(points, goalKg = 95.0, now = t0 + 20 * day)!!
        assertEquals(-0.7, r.kgPerWeek!!, 0.01)
        assertNotNull(r.etaEpochMs)
        assertTrue(r.etaEpochMs!! > t0 + 20 * day)
        assertEquals(21, r.smoothed.size)
    }

    @Test
    fun smoothingLagsBehindNoisyLatestReading() {
        val points = listOf(
            WeightTrend.Point(t0, 100.0),
            WeightTrend.Point(t0 + day, 100.0),
            WeightTrend.Point(t0 + 2 * day, 104.0)
        )
        val r = WeightTrend.compute(points, null, t0 + 2 * day)!!
        assertTrue(r.latestTrendKg < 101.5)
    }

    @Test
    fun tooFewOrTooCloseWeighInsHaveNoRate() {
        val two = listOf(WeightTrend.Point(t0, 90.0), WeightTrend.Point(t0 + 10 * day, 89.0))
        assertNull(WeightTrend.slopePerWeek(two))
        val sameWeek = (0..3).map { WeightTrend.Point(t0 + it * day, 90.0 - it) }
        assertNull(WeightTrend.slopePerWeek(sameWeek))
    }

    @Test
    fun noEtaWhenMovingAwayFromGoal() {
        assertNull(WeightTrend.etaFor(currentKg = 90.0, goalKg = 80.0, kgPerWeek = 0.4, now = t0))
        assertEquals(t0, WeightTrend.etaFor(80.1, 80.0, -0.5, t0))
    }
}

class RecoveryEngineTest {
    private fun metric(day: String, bb: Int? = null, sleep: Int? = null, stress: Int? = null, rhr: Int? = null) =
        DailyHealthMetric(day, 0L, stress, rhr, bb, sleep, "GARMIN")

    private val baseline = (1..14).map {
        metric(LocalDate.parse("2026-10-26").minusDays(it.toLong()).toString(), rhr = 55)
    }

    @Test
    fun wellRestedIsHigh() {
        val r = RecoveryEngine.assess(
            baseline + metric("2026-10-26", bb = 85, sleep = 480, stress = 20, rhr = 52),
            "2026-10-26", "2026-10-25"
        )!!
        assertEquals(RecoveryEngine.Level.HIGH, r.level)
        assertNull(r.suggestedMaxFastHours)
        assertTrue(r.factors.any { it.startsWith("Body Battery") })
    }

    @Test
    fun poorRecoveryIsLowAndCapsTheFast() {
        val r = RecoveryEngine.assess(
            baseline + metric("2026-10-26", bb = 15, sleep = 300, stress = 70, rhr = 62),
            "2026-10-26", "2026-10-25"
        )!!
        assertEquals(RecoveryEngine.Level.LOW, r.level)
        assertTrue(r.score < 45)
        assertEquals(16.0, r.suggestedMaxFastHours!!, 0.0)
        assertTrue(r.factors.contains("Resting HR +7 bpm vs usual"))
    }

    @Test
    fun fallsBackToYesterdayAndNullWithoutData() {
        val r = RecoveryEngine.assess(listOf(metric("2026-10-25", bb = 60)), "2026-10-26", "2026-10-25")
        assertNotNull(r)
        assertNull(RecoveryEngine.assess(emptyList(), "2026-10-26", "2026-10-25"))
        assertNull(RecoveryEngine.assess(listOf(metric("2026-10-20", bb = 60)), "2026-10-26", "2026-10-25"))
    }
}

class StreaksEngineTest {
    @Test
    fun currentStreakHoldsUntilTodayIsLogged() {
        val days = setOf("2026-10-23", "2026-10-24", "2026-10-25")
        assertEquals(3, StreaksEngine.current(days, "2026-10-26"))
        assertEquals(4, StreaksEngine.current(days + "2026-10-26", "2026-10-26"))
        assertEquals(0, StreaksEngine.current(setOf("2026-10-20"), "2026-10-26"))
    }

    @Test
    fun longestFindsBestRun() {
        val days = setOf("2026-10-01", "2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07")
        assertEquals(3, StreaksEngine.longest(days))
    }

    @Test
    fun weeklyChallengesOnlyCountThisWeek() {
        val inputs = StreaksEngine.Inputs(
            todayKey = "2026-10-28",
            completedFastHours = mapOf("2026-10-25" to 16.0, "2026-10-26" to 16.5, "2026-10-27" to 14.0),
            exerciseMinutes = mapOf("2026-10-26" to 90, "2026-10-27" to 80, "2026-10-19" to 300)
        )
        val c = StreaksEngine.weeklyChallenges(inputs).associateBy { it.id }
        assertEquals(2, c["fasts5"]!!.progress)
        assertEquals(1, c["long3"]!!.progress)
        assertEquals(150, c["move150"]!!.progress)
        assertTrue(c["move150"]!!.completed)
        assertFalse(c["fasts5"]!!.completed)
    }

    @Test
    fun daysLeftCountsTodayThroughSunday() {
        assertEquals(7, StreaksEngine.daysLeftInWeek("2026-10-26"))
        assertEquals(1, StreaksEngine.daysLeftInWeek("2026-11-01"))
    }

    @Test
    fun badgesUnlockFromStreaks() {
        val water = (0L..6L).map { LocalDate.parse("2026-10-20").plusDays(it).toString() }.toSet()
        val badges = StreaksEngine.badges(StreaksEngine.Inputs(todayKey = "2026-10-26", waterGoalDays = water))
            .associateBy { it.id }
        assertTrue(badges["water7"]!!.unlocked)
        assertFalse(badges["checkin7"]!!.unlocked)
    }
}

class CheckInInsightsTest {
    private fun c(day: String, mood: Int, energy: Int, hunger: Int = 3) = DailyCheckIn(day, 0L, mood, energy, hunger)

    @Test
    fun findsHigherEnergyOnFastingDays() {
        val fastDays = listOf("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22")
        val otherDays = listOf("2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26")
        val checkIns = fastDays.map { c(it, 3, 4) } + otherDays.map { c(it, 3, 2) }
        val r = CheckInInsights.compute(checkIns, fastDays.associateWith { 16.0 }, "2026-10-26")
        assertTrue(r.headline!!.contains("energy"))
        assertTrue(r.headline!!.contains("higher"))
        assertEquals(7, r.recentDays)
    }

    @Test
    fun asksForMoreDataWhenSparse() {
        val r = CheckInInsights.compute(listOf(c("2026-10-26", 4, 4)), emptyMap(), "2026-10-26")
        assertTrue(r.headline!!.startsWith("Check in 5 more days"))
        assertEquals(4.0, r.avgMood!!, 0.0)
    }
}

class FastingMilestonesTest {
    private val h = 3_600_000L

    @Test
    fun plansUpcomingStagesAndMergesGoalWithSameHourStage() {
        val plan = FastingMilestones.plan(startMs = 0L, goalHours = 16.0, nowMs = 1 * h)
        assertEquals(listOf(8L, 12L, 16L, 18L, 24L, 48L), plan.map { it.atMs / h })
        assertEquals(1, plan.count { it.isGoal })
        assertTrue(plan.first { it.atMs == 16 * h }.isGoal)
    }

    @Test
    fun skipsPastMilestones() {
        val plan = FastingMilestones.plan(startMs = 0L, goalHours = 18.0, nowMs = 13 * h)
        assertEquals(listOf(16L, 18L, 24L, 48L), plan.map { it.atMs / h })
        assertTrue(plan.first { it.atMs == 18 * h }.isGoal)
    }
}
