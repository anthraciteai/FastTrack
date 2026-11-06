package ai.anthracite.fasttrack.nutrition

import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.util.DayKeys

/**
 * Read-only analytics over stored [FoodLogEntry] data. Every function is pure and framework-free
 * (it takes the entries it operates on) so it can be unit-tested and is cheap to call off a
 * repository query. Nothing here mutates state or persists.
 *
 * Unknown nutrition values stay unknown: totals are built on [NutritionCalculator], which excludes
 * `null` rather than treating it as `0`, so a day with incomplete data is reported as such.
 */
object FoodAnalytics {

    /** Local-timezone day bucket (yyyy-MM-dd) for an entry, derived from its ISO timestamp. */
    private fun dayKeyOf(entry: FoodLogEntry): String =
        ai.anthracite.fasttrack.nutrition.model.Iso8601.parseOrNull(entry.loggedAt)
            ?.let { DayKeys.of(it) } ?: entry.loggedAt.take(10)

    private fun allItems(entries: List<FoodLogEntry>): List<FoodItem> = entries.flatMap { it.items }

    /** Total calories per day (yyyy-MM-dd → kcal). Days with no known data are omitted. */
    fun dailyCalorieTotals(entries: List<FoodLogEntry>): Map<String, Double> =
        entries.groupBy { dayKeyOf(it) }
            .mapValues { (_, dayEntries) ->
                NutritionCalculator.total(allItems(dayEntries), NutritionCalculator.CALORIES).estimate
            }
            .filterValues { it != null }
            .mapValues { it.value!! }

    /** Average calories across the given days (ignores days without a known total). */
    fun averageDailyCalories(entries: List<FoodLogEntry>): Double? {
        val totals = dailyCalorieTotals(entries).values
        return if (totals.isEmpty()) null else totals.average()
    }

    /** Average calories per logged meal/entry (entries with no known calorie data are skipped). */
    fun averageCaloriesPerMeal(entries: List<FoodLogEntry>): Double? {
        val perMeal = entries.mapNotNull { NutritionCalculator.mealCalories(it).estimate }
        return if (perMeal.isEmpty()) null else perMeal.average()
    }

    /** Calories grouped by broad item category (food / beverage / supplement). */
    fun caloriesByCategory(entries: List<FoodLogEntry>): Map<ItemCategory, Double> =
        allItems(entries).groupBy { it.category }
            .mapValues { (_, items) ->
                NutritionCalculator.total(items, NutritionCalculator.CALORIES).estimate ?: 0.0
            }

    /** Calories grouped by free-form food type (e.g. "beer", "sandwich"); blanks grouped as "". */
    fun caloriesByFoodType(entries: List<FoodLogEntry>): Map<String, Double> =
        allItems(entries).groupBy { it.foodType }
            .mapValues { (_, items) ->
                NutritionCalculator.total(items, NutritionCalculator.CALORIES).estimate ?: 0.0
            }

    /** Total standard alcoholic servings logged (sum of quantities of alcoholic items). */
    fun alcoholicServings(entries: List<FoodLogEntry>): Double =
        NutritionCalculator.alcoholicServings(allItems(entries))

    /** Total grams of alcohol where known (unknown excluded, never counted as zero). */
    fun alcoholGrams(entries: List<FoodLogEntry>): Double? =
        NutritionCalculator.total(allItems(entries), NutritionCalculator.ALCOHOL).estimate

    /** Alcoholic servings per day. */
    fun alcoholicServingsByDay(entries: List<FoodLogEntry>): Map<String, Double> =
        entries.groupBy { dayKeyOf(it) }
            .mapValues { (_, dayEntries) -> NutritionCalculator.alcoholicServings(allItems(dayEntries)) }

    /** Macro (protein/carbs/fat/…) totals per day, for trend charts. */
    fun macroTotalsByDay(entries: List<FoodLogEntry>): Map<String, NutritionCalculator.MacroTotals> =
        entries.groupBy { dayKeyOf(it) }
            .mapValues { (_, dayEntries) -> NutritionCalculator.macroTotals(allItems(dayEntries)) }

    /** Most frequently logged foods by canonical name → number of occurrences, most common first. */
    fun mostFrequentFoods(entries: List<FoodLogEntry>, limit: Int = 10): List<Pair<String, Int>> =
        allItems(entries).groupingBy { it.canonicalName.ifBlank { it.name } }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }

    /** How many items are user-verified vs. still estimated, across all entries. */
    data class VerificationBreakdown(val verified: Int, val estimated: Int) {
        val total: Int get() = verified + estimated
    }

    fun verificationBreakdown(entries: List<FoodLogEntry>): VerificationBreakdown {
        val items = allItems(entries)
        val verified = items.count { it.nutrition.caloriesKcal.verified }
        return VerificationBreakdown(verified = verified, estimated = items.size - verified)
    }

    /** Fraction (0..1) of items that have a known calorie value — a data-completeness indicator. */
    fun calorieCompleteness(entries: List<FoodLogEntry>): Double {
        val items = allItems(entries)
        if (items.isEmpty()) return 0.0
        val known = items.count { it.nutrition.caloriesKcal.isKnown }
        return known.toDouble() / items.size
    }
}
