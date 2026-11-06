package ai.anthracite.fasttrack.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Goals & activity levels drive the guidance engine. */
enum class Sex { MALE, FEMALE, OTHER }
enum class ActivityLevel { SEDENTARY, LIGHT, MODERATE, ACTIVE, VERY_ACTIVE }
enum class WeightGoal { LOSE, MAINTAIN, GAIN }
enum class MealType { BREAKFAST, LUNCH, DINNER, SNACK }
enum class EventType { WORKOUT, FAST, MEAL, OTHER }

/** Where an activity record originated — used for de-duplication. */
enum class DataSource { MANUAL, GARMIN, HEALTH_CONNECT }

/**
 * Buckets a saved journal entry falls into. The on-device LLM tags each entry with one or more of
 * these when it summarizes a journal conversation.
 */
enum class JournalCategory(val id: String, val label: String) {
    MENTAL_HEALTH("MENTAL_HEALTH", "Mental health"),
    FASTING("FASTING", "Fasting"),
    EXERCISE("EXERCISE", "Exercise"),
    NUTRITION("NUTRITION", "Nutrition"),
    SLEEP("SLEEP", "Sleep"),
    MOTIVATION("MOTIVATION", "Motivation"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromId(id: String?): JournalCategory =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: OTHER

        /** Parse the comma-separated token list stored on [JournalEntry.categories]. */
        fun parse(raw: String?): List<JournalCategory> =
            raw?.split(",")
                ?.mapNotNull { t -> t.trim().takeIf { it.isNotEmpty() }?.let { fromId(it) } }
                ?.distinct()
                ?: emptyList()

        fun join(values: List<JournalCategory>): String =
            values.distinct().joinToString(",") { it.id }
    }
}

/**
 * Dietary pattern the user follows. Drives macro splits in the guidance engine and (later)
 * filtering in the food log / meal estimator.
 */
enum class DietType(val id: String, val label: String) {
    BALANCED("BALANCED", "Balanced"),
    KETO("KETO", "Keto"),
    LOW_CARB("LOW_CARB", "Low-carb"),
    HIGH_PROTEIN("HIGH_PROTEIN", "High-protein"),
    MEDITERRANEAN("MEDITERRANEAN", "Mediterranean"),
    PALEO("PALEO", "Paleo"),
    VEGETARIAN("VEGETARIAN", "Vegetarian"),
    VEGAN("VEGAN", "Vegan"),
    PESCATARIAN("PESCATARIAN", "Pescatarian");

    companion object {
        fun fromId(id: String?): DietType = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/** Common allergen / preference restrictions, stored on the profile as a comma-separated set. */
object DietaryRestrictions {
    val OPTIONS = listOf(
        "Gluten-free", "Dairy-free", "Nut-free", "Soy-free",
        "Egg-free", "Shellfish-free", "Pork-free", "Halal", "Kosher"
    )

    fun parse(raw: String?): Set<String> =
        raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun join(values: Set<String>): String = values.joinToString(",")
}

/**
 * Single-row profile (id is always 1). Canonical units are metric.
 */
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val displayName: String = "Athlete",
    val sex: String = Sex.OTHER.name,
    val birthYear: Int = 1995,
    val heightCm: Double = 175.0,
    val weightKg: Double = 80.0,
    val goalWeightKg: Double = 72.0,
    val activityLevel: String = ActivityLevel.MODERATE.name,
    val goal: String = WeightGoal.LOSE.name,
    val fastingProtocol: String = "SIXTEEN_EIGHT",
    val unitSystem: String = "IMPERIAL",
    val dietType: String = DietType.BALANCED.name,
    /** Comma-separated restriction tokens, e.g. "Gluten-free,Dairy-free". */
    val dietaryRestrictions: String = ""
)

@Entity(tableName = "fasting_session")
data class FastingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val goalHours: Double,
    val protocol: String,
    val completed: Boolean = false
)

/** In-progress (or just-finished) live workout started from Movement. */
@Entity(tableName = "workout_session")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val type: String,
    val completed: Boolean = false,
    /** Linked schedule row so the workout shows up on the calendar. */
    val scheduleEventId: Long? = null
)

@Entity(tableName = "water_log", indices = [Index("dayKey")])
data class WaterLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val dayKey: String,
    val amountMl: Double
)

@Entity(tableName = "food_log", indices = [Index("dayKey"), Index("entryId")])
data class FoodLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val dayKey: String,
    val name: String,
    val mealType: String = MealType.SNACK.name,
    val calories: Int,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    /**
     * Links this flat row back to the rich [FoodLogEntryRow] it was mirrored from (null for plain
     * manual single-food entries). Lets a meal's mirrored rows be replaced/removed atomically when
     * the rich entry is edited or deleted, while existing aggregation keeps summing food_log rows.
     */
    val entryId: String? = null
)

/**
 * The rich, structured food log: a full [ai.anthracite.fasttrack.nutrition.model.FoodLogEntry]
 * persisted as JSON ([json]) with denormalized columns for fast day/range queries and totals.
 * The per-item rows in [FoodLog] are kept in sync (mirrored) so dashboards/sync are unaffected.
 */
@Entity(tableName = "food_log_entry", indices = [Index("dayKey"), Index("loggedAt")])
data class FoodLogEntryRow(
    @PrimaryKey val id: String,
    val dayKey: String,
    val loggedAt: String,
    val mealType: String,
    val mealName: String? = null,
    /** Rounded calorie estimate/low/high for the whole meal (presentation + quick queries). */
    val calories: Int,
    val caloriesLow: Int? = null,
    val caloriesHigh: Int? = null,
    val itemCount: Int,
    /** How many items had a known calorie value — lets the UI flag incomplete totals. */
    val knownItems: Int,
    val schemaVersion: String,
    /** The canonical serialized [FoodLogEntry]; source of truth for editing/export. */
    val json: String,
    val createdAt: Long,
    val updatedAt: Long
)

/** A body-weight weigh-in. Canonical storage is kilograms; converted for display/input. */
@Entity(tableName = "weight_log", indices = [Index("dayKey")])
data class WeightLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val dayKey: String,
    val weightKg: Double
)

@Entity(
    tableName = "exercise_log",
    indices = [Index("dayKey"), Index(value = ["source", "externalId"], unique = true)]
)
data class ExerciseLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochMs: Long,
    val dayKey: String,
    val type: String,
    val durationMin: Int,
    val calories: Int,
    val distanceMeters: Double? = null,
    val avgHeartRate: Int? = null,
    val source: String = DataSource.MANUAL.name,
    /** Stable id from the source system (Garmin/Health Connect) for de-dup; null for manual. */
    val externalId: String? = null
)

@Entity(tableName = "schedule_event", indices = [Index("startEpochMs")])
data class ScheduleEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val type: String = EventType.WORKOUT.name,
    val notes: String? = null,
    /** Google Calendar event id once synced; null until then. */
    val calendarEventId: String? = null,
    val synced: Boolean = false
)

/**
 * A saved journal entry: the summarized, categorized result of a Chat → Journal conversation.
 * The full back-and-forth is preserved in [transcript] so the user can revisit it.
 */
@Entity(tableName = "journal_entry", indices = [Index("createdAt")])
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val title: String,
    val summary: String,
    /** Comma-separated [JournalCategory] tokens, e.g. "MENTAL_HEALTH,FASTING". */
    val categories: String,
    /** Full conversation, newline-delimited "role: text". */
    val transcript: String,
    /** One-word mood inferred by the model, if any. */
    val mood: String? = null
)

/**
 * Daily wellness snapshot, primarily sourced from a Garmin watch (stress, resting HR, body
 * battery, sleep). One row per calendar day. Powers the stress/recovery trend charts.
 */
@Entity(tableName = "daily_health_metric")
data class DailyHealthMetric(
    @PrimaryKey val dayKey: String,
    val epochMs: Long,
    /** Average all-day stress score, 0–100 (Garmin scale). */
    val stressAvg: Int? = null,
    val restingHeartRate: Int? = null,
    val bodyBattery: Int? = null,
    val sleepMinutes: Int? = null,
    val source: String = DataSource.GARMIN.name
)

/**
 * An archived "daily update" — the dated recap the user saw when they first opened the app on a
 * given day. One row per calendar day so it can be revisited from Profile ▸ Daily update.
 */
@Entity(tableName = "daily_update")
data class DailyUpdate(
    @PrimaryKey val dayKey: String,
    val epochMs: Long,
    /** Pretty date shown in the header, e.g. "Monday October 19th". */
    val dateLabel: String,
    val headline: String,
    val summary: String,
    /** Newline-delimited recommendation titles shown that day. */
    val recommendations: String = "",
    val loginStreak: Int = 0
)

/** One quick mood / energy / hunger check-in per calendar day (each scored 1–5). */
@Entity(tableName = "daily_checkin")
data class DailyCheckIn(
    @PrimaryKey val dayKey: String,
    val epochMs: Long,
    val mood: Int,
    val energy: Int,
    val hunger: Int,
    val note: String? = null
)

/**
 * An archived weekly coach review. [weekKey] is the Monday (yyyy-MM-dd) the week starts on;
 * [statsJson] holds the computed numbers so the screen can re-render without recomputing.
 */
@Entity(tableName = "weekly_review")
data class WeeklyReview(
    @PrimaryKey val weekKey: String,
    val epochMs: Long,
    val headline: String,
    val summary: String,
    val focus: String,
    val statsJson: String,
    /** On-device LLM rewrite of the review, when the user generated one. */
    val aiText: String? = null
)
