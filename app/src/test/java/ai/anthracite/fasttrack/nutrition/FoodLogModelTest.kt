package ai.anthracite.fasttrack.nutrition

import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.nutrition.model.NutritionValue
import ai.anthracite.fasttrack.nutrition.model.SampleMeals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLogModelTest {

    @Test fun `nutrition value validates ordering and non-negativity`() {
        assertNull(NutritionValue(180.0, 160.0, 200.0).validationError())
        assertNotNull(NutritionValue(180.0, 200.0, 160.0).validationError()) // low > high
        assertNotNull(NutritionValue(180.0, 190.0, 200.0).validationError()) // low > estimate
        assertNotNull(NutritionValue(-5.0).validationError())                // negative
        assertNull(NutritionValue(0.0).validationError())                    // zero is valid (known)
    }

    @Test fun `unknown is distinct from zero`() {
        assertFalse(NutritionValue.UNKNOWN.isKnown)
        assertTrue(NutritionValue(0.0).isKnown)
    }

    @Test fun `entry survives a JSON round-trip`() {
        val original = SampleMeals.drinksAndCheesesteak()
        val restored = FoodLogEntry.fromJsonString(original.toJsonString())

        assertEquals(original.id, restored.id)
        assertEquals(original.mealName, restored.mealName)
        assertEquals(original.mealType, restored.mealType)
        assertEquals(original.items.size, restored.items.size)

        val cider = restored.items.first()
        assertEquals("hard_cider", cider.canonicalName)
        assertEquals(4.0, cider.quantity, 0.0001)
        assertEquals(180.0, cider.nutrition.caloriesKcal.estimate!!, 0.0001)
        assertEquals(160.0, cider.nutrition.caloriesKcal.low!!, 0.0001)
        assertTrue(cider.classification.isAlcoholic)
        assertFalse(cider.nutrition.caloriesKcal.verified)
    }

    @Test fun `null nutrition serializes as null and restores as unknown`() {
        val original = SampleMeals.drinksAndCheesesteak()
        val restored = FoodLogEntry.fromJsonString(original.toJsonString())
        // Fries declare no protein -> should remain unknown (null), not 0.
        val fries = restored.items.first { it.canonicalName == "french_fries" }
        assertNull(fries.nutrition.proteinGrams)
    }

    @Test fun `valid entry reports no validation error`() {
        assertNull(SampleMeals.drinksAndCheesesteak().validationError())
    }

    @Test fun `exported JSON is valid and parseable text`() {
        val json = SampleMeals.drinksAndCheesesteak().toJsonString()
        assertTrue(json.contains("\"schema_version\""))
        assertTrue(json.contains("\"items\""))
        // Round-trips without throwing.
        FoodLogEntry.fromJsonString(json)
    }
}
