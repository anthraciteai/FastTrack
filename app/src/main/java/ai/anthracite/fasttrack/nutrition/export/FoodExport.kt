package ai.anthracite.fasttrack.nutrition.export

import ai.anthracite.fasttrack.nutrition.FoodAnalytics
import ai.anthracite.fasttrack.nutrition.NutritionCalculator
import ai.anthracite.fasttrack.nutrition.model.FOOD_LOG_SCHEMA_VERSION
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.Iso8601
import ai.anthracite.fasttrack.util.DayKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The time windows a user can export their food log over. Each resolves to an inclusive
 * `fromDayKey..toDayKey` range (or the whole history for [ALL_TIME]).
 */
enum class ExportRange(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_30_DAYS("Last 30 days"),
    ALL_TIME("All time");

    data class Resolved(
        val label: String,
        /** Inclusive start day key, or null for an unbounded (all-time) export. */
        val fromKey: String?,
        /** Inclusive end day key, or null for an unbounded (all-time) export. */
        val toKey: String?
    ) {
        val isAllTime: Boolean get() = fromKey == null
    }

    fun resolve(nowMs: Long = System.currentTimeMillis()): Resolved {
        val today = DayKeys.of(nowMs)
        fun cal() = Calendar.getInstance().apply { timeInMillis = nowMs }
        return when (this) {
            TODAY -> Resolved(label, today, today)
            THIS_WEEK -> {
                val c = cal().apply { set(Calendar.DAY_OF_WEEK, firstDayOfWeek) }
                Resolved(label, DayKeys.of(c.timeInMillis), today)
            }
            THIS_MONTH -> {
                val c = cal().apply { set(Calendar.DAY_OF_MONTH, 1) }
                Resolved(label, DayKeys.of(c.timeInMillis), today)
            }
            LAST_30_DAYS -> {
                val c = cal().apply { add(Calendar.DAY_OF_YEAR, -29) }
                Resolved(label, DayKeys.of(c.timeInMillis), today)
            }
            ALL_TIME -> Resolved(label, null, null)
        }
    }

    companion object {
        val labels: List<String> get() = entries.map { it.label }
    }
}

/**
 * Builds a self-describing JSON export of the food log. The envelope carries:
 *  - `export`  — provenance metadata (app, schema version, range, timezone, counts),
 *  - `summary` — precomputed analytics ([FoodAnalytics]) so a consumer gets totals for free,
 *  - `entries` — the full, loss-less [FoodLogEntry] records for richer re-analysis later.
 *
 * Unknown nutrition values are emitted as JSON `null` (never coerced to 0), matching the model's
 * unknown-vs-zero convention, so a downstream model can tell "no data" from "known zero".
 */
object FoodExport {

    fun build(entries: List<FoodLogEntry>, range: ExportRange.Resolved, generatedAtMs: Long): String {
        val root = JSONObject()
        root.put("export", metadata(entries, range, generatedAtMs))
        root.put("summary", summary(entries))
        val arr = JSONArray()
        entries.forEach { entry -> runCatching { arr.put(JSONObject(entry.toJsonString())) } }
        root.put("entries", arr)
        return root.toString(2)
    }

    private fun metadata(
        entries: List<FoodLogEntry>,
        range: ExportRange.Resolved,
        generatedAtMs: Long
    ): JSONObject = JSONObject().apply {
        put("app", "FastTrack")
        put("type", "food_log")
        put("schemaVersion", FOOD_LOG_SCHEMA_VERSION)
        put("generatedAt", Iso8601.format(generatedAtMs))
        put("timezone", TimeZone.getDefault().id)
        put("rangeLabel", range.label)
        put("fromDayKey", range.fromKey ?: JSONObject.NULL)
        put("toDayKey", range.toKey ?: JSONObject.NULL)
        put("entryCount", entries.size)
        put("itemCount", entries.sumOf { it.items.size })
    }

    private fun summary(entries: List<FoodLogEntry>): JSONObject {
        val items = entries.flatMap { it.items }
        val calories = NutritionCalculator.total(items, NutritionCalculator.CALORIES)
        val macros = NutritionCalculator.macroTotals(items)
        val verification = FoodAnalytics.verificationBreakdown(entries)

        return JSONObject().apply {
            put("calories", ranged(calories))
            put("macrosGrams", JSONObject().apply {
                put("protein", nullableNum(macros.protein.estimate))
                put("carbohydrates", nullableNum(macros.carbs.estimate))
                put("fat", nullableNum(macros.fat.estimate))
                put("fiber", nullableNum(macros.fiber.estimate))
                put("sugar", nullableNum(macros.sugar.estimate))
                put("alcohol", nullableNum(macros.alcohol.estimate))
            })
            put("alcoholicServings", FoodAnalytics.alcoholicServings(entries))
            put("averageCaloriesPerDay", nullableNum(FoodAnalytics.averageDailyCalories(entries)))
            put("averageCaloriesPerMeal", nullableNum(FoodAnalytics.averageCaloriesPerMeal(entries)))
            put("calorieCompleteness", round2(FoodAnalytics.calorieCompleteness(entries)))
            put("verification", JSONObject()
                .put("verifiedItems", verification.verified)
                .put("estimatedItems", verification.estimated))
            put("caloriesByDay", mapToJson(FoodAnalytics.dailyCalorieTotals(entries)))
            put("caloriesByCategory", JSONObject().apply {
                FoodAnalytics.caloriesByCategory(entries).forEach { (cat, kcal) -> put(cat.id, kcal) }
            })
            put("caloriesByFoodType", JSONObject().apply {
                FoodAnalytics.caloriesByFoodType(entries).forEach { (type, kcal) ->
                    put(type.ifBlank { "unspecified" }, kcal)
                }
            })
            put("mostFrequentFoods", JSONArray().apply {
                FoodAnalytics.mostFrequentFoods(entries).forEach { (name, count) ->
                    put(JSONObject().put("name", name).put("count", count))
                }
            })
        }
    }

    private fun ranged(total: NutritionCalculator.RangedTotal): JSONObject = JSONObject().apply {
        put("estimate", nullableNum(total.estimate))
        put("low", nullableNum(total.low))
        put("high", nullableNum(total.high))
        put("knownItems", total.knownItems)
        put("totalItems", total.totalItems)
    }

    private fun mapToJson(map: Map<String, Double>): JSONObject = JSONObject().apply {
        map.forEach { (k, v) -> put(k, v) }
    }

    private fun nullableNum(v: Double?): Any = v ?: JSONObject.NULL

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    /** Suggested file name like `fasttrack-food-this-month-20261023.json`. */
    fun fileName(range: ExportRange.Resolved, generatedAtMs: Long): String {
        val slug = range.label.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-')
        val stamp = DayKeys.of(generatedAtMs).replace("-", "")
        return "fasttrack-food-$slug-$stamp.json"
    }
}
