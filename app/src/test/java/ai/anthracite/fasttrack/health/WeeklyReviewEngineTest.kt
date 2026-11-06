package ai.anthracite.fasttrack.health

import ai.anthracite.fasttrack.data.health.WeeklyReviewEngine
import ai.anthracite.fasttrack.data.health.WeightTrend
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.nutrition.barcode.OpenFoodFacts
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.util.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class WeeklyReviewEngineTest {
    private val utc = ZoneOffset.UTC
    private fun ms(day: String, hour: Int) = LocalDate.parse(day).atTime(hour, 0).toInstant(utc).toEpochMilli()

    /** The overnight fast credited to [day]: starts 8 PM the evening before, ends on [day]. */
    private fun fast(day: String, hours: Int, goal: Double = 16.0): FastingSession {
        val start = ms(day, 20) - 86_400_000L
        return FastingSession(
            startEpochMs = start,
            endEpochMs = start + hours * 3_600_000L,
            goalHours = goal,
            protocol = "SIXTEEN_EIGHT",
            completed = hours >= goal
        )
    }

    private val week = LocalDate.parse("2026-10-19")

    private fun inputs() = WeeklyReviewEngine.Inputs(
        fasts = listOf(
            // this week: 5 completed + 1 broken Friday
            fast("2026-10-19", 16), fast("2026-10-20", 17), fast("2026-10-21", 16),
            fast("2026-10-22", 18), fast("2026-10-23", 10), fast("2026-10-24", 16),
            // last week: 2 completed, broken Friday
            fast("2026-10-12", 16), fast("2026-10-14", 16), fast("2026-10-16", 9)
        ),
        caloriesByDay = mapOf(
            "2026-10-19" to 1700, "2026-10-20" to 1750, "2026-10-21" to 1650, "2026-10-22" to 1700,
            "2026-10-23" to 1800, "2026-10-24" to 2600, "2026-10-25" to 2500
        ),
        calorieTarget = 1900,
        waterByDay = (19..25).associate { "2026-10-$it" to 3000.0 },
        waterGoalMl = 2800.0,
        exerciseMinutesByDay = mapOf("2026-10-20" to 60, "2026-10-22" to 60, "2026-10-24" to 45),
        sleepMinutesByDay = (19..25).associate { "2026-10-$it" to 450 },
        weights = (0..13).map {
            WeightTrend.Point(ms("2026-10-12", 7) + it * 86_400_000L, 95.0 - 0.1 * it)
        },
        goal = WeightGoal.LOSE,
        unit = UnitSystem.METRIC,
        zone = utc
    )

    @Test
    fun statsCompareThisWeekToLast() {
        val r = WeeklyReviewEngine.build(week, inputs())
        assertEquals(6, r.stats.fastsStarted)
        assertEquals(5, r.stats.fastsCompleted)
        assertEquals(2, r.previous.fastsCompleted)
        assertEquals(18.0, r.stats.longestFastHours!!, 0.0)
        assertEquals(7, r.stats.waterGoalDays)
        assertEquals(165, r.stats.activeMinutes)
        assertEquals(2, r.stats.daysOverTarget)
        assertTrue(r.stats.weightChangeKg!! < 0)
        assertEquals("2026-10-19", r.weekKey)
    }

    @Test
    fun writesWinsPatternsAndFocus() {
        val r = WeeklyReviewEngine.build(week, inputs())
        assertTrue(r.wins.any { it.contains("up from 2") })
        assertTrue(r.wins.any { it.contains("water goal") })
        assertTrue(r.patterns.any { it.contains("Fridays") })
        assertTrue(r.patterns.any { it.contains("Weekend calories") })
        assertTrue(r.focus.contains("Friday"))
        assertTrue(r.headline.startsWith("5 fasts completed"))
        assertTrue(r.toPrompt().contains("Focus for next week"))
    }

    @Test
    fun reviewWeekIsThisWeekOnSundayOtherwiseLast() {
        assertEquals(LocalDate.parse("2026-10-19"), WeeklyReviewEngine.reviewWeekFor(LocalDate.parse("2026-10-25")))
        assertEquals(LocalDate.parse("2026-10-19"), WeeklyReviewEngine.reviewWeekFor(LocalDate.parse("2026-10-28")))
    }

    @Test
    fun emptyWeekStillProducesAReview() {
        val r = WeeklyReviewEngine.build(week, WeeklyReviewEngine.Inputs(zone = utc))
        assertEquals(0, r.stats.fastsCompleted)
        assertNull(r.stats.avgFastHours)
        assertTrue(r.focus.isNotBlank())
    }
}

class OpenFoodFactsTest {
    @Test
    fun parsesPerServingLabel() {
        val body = """{"status":1,"product":{"product_name":"Greek Yogurt","brands":"Fage","serving_quantity":170,
            "nutriments":{"energy-kcal_serving":100,"proteins_serving":18,"carbohydrates_serving":6,"fat_serving":0,
            "energy-kcal_100g":59}}}"""
        val r = OpenFoodFacts.parse(body)!!
        assertTrue(r.perServing)
        assertEquals("Fage Greek Yogurt", r.item.name)
        assertEquals(100.0, r.item.nutrition.caloriesKcal.estimate!!, 0.01)
        assertEquals(18.0, r.item.nutrition.proteinGrams!!.estimate!!, 0.01)
        assertEquals(NutritionSource.PACKAGE_LABEL, r.item.nutrition.caloriesKcal.source)
    }

    @Test
    fun fallsBackToPer100g() {
        val body = """{"status":1,"product":{"product_name":"Rolled Oats","nutriments":{"energy-kcal_100g":379,"proteins_100g":13}}}"""
        val r = OpenFoodFacts.parse(body)!!
        assertFalse(r.perServing)
        assertEquals(379.0, r.item.nutrition.caloriesKcal.estimate!!, 0.01)
        assertEquals(100.0, r.item.portion.amount, 0.0)
    }

    @Test
    fun unknownProductIsNull() {
        assertNull(OpenFoodFacts.parse("""{"status":0,"status_verbose":"product not found"}"""))
        assertNotNull(OpenFoodFacts.parse("""{"status":1,"product":{"brands":"Acme","nutriments":{"energy-kcal_100g":50}}}"""))
    }
}
