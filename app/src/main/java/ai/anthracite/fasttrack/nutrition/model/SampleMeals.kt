package ai.anthracite.fasttrack.nutrition.model

/**
 * Development/test fixtures. These are **not** inserted into a real user's history automatically;
 * they exist so tests, previews, and manual QA have a known-good meal to work with.
 */
object SampleMeals {

    private fun beverage(
        name: String,
        canonical: String,
        quantity: Double,
        calEstimate: Double,
        calLow: Double,
        calHigh: Double,
        alcoholG: Double?
    ): FoodItem = FoodItem(
        name = name,
        canonicalName = canonical,
        category = ItemCategory.BEVERAGE,
        foodType = canonical,
        quantity = quantity,
        unit = MeasurementUnit.SERVING,
        portion = Portion(12.0, MeasurementUnit.FLUID_OUNCE, SizeBasis.STANDARD_SERVING, assumed = true),
        nutrition = NutritionEstimate(
            caloriesKcal = NutritionValue(calEstimate, calLow, calHigh, NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false),
            alcoholGrams = alcoholG?.let { NutritionValue(it, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW) }
        ),
        classification = FoodClassification(isAlcoholic = true, isLiquid = true, foodGroups = listOf("alcohol")),
        dataQuality = DataQuality(NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false)
    )

    /**
     * The spec's reference meal: four hard ciders, one beer, a Philly cheesesteak and a serving of
     * fries. Starting estimates (unverified), total ≈ 2,170 kcal. Portions/brands are editable.
     */
    fun drinksAndCheesesteak(): FoodLogEntry = FoodLogEntry(
        entryType = EntryType.MEAL,
        mealName = "Drinks & a cheesesteak",
        mealType = MealSlot.DINNER,
        items = listOf(
            beverage("Hard cider", "hard_cider", quantity = 4.0, calEstimate = 180.0, calLow = 160.0, calHigh = 200.0, alcoholG = 18.0),
            beverage("Beer", "beer", quantity = 1.0, calEstimate = 150.0, calLow = 130.0, calHigh = 170.0, alcoholG = 14.0),
            FoodItem(
                name = "Philly cheesesteak",
                canonicalName = "philly_cheesesteak",
                category = ItemCategory.FOOD,
                foodType = "sandwich",
                quantity = 1.0,
                unit = MeasurementUnit.ITEM,
                portion = Portion(1.0, MeasurementUnit.ITEM, SizeBasis.RESTAURANT_PORTION, assumed = true),
                nutrition = NutritionEstimate(
                    caloriesKcal = NutritionValue(850.0, 750.0, 1000.0, NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false),
                    proteinGrams = NutritionValue(40.0, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW),
                    carbohydratesGrams = NutritionValue(50.0, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW),
                    fatGrams = NutritionValue(45.0, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW)
                ),
                classification = FoodClassification(isAlcoholic = false, isLiquid = false, foodGroups = listOf("protein", "grain")),
                dataQuality = DataQuality(NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false)
            ),
            FoodItem(
                name = "French fries",
                canonicalName = "french_fries",
                category = ItemCategory.FOOD,
                foodType = "fried_potato",
                quantity = 1.0,
                unit = MeasurementUnit.SERVING,
                portion = Portion(1.0, MeasurementUnit.SERVING, SizeBasis.RESTAURANT_PORTION, assumed = true),
                nutrition = NutritionEstimate(
                    caloriesKcal = NutritionValue(450.0, 380.0, 520.0, NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false),
                    carbohydratesGrams = NutritionValue(58.0, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW),
                    fatGrams = NutritionValue(22.0, source = NutritionSource.MODEL_ESTIMATE, confidence = Confidence.LOW)
                ),
                classification = FoodClassification(isAlcoholic = false, isLiquid = false, foodGroups = listOf("vegetable")),
                dataQuality = DataQuality(NutritionSource.MODEL_ESTIMATE, Confidence.LOW, verified = false)
            )
        ),
        tags = listOf("sample", "dinner")
    )
}
