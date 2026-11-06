package ai.anthracite.fasttrack.nutrition.estimator

import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import android.os.SystemClock
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A fully offline, zero-download estimator. It tokenizes the description, matches foods against a
 * small built-in nutrition table (per typical serving), applies simple quantity words/numbers and
 * sums the result. Not as smart as an LLM, but always available and deterministic — it also serves
 * as the fallback whenever the LiteRT model isn't downloaded yet.
 */
class HeuristicMealEstimator : MealEstimator {

    override val engineName: String = "Built-in estimator"

    override suspend fun status(): EstimatorStatus =
        EstimatorStatus(EstimatorState.READY, "Offline keyword estimator", engineName)

    /** Per-serving: kcal, protein g, carbs g, fat g. */
    private data class Food(val kcal: Int, val p: Double, val c: Double, val f: Double)

    private val table: Map<String, Food> = mapOf(
        "egg" to Food(78, 6.0, 0.6, 5.0),
        "eggs" to Food(78, 6.0, 0.6, 5.0),
        "toast" to Food(90, 3.0, 15.0, 1.5),
        "bread" to Food(90, 3.0, 15.0, 1.5),
        "bagel" to Food(250, 10.0, 48.0, 1.5),
        "butter" to Food(102, 0.1, 0.0, 11.5),
        "coffee" to Food(2, 0.3, 0.0, 0.0),
        "black coffee" to Food(2, 0.3, 0.0, 0.0),
        "latte" to Food(120, 8.0, 12.0, 4.5),
        "milk" to Food(103, 8.0, 12.0, 2.4),
        "banana" to Food(105, 1.3, 27.0, 0.4),
        "apple" to Food(95, 0.5, 25.0, 0.3),
        "orange" to Food(62, 1.2, 15.0, 0.2),
        "oatmeal" to Food(150, 5.0, 27.0, 3.0),
        "oats" to Food(150, 5.0, 27.0, 3.0),
        "rice" to Food(205, 4.3, 45.0, 0.4),
        "chicken" to Food(165, 31.0, 0.0, 3.6),
        "chicken breast" to Food(165, 31.0, 0.0, 3.6),
        "beef" to Food(250, 26.0, 0.0, 15.0),
        "steak" to Food(271, 25.0, 0.0, 19.0),
        "salmon" to Food(208, 20.0, 0.0, 13.0),
        "tuna" to Food(132, 28.0, 0.0, 1.0),
        "salad" to Food(150, 3.0, 10.0, 10.0),
        "avocado" to Food(240, 3.0, 12.0, 22.0),
        "cheese" to Food(113, 7.0, 1.0, 9.0),
        "yogurt" to Food(100, 10.0, 12.0, 0.5),
        "greek yogurt" to Food(100, 17.0, 6.0, 0.7),
        "pasta" to Food(220, 8.0, 43.0, 1.3),
        "pizza" to Food(285, 12.0, 36.0, 10.0),
        "burger" to Food(354, 20.0, 29.0, 17.0),
        "fries" to Food(312, 3.4, 41.0, 15.0),
        "protein shake" to Food(160, 30.0, 5.0, 2.5),
        "almonds" to Food(164, 6.0, 6.0, 14.0),
        "peanut butter" to Food(188, 8.0, 6.0, 16.0),
        "potato" to Food(163, 4.3, 37.0, 0.2),
        "broccoli" to Food(55, 3.7, 11.0, 0.6),
        "soda" to Food(140, 0.0, 39.0, 0.0),
        "beer" to Food(154, 1.6, 13.0, 0.0),
        "cider" to Food(180, 0.0, 17.0, 0.0),
        "cheesesteak" to Food(850, 40.0, 50.0, 45.0),
        "wine" to Food(125, 0.1, 4.0, 0.0)
    )

    /** Foods we treat as drinks (beverage category + liquid classification). */
    private val liquids = setOf(
        "coffee", "black coffee", "latte", "milk", "soda", "beer", "cider", "wine", "protein shake"
    )
    private val alcoholic = setOf("beer", "cider", "wine")

    private val numberWords = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "half" to 1
    )

    override suspend fun estimate(description: String): MealEstimate? {
        val text = description.lowercase(Locale.US).trim()
        if (text.isEmpty()) return null

        var kcal = 0.0; var p = 0.0; var c = 0.0; var f = 0.0
        var matched = false

        // Check multi-word keys first (e.g. "chicken breast", "black coffee").
        val remaining = StringBuilder(" $text ")
        table.keys.filter { it.contains(' ') }.sortedByDescending { it.length }.forEach { key ->
            val qty = quantityBefore(text, key)
            while (remaining.contains(" $key ")) {
                val idx = remaining.indexOf(" $key ")
                remaining.replace(idx, idx + key.length + 2, " ")
                table[key]!!.let { food ->
                    kcal += food.kcal * qty; p += food.p * qty; c += food.c * qty; f += food.f * qty
                }
                matched = true
            }
        }

        // Single-word tokens.
        remaining.toString().split(Regex("[^a-z]+")).filter { it.isNotBlank() }.forEach { token ->
            table[token]?.let { food ->
                val qty = 1
                kcal += food.kcal * qty; p += food.p * qty; c += food.c * qty; f += food.f * qty
                matched = true
            }
        }

        if (!matched) return null
        return MealEstimate(
            name = description.trim().replaceFirstChar { it.uppercase() },
            calories = kcal.roundToInt(),
            proteinG = round1(p),
            carbsG = round1(c),
            fatG = round1(f),
            confidence = 0.45f,
            engine = engineName
        )
    }

    override suspend fun parseMeal(description: String): ParsedMeal {
        val started = SystemClock.elapsedRealtime()
        val text = description.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ").trim()
        val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val items = ArrayList<FoodItem>()
        var i = 0
        while (i < tokens.size) {
            val qty = numberWords[tokens[i]] ?: tokens[i].toIntOrNull()
            val foodStart = if (qty != null) i + 1 else i
            val quantity = (qty ?: 1).coerceIn(1, 50)
            if (foodStart >= tokens.size) { i++; continue }
            // Prefer a two-word food name (e.g. "black coffee", "chicken breast").
            val twoWord = if (foodStart + 1 < tokens.size) "${tokens[foodStart]} ${tokens[foodStart + 1]}" else null
            val twoKey = twoWord?.takeIf { table.containsKey(it) }
            if (twoKey != null) {
                items += buildItem(twoKey, quantity)
                i = foodStart + 2
                continue
            }
            val oneKey = normalizeFood(tokens[foodStart])
            if (oneKey != null) {
                items += buildItem(oneKey, quantity)
                i = foodStart + 1
                continue
            }
            i++
        }
        val elapsed = SystemClock.elapsedRealtime() - started
        return ParsedMeal(
            ok = items.isNotEmpty(),
            engine = engineName,
            elapsedMs = elapsed,
            items = items,
            error = if (items.isEmpty()) "No foods recognized" else null
        )
    }

    /** Map a token (or its singular form) to a known table key, or null if unrecognized. */
    private fun normalizeFood(token: String): String? {
        if (token.isBlank()) return null
        if (table.containsKey(token)) return token
        if (token.length > 3 && token.endsWith("s")) {
            val singular = token.dropLast(1)
            if (table.containsKey(singular)) return singular
        }
        return null
    }

    private fun buildItem(key: String, quantity: Int): FoodItem {
        val food = table.getValue(key)
        val liquid = key in liquids
        return MealEstimators.item(
            name = key.split(' ').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            canonicalName = key.replace(' ', '_'),
            category = if (liquid) ItemCategory.BEVERAGE else ItemCategory.FOOD,
            quantity = quantity.toDouble(),
            unit = MeasurementUnit.SERVING,
            calories = food.kcal.toDouble(),
            proteinG = food.p.takeIf { it > 0.0 },
            carbsG = food.c.takeIf { it > 0.0 },
            fatG = food.f.takeIf { it > 0.0 },
            isAlcoholic = key in alcoholic,
            isLiquid = liquid,
            confidence = Confidence.LOW,
            source = NutritionSource.FOOD_DATABASE,
            rangePct = 0.2
        )
    }

    private fun quantityBefore(text: String, key: String): Int {
        val idx = text.indexOf(key)
        if (idx <= 0) return 1
        val before = text.substring(0, idx).trim().split(Regex("\\s+")).lastOrNull() ?: return 1
        before.toIntOrNull()?.let { return it.coerceIn(1, 20) }
        return numberWords[before] ?: 1
    }

    private fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
