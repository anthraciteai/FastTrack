package ai.anthracite.fasttrack.nutrition

import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionEstimate
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.nutrition.model.NutritionValue
import ai.anthracite.fasttrack.nutrition.model.SampleMeals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionCalculatorTest {

    private fun item(
        name: String,
        qty: Double,
        cal: Double?,
        low: Double? = null,
        high: Double? = null,
        protein: Double? = null
    ) = FoodItem(
        name = name,
        canonicalName = name.lowercase(),
        quantity = qty,
        nutrition = NutritionEstimate(
            caloriesKcal = NutritionValue(cal, low, high, NutritionSource.MODEL_ESTIMATE, Confidence.MEDIUM),
            proteinGrams = protein?.let { NutritionValue(it) }
        )
    )

    @Test fun `item calories scale by quantity, not portion`() {
        val t = NutritionCalculator.itemCalories(item("cider", qty = 4.0, cal = 180.0, low = 160.0, high = 200.0))
        assertEquals(720.0, t.estimate!!, 0.0001)
        assertEquals(640.0, t.low!!, 0.0001)
        assertEquals(800.0, t.high!!, 0.0001)
        assertTrue(t.isComplete)
    }

    @Test fun `missing bounds fall back to estimate so range never understates`() {
        val t = NutritionCalculator.itemCalories(item("mystery", qty = 2.0, cal = 100.0))
        assertEquals(200.0, t.estimate!!, 0.0001)
        assertEquals(200.0, t.low!!, 0.0001)
        assertEquals(200.0, t.high!!, 0.0001)
    }

    @Test fun `unknown calories are excluded, not treated as zero`() {
        val items = listOf(
            item("known", qty = 1.0, cal = 200.0),
            item("unknown", qty = 1.0, cal = null)
        )
        val t = NutritionCalculator.mealCalories(items)
        assertEquals(200.0, t.estimate!!, 0.0001)
        assertEquals(1, t.knownItems)
        assertEquals(2, t.totalItems)
        assertFalse(t.isComplete)
        assertTrue(t.isPartial)
    }

    @Test fun `all unknown yields null total with no coverage`() {
        val t = NutritionCalculator.mealCalories(listOf(item("a", 1.0, null), item("b", 1.0, null)))
        assertNull(t.estimate)
        assertEquals(0, t.knownItems)
        assertFalse(t.hasAnyData)
    }

    @Test fun `editing removes an item and recomputes`() {
        val items = mutableListOf(
            item("beer", 1.0, 150.0),
            item("fries", 1.0, 450.0)
        )
        assertEquals(600.0, NutritionCalculator.mealCalories(items).estimate!!, 0.0001)
        items.removeAt(1)
        assertEquals(150.0, NutritionCalculator.mealCalories(items).estimate!!, 0.0001)
    }

    @Test fun `example meal totals 2170 with a sensible range`() {
        val entry = SampleMeals.drinksAndCheesesteak()
        val total = NutritionCalculator.mealCalories(entry)
        assertEquals(2170.0, total.estimate!!, 0.0001)
        // 4*160 + 130 + 750 + 380 = 1900 ; 4*200 + 170 + 1000 + 520 = 2490
        assertEquals(1900.0, total.low!!, 0.0001)
        assertEquals(2490.0, total.high!!, 0.0001)
        assertTrue(total.isComplete)
    }

    @Test fun `alcoholic servings count quantities of flagged items`() {
        val entry = SampleMeals.drinksAndCheesesteak()
        // 4 ciders + 1 beer = 5 servings
        assertEquals(5.0, NutritionCalculator.alcoholicServings(entry.items), 0.0001)
    }

    @Test fun `macro totals aggregate only known values`() {
        val entry = SampleMeals.drinksAndCheesesteak()
        val macros = NutritionCalculator.macroTotals(entry.items)
        // Only the cheesesteak declares protein (40g x1).
        assertEquals(40.0, macros.protein.estimate!!, 0.0001)
        assertEquals(1, macros.protein.knownItems)
    }

    @Test fun `mass conversions use exact factors`() {
        val oz = NutritionCalculator.convert(100.0, MeasurementUnit.GRAM, MeasurementUnit.OUNCE)!!
        assertEquals(3.5274, oz, 0.001)
        val g = NutritionCalculator.convert(1.0, MeasurementUnit.POUND, MeasurementUnit.GRAM)!!
        assertEquals(453.592, g, 0.01)
    }

    @Test fun `volume conversions use exact factors`() {
        val floz = NutritionCalculator.convert(355.0, MeasurementUnit.MILLILITER, MeasurementUnit.FLUID_OUNCE)!!
        assertEquals(12.0, floz, 0.02)
    }

    @Test fun `grams and milliliters never convert without density`() {
        assertNull(NutritionCalculator.convert(100.0, MeasurementUnit.GRAM, MeasurementUnit.MILLILITER))
        assertNull(NutritionCalculator.convert(1.0, MeasurementUnit.SERVING, MeasurementUnit.GRAM))
    }

    @Test fun `rounding is presentation only`() {
        assertEquals(2170, NutritionCalculator.round0(2170.4))
        assertEquals(2170.4, NutritionCalculator.round1(2170.44)!!, 0.0001)
    }
}
