package ai.anthracite.fasttrack.nutrition

import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionValue
import ai.anthracite.fasttrack.nutrition.model.UnitKind
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The single source of truth for turning [FoodItem]/[FoodLogEntry] data into calorie and macro
 * totals. Pure, deterministic, and framework-free so it's trivially unit-testable.
 *
 * ### Rules (see [ai.anthracite.fasttrack.nutrition.model] for the full convention)
 * - Nutrition on an item is **per one unit**; multiply by [FoodItem.quantity] only. [Portion] is
 *   descriptive and is never multiplied in again (no double-counting).
 * - `null` means unknown and is **excluded** from totals (never treated as 0). The result reports
 *   how many items were covered so callers can show "partial" / "incomplete".
 * - Values are kept unrounded internally; round only via [round0]/[round1] for display.
 * - Totals are recomputed from the live item list every call, so add/edit/remove just works.
 */
object NutritionCalculator {

    /**
     * A calorie/macro total with an optional low–high range plus a coverage count so the caller
     * can distinguish a confident total from one missing data.
     */
    data class RangedTotal(
        val estimate: Double?,
        val low: Double?,
        val high: Double?,
        val knownItems: Int,
        val totalItems: Int
    ) {
        /** True when every contributing item had a known value. */
        val isComplete: Boolean get() = knownItems == totalItems
        /** True when some but not all items had known values. */
        val isPartial: Boolean get() = knownItems in 1 until totalItems
        val hasAnyData: Boolean get() = knownItems > 0

        companion object {
            val EMPTY = RangedTotal(null, null, null, 0, 0)
        }
    }

    /** Selects one nutrition value (e.g. protein) from an item for aggregation. */
    fun interface NutrientSelector {
        fun select(item: FoodItem): NutritionValue?
    }

    val CALORIES = NutrientSelector { it.nutrition.caloriesKcal }
    val PROTEIN = NutrientSelector { it.nutrition.proteinGrams }
    val CARBS = NutrientSelector { it.nutrition.carbohydratesGrams }
    val FAT = NutrientSelector { it.nutrition.fatGrams }
    val FIBER = NutrientSelector { it.nutrition.fiberGrams }
    val SUGAR = NutrientSelector { it.nutrition.sugarGrams }
    val ALCOHOL = NutrientSelector { it.nutrition.alcoholGrams }

    // ---- Per-item -----------------------------------------------------------------------------

    /** Scales one per-unit [NutritionValue] by the item's quantity. Unknown estimate → null total. */
    fun itemValue(item: FoodItem, selector: NutrientSelector): RangedTotal {
        val value = selector.select(item)
        val qty = item.quantity
        if (value?.estimate == null) {
            return RangedTotal(null, null, null, knownItems = 0, totalItems = 1)
        }
        val est = value.estimate * qty
        // Fall back to the estimate when a bound is missing so the range never understates it.
        val low = (value.low ?: value.estimate) * qty
        val high = (value.high ?: value.estimate) * qty
        return RangedTotal(est, low, high, knownItems = 1, totalItems = 1)
    }

    fun itemCalories(item: FoodItem): RangedTotal = itemValue(item, CALORIES)

    // ---- Per-meal -----------------------------------------------------------------------------

    /** Sum a nutrient across the items of a meal, tracking coverage and the combined range. */
    fun total(items: List<FoodItem>, selector: NutrientSelector): RangedTotal {
        if (items.isEmpty()) return RangedTotal.EMPTY
        var est = 0.0; var low = 0.0; var high = 0.0
        var known = 0
        for (item in items) {
            val t = itemValue(item, selector)
            if (t.estimate == null) continue
            est += t.estimate
            low += t.low ?: t.estimate
            high += t.high ?: t.estimate
            known++
        }
        return if (known == 0) {
            RangedTotal(null, null, null, 0, items.size)
        } else {
            RangedTotal(est, low, high, knownItems = known, totalItems = items.size)
        }
    }

    fun mealCalories(entry: FoodLogEntry): RangedTotal = total(entry.items, CALORIES)
    fun mealCalories(items: List<FoodItem>): RangedTotal = total(items, CALORIES)

    /** Protein/carbs/fat/fiber/sugar/alcohol totals in one pass, each with its own coverage. */
    data class MacroTotals(
        val protein: RangedTotal,
        val carbs: RangedTotal,
        val fat: RangedTotal,
        val fiber: RangedTotal,
        val sugar: RangedTotal,
        val alcohol: RangedTotal
    )

    fun macroTotals(items: List<FoodItem>): MacroTotals = MacroTotals(
        protein = total(items, PROTEIN),
        carbs = total(items, CARBS),
        fat = total(items, FAT),
        fiber = total(items, FIBER),
        sugar = total(items, SUGAR),
        alcohol = total(items, ALCOHOL)
    )

    /** Count of standard alcoholic servings = sum of quantities of items flagged alcoholic. */
    fun alcoholicServings(items: List<FoodItem>): Double =
        items.filter { it.classification.isAlcoholic }.sumOf { it.quantity }

    // ---- Rounding (presentation only) ---------------------------------------------------------

    fun round0(v: Double?): Int? = v?.roundToInt()
    fun round1(v: Double?): Double? = v?.let { (it * 10).roundToLong() / 10.0 }

    // ---- Unit conversion ----------------------------------------------------------------------

    /**
     * Convert [amount] from [from] to [to] when they share a [UnitKind] (both MASS or both VOLUME).
     * Returns null for incompatible conversions — notably grams↔milliliters, which require a
     * food-specific density we deliberately do not assume, and anything involving COUNT units.
     */
    fun convert(amount: Double, from: MeasurementUnit, to: MeasurementUnit): Double? {
        if (from == to) return amount
        if (from.kind == UnitKind.COUNT || to.kind == UnitKind.COUNT) return null
        if (from.kind != to.kind) return null // never silently cross mass <-> volume
        // Normalize to the base unit (g or ml), then to the target.
        return amount * from.base / to.base
    }
}
