package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.ActivityLevel
import ai.anthracite.fasttrack.data.local.DietType
import ai.anthracite.fasttrack.data.local.Sex
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Evidence-based (rule-of-thumb) health targets derived from the user's profile.
 *
 * These are general wellness heuristics, not medical advice:
 *  - Hydration: ~35 ml per kg of body weight baseline (≈ 0.5 oz per lb), plus a bump for
 *    activity. Rounded to a sensible daily target.
 *  - Energy: BMR via the Mifflin-St Jeor equation, scaled by an activity factor to TDEE,
 *    then a 500 kcal/day deficit for weight loss (≈ 0.5 kg/week).
 *  - Macros: protein anchored to body weight (1.8 g/kg to preserve lean mass while cutting),
 *    fat at 25% of calories, carbs filling the remainder.
 */
object GuidanceEngine {

    data class Targets(
        val waterMl: Double,
        val calories: Int,
        val proteinG: Int,
        val carbsG: Int,
        val fatG: Int,
        val bmr: Int,
        val tdee: Int,
        val bmi: Double,
        val fastingHours: Double,
        val dietLabel: String,
        val macroNote: String,
        val weightRef: WeightReference = WeightReference()
    )

    /**
     * Height-based weight reference (Devine ideal body weight + BMI 18.5–24.9 healthy band).
     * Context only — never used to clamp or rewrite the user's goal.
     */
    data class WeightReference(
        val idealKg: Double = 0.0,
        val healthyLowKg: Double = 0.0,
        val healthyHighKg: Double = 0.0
    )

    private fun activityFactor(level: ActivityLevel): Double = when (level) {
        ActivityLevel.SEDENTARY -> 1.2
        ActivityLevel.LIGHT -> 1.375
        ActivityLevel.MODERATE -> 1.55
        ActivityLevel.ACTIVE -> 1.725
        ActivityLevel.VERY_ACTIVE -> 1.9
    }

    fun ageFrom(birthYear: Int): Int =
        (Calendar.getInstance().get(Calendar.YEAR) - birthYear).coerceIn(10, 100)

    const val ML_PER_OZ = 29.5735
    private const val LB_PER_KG = 2.2046226

    /**
     * Default daily hydration goal: the classic "half your body weight (lbs) in fluid ounces" rule,
     * returned in millilitres. e.g. 80 kg ≈ 176 lb → ~88 oz ≈ 2,600 ml.
     */
    fun recommendedWaterMl(weightKg: Double): Double =
        weightKg * LB_PER_KG / 2.0 * ML_PER_OZ

    /**
     * Effective hydration goal in ml: a user's custom [overrideOz] when set (> 0), otherwise the
     * half-body-weight default.
     */
    fun waterGoalMl(weightKg: Double, overrideOz: Int): Double =
        if (overrideOz > 0) overrideOz * ML_PER_OZ else recommendedWaterMl(weightKg)

    fun bmi(weightKg: Double, heightCm: Double): Double {
        val m = heightCm / 100.0
        return if (m <= 0) 0.0 else weightKg / (m * m)
    }

    fun bmr(profile: UserProfile): Double {
        val age = ageFrom(profile.birthYear)
        val base = 10 * profile.weightKg + 6.25 * profile.heightCm - 5 * age
        return when (Sex.valueOf(profile.sex)) {
            Sex.MALE -> base + 5
            Sex.FEMALE -> base - 161
            Sex.OTHER -> base - 78 // midpoint
        }
    }

    fun compute(profile: UserProfile): Targets {
        val level = ActivityLevel.valueOf(profile.activityLevel)
        val goal = WeightGoal.valueOf(profile.goal)
        val diet = DietType.fromId(profile.dietType)
        val bmrVal = bmr(profile)
        val tdee = bmrVal * activityFactor(level)
        val calories = when (goal) {
            WeightGoal.LOSE -> tdee - 500
            WeightGoal.MAINTAIN -> tdee
            WeightGoal.GAIN -> tdee + 350
        }.coerceAtLeast(1200.0)

        val macros = macrosFor(diet, calories, profile.weightKg)

        return Targets(
            waterMl = recommendedWaterMl(profile.weightKg),
            calories = calories.roundToInt(),
            proteinG = macros.proteinG,
            carbsG = macros.carbsG,
            fatG = macros.fatG,
            bmr = bmrVal.roundToInt(),
            tdee = tdee.roundToInt(),
            bmi = bmi(profile.weightKg, profile.heightCm),
            fastingHours = FastingProtocol.fromId(profile.fastingProtocol).fastHours,
            dietLabel = diet.label,
            macroNote = macros.note,
            weightRef = weightReference(profile.heightCm, Sex.valueOf(profile.sex))
        )
    }

    /**
     * Devine formula for ideal body weight from height, plus a BMI 18.5–24.9 band.
     * Under 5 ft the extra-inch term is zero (formula isn't defined below that).
     */
    fun weightReference(heightCm: Double, sex: Sex): WeightReference {
        val m = heightCm / 100.0
        if (m <= 0.0) return WeightReference()
        val inchesOver5ft = (Units.cmToIn(heightCm) - 60.0).coerceAtLeast(0.0)
        val ideal = when (sex) {
            Sex.MALE -> 50.0 + 2.3 * inchesOver5ft
            Sex.FEMALE -> 45.5 + 2.3 * inchesOver5ft
            Sex.OTHER -> 47.75 + 2.3 * inchesOver5ft
        }
        return WeightReference(
            idealKg = ideal,
            healthyLowKg = 18.5 * m * m,
            healthyHighKg = 24.9 * m * m
        )
    }

    /** One-line comparison of the user's goal vs the height-based reference. Never a warning. */
    fun goalVsReference(goalKg: Double, ref: WeightReference, unit: UnitSystem): String {
        if (ref.idealKg <= 0.0 || goalKg <= 0.0) return "Set your height and goal to see this reference."
        val deltaKg = goalKg - ref.idealKg
        val deltaLabel = Units.weightLabel(abs(deltaKg), unit)
        return when {
            abs(deltaKg) < 1.5 -> "Your goal is right around this reference."
            deltaKg > 0 -> "Your goal is $deltaLabel above this reference."
            else -> "Your goal is $deltaLabel below this reference."
        }
    }

    private data class Macros(val proteinG: Int, val carbsG: Int, val fatG: Int, val note: String)

    /**
     * Splits calories into macros according to the chosen diet. Protein is anchored to body
     * weight; the carb/fat balance shifts per diet (e.g. keto pushes carbs down and fat up).
     */
    private fun macrosFor(diet: DietType, calories: Double, weightKg: Double): Macros {
        val proteinPerKg = when (diet) {
            DietType.KETO -> 1.6
            DietType.HIGH_PROTEIN -> 2.2
            else -> 1.8
        }
        val proteinG = (weightKg * proteinPerKg).roundToInt()
        val proteinCal = proteinG * 4

        return when (diet) {
            DietType.KETO -> {
                val carbsG = 25 // hard cap to stay in ketosis
                val fatG = ((calories - proteinCal - carbsG * 4) / 9.0).roundToInt().coerceAtLeast(0)
                Macros(proteinG, carbsG, fatG, "~70% fat · very low carb (≤25g)")
            }
            DietType.LOW_CARB -> {
                val carbsG = ((calories * 0.15) / 4.0).roundToInt()
                val fatG = ((calories - proteinCal - carbsG * 4) / 9.0).roundToInt().coerceAtLeast(0)
                Macros(proteinG, carbsG, fatG, "~15% carbs · higher fat")
            }
            DietType.HIGH_PROTEIN -> {
                val fatG = ((calories * 0.25) / 9.0).roundToInt()
                val carbsG = ((calories - proteinCal - fatG * 9) / 4.0).roundToInt().coerceAtLeast(0)
                Macros(proteinG, carbsG, fatG, "2.2 g/kg protein to preserve muscle")
            }
            DietType.MEDITERRANEAN -> {
                val fatG = ((calories * 0.35) / 9.0).roundToInt() // olive oil, fish, nuts
                val carbsG = ((calories - proteinCal - fatG * 9) / 4.0).roundToInt().coerceAtLeast(0)
                Macros(proteinG, carbsG, fatG, "Healthy fats ~35% · whole foods")
            }
            else -> {
                // Balanced / Paleo / Vegetarian / Vegan / Pescatarian: standard split.
                val fatG = ((calories * 0.25) / 9.0).roundToInt()
                val carbsG = ((calories - proteinCal - fatG * 9) / 4.0).roundToInt().coerceAtLeast(0)
                val note = when (diet) {
                    DietType.VEGAN -> "Prioritize plant protein (legumes, tofu, tempeh)"
                    DietType.VEGETARIAN -> "Round out protein with eggs, dairy, legumes"
                    DietType.PESCATARIAN -> "Lean on fish & seafood for protein"
                    DietType.PALEO -> "Whole foods · no grains or legumes"
                    else -> "Balanced 40/30/30-style split"
                }
                Macros(proteinG, carbsG, fatG, note)
            }
        }
    }

    fun bmiCategory(bmi: Double): String = when {
        bmi < 18.5 -> "Underweight"
        bmi < 25 -> "Healthy"
        bmi < 30 -> "Overweight"
        else -> "Obese"
    }
}
