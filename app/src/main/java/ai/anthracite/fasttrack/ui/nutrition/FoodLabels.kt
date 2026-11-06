package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit

/** Human-readable display labels for the food model enums. Display only; never persisted. */
object FoodLabels {

    /** Compact quantity: "4", "1.5", "0.5" (no trailing ".0"). */
    fun quantity(q: Double): String =
        if (q == q.toLong().toDouble()) q.toLong().toString()
        else q.toString().trimEnd('0').trimEnd('.')

    fun unitShort(unit: MeasurementUnit): String = when (unit) {
        MeasurementUnit.SERVING -> "serving"
        MeasurementUnit.ITEM -> "item"
        MeasurementUnit.GRAM -> "g"
        MeasurementUnit.KILOGRAM -> "kg"
        MeasurementUnit.OUNCE -> "oz"
        MeasurementUnit.POUND -> "lb"
        MeasurementUnit.MILLILITER -> "ml"
        MeasurementUnit.LITER -> "L"
        MeasurementUnit.FLUID_OUNCE -> "fl oz"
        MeasurementUnit.CUP -> "cup"
        MeasurementUnit.OTHER -> ""
    }

    /** All units, for the edit dialog's dropdown, in a sensible order. */
    val unitOptions: List<MeasurementUnit> = listOf(
        MeasurementUnit.SERVING,
        MeasurementUnit.ITEM,
        MeasurementUnit.GRAM,
        MeasurementUnit.KILOGRAM,
        MeasurementUnit.OUNCE,
        MeasurementUnit.POUND,
        MeasurementUnit.MILLILITER,
        MeasurementUnit.LITER,
        MeasurementUnit.FLUID_OUNCE,
        MeasurementUnit.CUP,
        MeasurementUnit.OTHER
    )

    fun unitOptionLabel(unit: MeasurementUnit): String = when (unit) {
        MeasurementUnit.SERVING -> "serving"
        MeasurementUnit.ITEM -> "item"
        MeasurementUnit.GRAM -> "grams (g)"
        MeasurementUnit.KILOGRAM -> "kilograms (kg)"
        MeasurementUnit.OUNCE -> "ounces (oz)"
        MeasurementUnit.POUND -> "pounds (lb)"
        MeasurementUnit.MILLILITER -> "milliliters (ml)"
        MeasurementUnit.LITER -> "liters (L)"
        MeasurementUnit.FLUID_OUNCE -> "fluid ounces (fl oz)"
        MeasurementUnit.CUP -> "cups"
        MeasurementUnit.OTHER -> "other"
    }

    /** "12 fl oz each", "150 g each", "1 serving each". */
    fun unit(portionAmount: Double, unit: MeasurementUnit): String {
        val short = unitShort(unit)
        if (short.isBlank()) return ""
        return "${quantity(portionAmount)} $short each"
    }

    fun category(category: ItemCategory): String = when (category) {
        ItemCategory.FOOD -> "food"
        ItemCategory.BEVERAGE -> "beverage"
        ItemCategory.SUPPLEMENT -> "supplement"
    }

    private fun confidence(c: Confidence): String = when (c) {
        Confidence.LOW -> "low confidence"
        Confidence.MEDIUM -> "medium confidence"
        Confidence.HIGH -> "high confidence"
    }

    /** e.g. "estimated · low confidence" or "verified". */
    fun quality(item: FoodItem): String {
        val cal = item.nutrition.caloriesKcal
        return if (cal.verified) "verified"
        else "estimated · ${confidence(cal.confidence)}"
    }
}
