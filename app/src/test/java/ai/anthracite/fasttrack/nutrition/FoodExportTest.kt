package ai.anthracite.fasttrack.nutrition

import ai.anthracite.fasttrack.nutrition.export.ExportRange
import ai.anthracite.fasttrack.nutrition.export.FoodExport
import ai.anthracite.fasttrack.nutrition.model.SampleMeals
import ai.anthracite.fasttrack.util.DayKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FoodExportTest {

    private val now = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 23, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `today range is a single day`() {
        val r = ExportRange.TODAY.resolve(now)
        assertEquals(DayKeys.of(now), r.fromKey)
        assertEquals(DayKeys.of(now), r.toKey)
        assertFalse(r.isAllTime)
    }

    @Test fun `this month starts on the first`() {
        val r = ExportRange.THIS_MONTH.resolve(now)
        assertTrue("fromKey=${r.fromKey}", r.fromKey!!.endsWith("-01"))
        assertEquals(DayKeys.of(now), r.toKey)
    }

    @Test fun `last 30 days starts 29 days before today`() {
        val r = ExportRange.LAST_30_DAYS.resolve(now)
        val expectedFrom = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -29)
        }.timeInMillis
        assertEquals(DayKeys.of(expectedFrom), r.fromKey)
    }

    @Test fun `all time is unbounded`() {
        val r = ExportRange.ALL_TIME.resolve(now)
        assertTrue(r.isAllTime)
        assertNull(r.fromKey)
    }

    @Test fun `envelope carries metadata, summary and full entries`() {
        val entry = SampleMeals.drinksAndCheesesteak()
        val json = FoodExport.build(listOf(entry), ExportRange.ALL_TIME.resolve(now), now)
        val root = JSONObject(json)

        val meta = root.getJSONObject("export")
        assertEquals("FastTrack", meta.getString("app"))
        assertEquals("food_log", meta.getString("type"))
        assertEquals(1, meta.getInt("entryCount"))
        assertEquals(4, meta.getInt("itemCount"))

        val summary = root.getJSONObject("summary")
        assertEquals(2170.0, summary.getJSONObject("calories").getDouble("estimate"), 0.5)
        assertEquals(5.0, summary.getDouble("alcoholicServings"), 0.0001)

        assertEquals(1, root.getJSONArray("entries").length())
    }

    @Test fun `unknown macro totals export as json null, not zero`() {
        // The fixture has no fiber data on any item -> fiber must be null (unknown), never 0.
        val entry = SampleMeals.drinksAndCheesesteak()
        val json = FoodExport.build(listOf(entry), ExportRange.ALL_TIME.resolve(now), now)
        val macros = JSONObject(json).getJSONObject("summary").getJSONObject("macrosGrams")
        assertTrue(macros.isNull("fiber"))
    }
}
