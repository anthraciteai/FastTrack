package ai.anthracite.fasttrack.nutrition.barcode

import ai.anthracite.fasttrack.nutrition.estimator.MealEstimators
import ai.anthracite.fasttrack.nutrition.model.Confidence
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.ItemCategory
import ai.anthracite.fasttrack.nutrition.model.MeasurementUnit
import ai.anthracite.fasttrack.nutrition.model.NutritionSource
import ai.anthracite.fasttrack.nutrition.model.SizeBasis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Packaged-food lookup by barcode (EAN/UPC) against the free Open Food Facts database.
 * Prefers per-serving label values and falls back to per-100 g when the label has no serving.
 */
object OpenFoodFacts {

    sealed interface Lookup {
        data class Found(val item: FoodItem, val perServing: Boolean) : Lookup
        data object NotFound : Lookup
        data class Failed(val message: String) : Lookup
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private const val FIELDS =
        "product_name,brands,serving_size,serving_quantity,nutriments,categories_tags,quantity"

    suspend fun lookup(barcode: String): Lookup = withContext(Dispatchers.IO) {
        val code = barcode.filter { it.isDigit() }
        if (code.length < 8) return@withContext Lookup.Failed("That doesn't look like a product barcode")
        val req = Request.Builder()
            .url("https://world.openfoodfacts.org/api/v2/product/$code.json?fields=$FIELDS")
            .header("User-Agent", "FastTrack-Android/1.0 (ai.anthracite.fasttrack)")
            .build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@use Lookup.NotFound
                if (!resp.isSuccessful) return@use Lookup.Failed("Lookup failed (${resp.code})")
                parse(resp.body?.string().orEmpty()) ?: Lookup.NotFound
            }
        }.getOrElse { Lookup.Failed(it.message ?: "Network error") }
    }

    /** Map an Open Food Facts product response to a [FoodItem]; null if it has no product. */
    fun parse(body: String): Lookup.Found? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("status", 0) != 1) return null
        val p = root.optJSONObject("product") ?: return null
        val n = p.optJSONObject("nutriments") ?: JSONObject()

        val baseName = p.optString("product_name").trim()
        val brand = p.optString("brands").split(',').firstOrNull()?.trim().orEmpty()
        if (baseName.isBlank() && brand.isBlank()) return null
        val name = when {
            baseName.isBlank() -> brand
            brand.isBlank() || baseName.contains(brand, ignoreCase = true) -> baseName
            else -> "$brand $baseName"
        }

        val servingQty = p.optDouble("serving_quantity", Double.NaN).takeIf { !it.isNaN() && it > 0 }
        val kcalServing = n.num("energy-kcal_serving") ?: n.num("energy_serving")?.let { it / 4.184 }
        val perServing = kcalServing != null
        fun macro(key: String): Double? =
            if (perServing) n.num("${key}_serving") ?: servingQty?.let { q -> n.num("${key}_100g")?.times(q / 100.0) }
            else n.num("${key}_100g")

        val calories = kcalServing ?: n.num("energy-kcal_100g") ?: n.num("energy_100g")?.let { it / 4.184 }
            ?: return null
        val isDrink = p.optJSONArray("categories_tags")?.let { tags ->
            (0 until tags.length()).any { tags.optString(it).contains("beverages") }
        } ?: false
        val portionUnit = if (isDrink) MeasurementUnit.MILLILITER else MeasurementUnit.GRAM
        val portionAmount = if (perServing) servingQty ?: 1.0 else 100.0

        val item = MealEstimators.item(
            name = name,
            category = if (isDrink) ItemCategory.BEVERAGE else ItemCategory.FOOD,
            foodType = "packaged",
            quantity = 1.0,
            unit = MeasurementUnit.SERVING,
            portionAmount = portionAmount,
            portionUnit = if (perServing && servingQty == null) MeasurementUnit.SERVING else portionUnit,
            sizeBasis = SizeBasis.PACKAGE_LABEL,
            calories = calories,
            proteinG = macro("proteins"),
            carbsG = macro("carbohydrates"),
            fatG = macro("fat"),
            alcoholG = macro("alcohol"),
            isAlcoholic = (macro("alcohol") ?: 0.0) > 0.0,
            isLiquid = isDrink,
            confidence = Confidence.HIGH,
            source = if (perServing) NutritionSource.PACKAGE_LABEL else NutritionSource.FOOD_DATABASE,
            rangePct = 0.05
        )
        return Lookup.Found(item, perServing)
    }

    private fun JSONObject.num(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return v.takeIf { !it.isNaN() && it >= 0 }
    }
}
