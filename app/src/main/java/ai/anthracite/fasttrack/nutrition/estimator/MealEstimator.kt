package ai.anthracite.fasttrack.nutrition.estimator

import ai.anthracite.fasttrack.data.local.MealType
import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.DataQuality
import ai.anthracite.fasttrack.nutrition.model.FoodClassification
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionEstimate
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.nutrition.model.NutritionValue
import ai.anthracite.fasttrack.nutrition.model.Portion
import ai.anthracite.fasttrack.nutrition.model.SizeBasis

/** A parsed/estimated meal, ready to pre-fill the add-food form. */
data class MealEstimate(
    val name: String,
    val calories: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val mealType: MealType = MealType.SNACK,
    /** 0f..1f rough confidence; heuristic is lower than an LLM. */
    val confidence: Float = 0.5f,
    val engine: String = "heuristic"
)

enum class EstimatorState { READY, NEEDS_MODEL, UNAVAILABLE }

data class EstimatorStatus(val state: EstimatorState, val detail: String, val engine: String)

/**
 * Turns a free-text meal description ("two eggs, toast and black coffee") into calorie/macro
 * estimates. Implementations are swappable: a zero-dependency heuristic today, an on-device
 * LiteRT / Google AI Edge model tomorrow, or a cloud LLM — the UI never changes.
 */
data class NlpProbeResult(
    val ok: Boolean,
    val engine: String,
    val elapsedMs: Long,
    val estimate: MealEstimate?,
    val rawPreview: String = "",
    val error: String? = null,
    val engineReset: Boolean = false
)

/**
 * A free-text meal parsed into a structured list of [FoodItem]s — the "function-call" result the
 * estimator produces. e.g. "4 ciders, 1 beer, a philly cheesesteak and fries" → four items, each
 * with quantity, unit, per-unit calories (+range), classification and confidence.
 */
data class ParsedMeal(
    val ok: Boolean,
    val engine: String,
    val elapsedMs: Long,
    val items: List<FoodItem>,
    val rawPreview: String = "",
    val error: String? = null
)

interface MealEstimator {
    val engineName: String
    suspend fun status(): EstimatorStatus
    suspend fun estimate(description: String): MealEstimate?

    /**
     * Parse a description into multiple structured items. The default adapts the single-aggregate
     * [estimate] into one item; smarter engines (LLM, heuristic table) override to split items.
     */
    suspend fun parseMeal(description: String): ParsedMeal {
        val started = android.os.SystemClock.elapsedRealtime()
        val est = estimate(description)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        if (est == null) {
            return ParsedMeal(false, engineName, elapsed, emptyList(), error = "No parseable result")
        }
        return ParsedMeal(
            ok = true,
            engine = est.engine,
            elapsedMs = elapsed,
            items = listOf(MealEstimators.singleItem(est))
        )
    }

    suspend fun probe(description: String): NlpProbeResult {
        val started = android.os.SystemClock.elapsedRealtime()
        val estimate = estimate(description)
        return NlpProbeResult(
            ok = estimate != null,
            engine = estimate?.engine ?: engineName,
            elapsedMs = android.os.SystemClock.elapsedRealtime() - started,
            estimate = estimate,
            error = if (estimate == null) "No parseable result" else null
        )
    }
}

/** Shared mapping helpers for turning raw estimates into the rich [FoodItem] model. */
object MealEstimators {

    fun confidenceFrom(score: Float): Confidence = when {
        score >= 0.7f -> Confidence.HIGH
        score >= 0.5f -> Confidence.MEDIUM
        else -> Confidence.LOW
    }

    /** Wrap a known per-unit number as an unverified model estimate with an optional range. */
    fun value(
        estimate: Double?,
        low: Double? = null,
        high: Double? = null,
        source: NutritionSource = NutritionSource.MODEL_ESTIMATE,
        confidence: Confidence = Confidence.MEDIUM
    ): NutritionValue? = if (estimate == null) null else NutritionValue(
        estimate = estimate, low = low, high = high,
        source = source, confidence = confidence, verified = false
    )

    /**
     * Build a [FoodItem] from already-parsed fields. Calories carry a ±[rangePct] band when no
     * explicit low/high is supplied. Macros that are null stay unknown (never coerced to zero).
     */
    fun item(
        name: String,
        canonicalName: String = name.lowercase().trim().replace(Regex("\\s+"), "_"),
        category: ItemCategory = ItemCategory.FOOD,
        foodType: String = "",
        quantity: Double = 1.0,
        unit: MeasurementUnit = MeasurementUnit.SERVING,
        portionAmount: Double = 1.0,
        portionUnit: MeasurementUnit = unit,
        sizeBasis: SizeBasis = SizeBasis.STANDARD_SERVING,
        calories: Double?,
        caloriesLow: Double? = null,
        caloriesHigh: Double? = null,
        proteinG: Double? = null,
        carbsG: Double? = null,
        fatG: Double? = null,
        alcoholG: Double? = null,
        isAlcoholic: Boolean = false,
        isLiquid: Boolean = category == ItemCategory.BEVERAGE,
        confidence: Confidence = Confidence.MEDIUM,
        source: NutritionSource = NutritionSource.MODEL_ESTIMATE,
        rangePct: Double = 0.15
    ): FoodItem {
        val low = caloriesLow ?: calories?.let { it * (1 - rangePct) }
        val high = caloriesHigh ?: calories?.let { it * (1 + rangePct) }
        return FoodItem(
            name = name,
            canonicalName = canonicalName.ifBlank { name.lowercase().trim().replace(Regex("\\s+"), "_") },
            category = category,
            foodType = foodType,
            quantity = quantity,
            unit = unit,
            portion = Portion(portionAmount, portionUnit, sizeBasis, assumed = true),
            nutrition = NutritionEstimate(
                caloriesKcal = value(calories, low, high, source, confidence) ?: NutritionValue.UNKNOWN,
                proteinGrams = value(proteinG, source = source, confidence = confidence),
                carbohydratesGrams = value(carbsG, source = source, confidence = confidence),
                fatGrams = value(fatG, source = source, confidence = confidence),
                alcoholGrams = value(alcoholG, source = source, confidence = confidence)
            ),
            classification = FoodClassification(isAlcoholic = isAlcoholic, isLiquid = isLiquid),
            dataQuality = DataQuality(source = source, confidence = confidence, verified = false)
        )
    }

    /** Fallback: collapse a single-aggregate [MealEstimate] into one [FoodItem]. */
    fun singleItem(est: MealEstimate): FoodItem = item(
        name = est.name,
        calories = est.calories.toDouble(),
        proteinG = est.proteinG.takeIf { it > 0 },
        carbsG = est.carbsG.takeIf { it > 0 },
        fatG = est.fatG.takeIf { it > 0 },
        confidence = confidenceFrom(est.confidence),
        source = NutritionSource.MODEL_ESTIMATE
    )
}
