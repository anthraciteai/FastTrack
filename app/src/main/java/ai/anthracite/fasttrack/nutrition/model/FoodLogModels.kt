package ai.anthracite.fasttrack.nutrition.model

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Strongly-typed, offline-first food-logging domain model.
 *
 * ### Serving-basis convention (read this before touching nutrition math)
 * A [FoodItem] carries **per-single-unit** nutrition in [FoodItem.nutrition]. "One unit" is one of
 * whatever [FoodItem.unit]/[Portion] describes (e.g. one 12 fl oz serving of cider). The number of
 * units actually consumed is [FoodItem.quantity]. Therefore:
 *
 * ```
 * itemTotalCalories = nutrition.caloriesKcal × quantity
 * ```
 *
 * [Portion] only *describes the size of one unit* (12 fl oz, 1 sandwich, 150 g). It must never be
 * multiplied into nutrition again — doing so would double-count. Keep [FoodItem.quantity] and
 * [Portion.amount] conceptually separate: quantity = "how many", portion = "how big is each one".
 *
 * ### Unknown vs. zero
 * A missing numeric nutrition value is represented as `null` and means "unknown". `0.0` means
 * "known to be zero" (e.g. the alcohol in water). Aggregation never silently treats unknown as 0.
 */
const val FOOD_LOG_SCHEMA_VERSION = "1.0"

// ---------------------------------------------------------------------------------------------
// Enums — stored by stable string [id]; display labels live separately.
// ---------------------------------------------------------------------------------------------

enum class EntryType(val id: String) {
    MEAL("meal"), SNACK("snack"), INDIVIDUAL_ITEM("individual_item");

    companion object {
        fun fromId(id: String?): EntryType = entries.firstOrNull { it.id == id } ?: MEAL
    }
}

/** Which part of the day a log belongs to. Separate from [EntryType]. */
enum class MealSlot(val id: String) {
    BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner"), SNACK("snack"), UNSPECIFIED("unspecified");

    companion object {
        fun fromId(id: String?): MealSlot = entries.firstOrNull { it.id == id } ?: UNSPECIFIED
    }
}

/** Broad kind of a consumed item. */
enum class ItemCategory(val id: String) {
    FOOD("food"), BEVERAGE("beverage"), SUPPLEMENT("supplement");

    companion object {
        fun fromId(id: String?): ItemCategory = entries.firstOrNull { it.id == id } ?: FOOD
    }
}

/** The kind of quantity a [MeasurementUnit] expresses, which governs what can convert to what. */
enum class UnitKind { COUNT, MASS, VOLUME }

/**
 * Supported units of measure. [base] is the amount of the canonical base unit (grams for MASS,
 * milliliters for VOLUME) in one of this unit; COUNT units have no base and never convert.
 */
enum class MeasurementUnit(val id: String, val kind: UnitKind, val base: Double) {
    SERVING("serving", UnitKind.COUNT, 0.0),
    ITEM("item", UnitKind.COUNT, 0.0),
    GRAM("gram", UnitKind.MASS, 1.0),
    KILOGRAM("kilogram", UnitKind.MASS, 1000.0),
    OUNCE("ounce", UnitKind.MASS, 28.349523125),
    POUND("pound", UnitKind.MASS, 453.59237),
    MILLILITER("milliliter", UnitKind.VOLUME, 1.0),
    LITER("liter", UnitKind.VOLUME, 1000.0),
    FLUID_OUNCE("fluid_ounce", UnitKind.VOLUME, 29.5735295625),
    CUP("cup", UnitKind.VOLUME, 236.5882365),
    OTHER("other", UnitKind.COUNT, 0.0);

    companion object {
        fun fromId(id: String?): MeasurementUnit = entries.firstOrNull { it.id == id } ?: SERVING
    }
}

/** How a [Portion] size was arrived at. */
enum class SizeBasis(val id: String) {
    STANDARD_SERVING("standard_serving"),
    MEASURED_WEIGHT("measured_weight"),
    MEASURED_VOLUME("measured_volume"),
    RESTAURANT_PORTION("restaurant_portion"),
    PACKAGE_LABEL("package_label"),
    CUSTOM("custom");

    companion object {
        fun fromId(id: String?): SizeBasis = entries.firstOrNull { it.id == id } ?: CUSTOM
    }
}

/** Where a nutrition number came from. Ordered weakest→strongest for provenance comparisons. */
enum class NutritionSource(val id: String) {
    MODEL_ESTIMATE("model_estimate"),
    USER_INPUT("user_input"),
    FOOD_DATABASE("food_database"),
    PACKAGE_LABEL("package_label");

    companion object {
        fun fromId(id: String?): NutritionSource = entries.firstOrNull { it.id == id } ?: MODEL_ESTIMATE
    }
}

enum class Confidence(val id: String) {
    LOW("low"), MEDIUM("medium"), HIGH("high");

    companion object {
        fun fromId(id: String?): Confidence = entries.firstOrNull { it.id == id } ?: LOW
    }
}

// ---------------------------------------------------------------------------------------------
// Value objects
// ---------------------------------------------------------------------------------------------

/**
 * A single nutrition number with an optional [low]/[high] range and provenance. All numbers are
 * **per one unit** of the owning [FoodItem]. `null` estimate = unknown (never coerced to zero).
 */
data class NutritionValue(
    val estimate: Double? = null,
    val low: Double? = null,
    val high: Double? = null,
    val source: NutritionSource = NutritionSource.MODEL_ESTIMATE,
    val confidence: Confidence = Confidence.LOW,
    val verified: Boolean = false
) {
    val isKnown: Boolean get() = estimate != null

    /** Returns a human-readable validation error, or null when valid. */
    fun validationError(): String? {
        listOfNotNull(estimate, low, high).forEach {
            if (it < 0) return "nutrition values must be non-negative (got $it)"
        }
        if (low != null && high != null && low > high) return "low ($low) > high ($high)"
        if (estimate != null) {
            if (low != null && low > estimate) return "low ($low) > estimate ($estimate)"
            if (high != null && high < estimate) return "high ($high) < estimate ($estimate)"
        }
        return null
    }

    val isValid: Boolean get() = validationError() == null

    fun toJson(): JSONObject = JSONObject().apply {
        putNullable("estimate", estimate)
        putNullable("low", low)
        putNullable("high", high)
        put("source", source.id)
        put("confidence", confidence.id)
        put("verified", verified)
    }

    companion object {
        val UNKNOWN = NutritionValue()

        fun fromJson(o: JSONObject?): NutritionValue {
            if (o == null) return UNKNOWN
            return NutritionValue(
                estimate = o.optDoubleOrNull("estimate"),
                low = o.optDoubleOrNull("low"),
                high = o.optDoubleOrNull("high"),
                source = NutritionSource.fromId(o.optString("source")),
                confidence = Confidence.fromId(o.optString("confidence")),
                verified = o.optBoolean("verified", false)
            )
        }
    }
}

/** The full per-unit nutrition profile of a [FoodItem]. Unknown fields are null. */
data class NutritionEstimate(
    val caloriesKcal: NutritionValue = NutritionValue.UNKNOWN,
    val proteinGrams: NutritionValue? = null,
    val carbohydratesGrams: NutritionValue? = null,
    val fatGrams: NutritionValue? = null,
    val fiberGrams: NutritionValue? = null,
    val sugarGrams: NutritionValue? = null,
    val alcoholGrams: NutritionValue? = null
) {
    fun validationError(): String? =
        caloriesKcal.validationError()?.let { "calories: $it" }
            ?: firstMacroError("protein", proteinGrams)
            ?: firstMacroError("carbs", carbohydratesGrams)
            ?: firstMacroError("fat", fatGrams)
            ?: firstMacroError("fiber", fiberGrams)
            ?: firstMacroError("sugar", sugarGrams)
            ?: firstMacroError("alcohol", alcoholGrams)

    private fun firstMacroError(label: String, v: NutritionValue?): String? =
        v?.validationError()?.let { "$label: $it" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("calories_kcal", caloriesKcal.toJson())
        putOpt("protein_grams", proteinGrams?.toJson())
        putOpt("carbohydrates_grams", carbohydratesGrams?.toJson())
        putOpt("fat_grams", fatGrams?.toJson())
        putOpt("fiber_grams", fiberGrams?.toJson())
        putOpt("sugar_grams", sugarGrams?.toJson())
        putOpt("alcohol_grams", alcoholGrams?.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): NutritionEstimate = NutritionEstimate(
            caloriesKcal = NutritionValue.fromJson(o.optJSONObject("calories_kcal")),
            proteinGrams = o.optJSONObject("protein_grams")?.let { NutritionValue.fromJson(it) },
            carbohydratesGrams = o.optJSONObject("carbohydrates_grams")?.let { NutritionValue.fromJson(it) },
            fatGrams = o.optJSONObject("fat_grams")?.let { NutritionValue.fromJson(it) },
            fiberGrams = o.optJSONObject("fiber_grams")?.let { NutritionValue.fromJson(it) },
            sugarGrams = o.optJSONObject("sugar_grams")?.let { NutritionValue.fromJson(it) },
            alcoholGrams = o.optJSONObject("alcohol_grams")?.let { NutritionValue.fromJson(it) }
        )
    }
}

/** The size of a *single* unit of the item (not the total consumed — see class doc on the model). */
data class Portion(
    val amount: Double,
    val unit: MeasurementUnit,
    val sizeBasis: SizeBasis = SizeBasis.CUSTOM,
    val assumed: Boolean = true
) {
    fun validationError(): String? =
        if (amount <= 0) "portion amount must be positive (got $amount)" else null

    fun toJson(): JSONObject = JSONObject().apply {
        put("amount", amount)
        put("unit", unit.id)
        put("size_basis", sizeBasis.id)
        put("assumed", assumed)
    }

    companion object {
        fun fromJson(o: JSONObject): Portion = Portion(
            amount = o.optDouble("amount", 1.0),
            unit = MeasurementUnit.fromId(o.optString("unit")),
            sizeBasis = SizeBasis.fromId(o.optString("size_basis")),
            assumed = o.optBoolean("assumed", true)
        )
    }
}

data class FoodClassification(
    val isAlcoholic: Boolean = false,
    val isLiquid: Boolean = false,
    val foodGroups: List<String> = emptyList(),
    val dietaryTags: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("is_alcoholic", isAlcoholic)
        put("is_liquid", isLiquid)
        put("food_groups", JSONArray(foodGroups))
        put("dietary_tags", JSONArray(dietaryTags))
    }

    companion object {
        fun fromJson(o: JSONObject): FoodClassification = FoodClassification(
            isAlcoholic = o.optBoolean("is_alcoholic", false),
            isLiquid = o.optBoolean("is_liquid", false),
            foodGroups = o.optJSONArray("food_groups").toStringList(),
            dietaryTags = o.optJSONArray("dietary_tags").toStringList()
        )
    }
}

/** Overall quality for the item as a whole; per-value provenance lives on each [NutritionValue]. */
data class DataQuality(
    val source: NutritionSource = NutritionSource.MODEL_ESTIMATE,
    val confidence: Confidence = Confidence.LOW,
    val verified: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source.id)
        put("confidence", confidence.id)
        put("verified", verified)
    }

    companion object {
        fun fromJson(o: JSONObject?): DataQuality {
            if (o == null) return DataQuality()
            return DataQuality(
                source = NutritionSource.fromId(o.optString("source")),
                confidence = Confidence.fromId(o.optString("confidence")),
                verified = o.optBoolean("verified", false)
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Aggregates
// ---------------------------------------------------------------------------------------------

data class FoodItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val canonicalName: String,
    val category: ItemCategory = ItemCategory.FOOD,
    val subcategory: String = "",
    val foodType: String = "",
    val quantity: Double = 1.0,
    val unit: MeasurementUnit = MeasurementUnit.SERVING,
    val portion: Portion = Portion(1.0, MeasurementUnit.SERVING, SizeBasis.STANDARD_SERVING, assumed = true),
    val preparation: String? = null,
    val ingredients: List<String>? = null,
    val nutrition: NutritionEstimate = NutritionEstimate(),
    val classification: FoodClassification = FoodClassification(),
    val dataQuality: DataQuality = DataQuality()
) {
    fun validationError(): String? = when {
        quantity <= 0 -> "quantity must be positive (got $quantity)"
        else -> portion.validationError() ?: nutrition.validationError()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("canonical_name", canonicalName)
        put("category", category.id)
        put("subcategory", subcategory)
        put("food_type", foodType)
        put("quantity", quantity)
        put("unit", unit.id)
        put("portion", portion.toJson())
        putOpt("preparation", preparation)
        ingredients?.let { put("ingredients", JSONArray(it)) }
        put("nutrition", nutrition.toJson())
        put("classification", classification.toJson())
        put("data_quality", dataQuality.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): FoodItem = FoodItem(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"),
            canonicalName = o.optString("canonical_name"),
            category = ItemCategory.fromId(o.optString("category")),
            subcategory = o.optString("subcategory"),
            foodType = o.optString("food_type"),
            quantity = o.optDouble("quantity", 1.0),
            unit = MeasurementUnit.fromId(o.optString("unit")),
            portion = o.optJSONObject("portion")?.let { Portion.fromJson(it) }
                ?: Portion(1.0, MeasurementUnit.SERVING),
            preparation = o.optStringOrNull("preparation"),
            ingredients = o.optJSONArray("ingredients")?.toStringList(),
            nutrition = o.optJSONObject("nutrition")?.let { NutritionEstimate.fromJson(it) }
                ?: NutritionEstimate(),
            classification = o.optJSONObject("classification")?.let { FoodClassification.fromJson(it) }
                ?: FoodClassification(),
            dataQuality = DataQuality.fromJson(o.optJSONObject("data_quality"))
        )
    }
}

data class FoodLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val loggedAt: String = Iso8601.now(),
    val entryType: EntryType = EntryType.MEAL,
    val mealName: String? = null,
    val mealType: MealSlot = MealSlot.UNSPECIFIED,
    val items: List<FoodItem> = emptyList(),
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val schemaVersion: String = FOOD_LOG_SCHEMA_VERSION,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    /** First validation error across the entry and its items, or null if everything is valid. */
    fun validationError(): String? {
        items.forEachIndexed { i, item ->
            item.validationError()?.let { return "item[$i] (${item.name}): $it" }
        }
        return null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("logged_at", loggedAt)
        put("entry_type", entryType.id)
        putOpt("meal_name", mealName)
        put("meal_type", mealType.id)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        putOpt("notes", notes)
        put("tags", JSONArray(tags))
        put("schema_version", schemaVersion)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
    }

    /** Pretty-printed JSON suitable for export/sharing. */
    fun toJsonString(indent: Int = 2): String = toJson().toString(indent)

    companion object {
        fun fromJson(o: JSONObject): FoodLogEntry = FoodLogEntry(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            loggedAt = o.optString("logged_at").ifBlank { Iso8601.now() },
            entryType = EntryType.fromId(o.optString("entry_type")),
            mealName = o.optStringOrNull("meal_name"),
            mealType = MealSlot.fromId(o.optString("meal_type")),
            items = o.optJSONArray("items").toObjectList().map { FoodItem.fromJson(it) },
            notes = o.optStringOrNull("notes"),
            tags = o.optJSONArray("tags").toStringList(),
            schemaVersion = o.optString("schema_version").ifBlank { FOOD_LOG_SCHEMA_VERSION },
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            updatedAt = o.optLong("updated_at", System.currentTimeMillis())
        )

        fun fromJsonString(raw: String): FoodLogEntry = fromJson(JSONObject(raw))
    }
}

// ---------------------------------------------------------------------------------------------
// Small helpers (kept local so the model has no Android dependencies and stays unit-testable).
// ---------------------------------------------------------------------------------------------

object Iso8601 {
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    fun now(): String = fmt().format(Date())
    fun format(epochMs: Long): String = fmt().format(Date(epochMs))
    /** Parse back to epoch ms, tolerating a trailing 'Z'. Returns null if unparseable. */
    fun parseOrNull(iso: String): Long? = runCatching {
        fmt().parse(iso)?.time
    }.getOrNull() ?: runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso)?.time
    }.getOrNull()
}

private fun JSONObject.putNullable(key: String, value: Double?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}
