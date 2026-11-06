package ai.anthracite.fasttrack.util

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.util.UUID

/** Lightweight SharedPreferences wrapper for app settings (reminders, etc.). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("fasttrack_prefs", Context.MODE_PRIVATE)

    var waterReminderEnabled: Boolean
        get() = sp.getBoolean(KEY_WATER, true)
        set(v) = sp.edit { putBoolean(KEY_WATER, v) }

    /** Interval in minutes between water nudges. */
    var waterIntervalMin: Int
        get() = sp.getInt(KEY_WATER_INTERVAL, 120)
        set(v) = sp.edit { putInt(KEY_WATER_INTERVAL, v) }

    /**
     * Custom daily hydration goal in fluid ounces. 0 means "auto" — fall back to the
     * half-body-weight default computed in [ai.anthracite.fasttrack.data.health.GuidanceEngine].
     */
    var waterGoalOverrideOz: Int
        get() = sp.getInt(KEY_WATER_GOAL_OZ, 0)
        set(v) = sp.edit { putInt(KEY_WATER_GOAL_OZ, v.coerceIn(0, 300)) }

    var fastingRemindersEnabled: Boolean
        get() = sp.getBoolean(KEY_FASTING, true)
        set(v) = sp.edit { putBoolean(KEY_FASTING, v) }

    /** Persistent "fasting timer" notification with a live countdown while a fast is running. */
    var fastingOngoingEnabled: Boolean
        get() = sp.getBoolean(KEY_FASTING_ONGOING, true)
        set(v) = sp.edit { putBoolean(KEY_FASTING_ONGOING, v) }

    /** Sunday-evening "your weekly review is ready" notification. */
    var weeklyReviewEnabled: Boolean
        get() = sp.getBoolean(KEY_WEEKLY_REVIEW, true)
        set(v) = sp.edit { putBoolean(KEY_WEEKLY_REVIEW, v) }

    /** Week key (Monday yyyy-MM-dd) of the last weekly review the user opened. */
    var lastSeenWeeklyReview: String
        get() = sp.getString(KEY_WEEKLY_SEEN, "") ?: ""
        set(v) = sp.edit { putString(KEY_WEEKLY_SEEN, v) }

    var fastRulesVersion: Int
        get() = sp.getInt(KEY_FAST_RULES_VERSION, 0)
        set(v) = sp.edit { putInt(KEY_FAST_RULES_VERSION, v) }

    /** Start times of fasts deleted (or re-timed) on this device, so sync can't resurrect them. */
    val fastTombstones = object : ai.anthracite.fasttrack.data.repository.FastTombstones {
        override fun all(): Set<Long> =
            sp.getStringSet(KEY_FAST_TOMBSTONES, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

        override fun add(startEpochMs: Long) {
            val next = (all() + startEpochMs).sortedDescending().take(500).map { it.toString() }.toSet()
            sp.edit { putStringSet(KEY_FAST_TOMBSTONES, next) }
        }
    }

    var workoutRemindersEnabled: Boolean
        get() = sp.getBoolean(KEY_WORKOUT, true)
        set(v) = sp.edit { putBoolean(KEY_WORKOUT, v) }

    /**
     * Master switch for on-device AI features (the LLM food-log estimator and its model
     * management). Opt-in: when off (the default), every LLM-facing surface is hidden, the
     * model is not loaded, and meals are entered manually.
     */
    var aiFeaturesEnabled: Boolean
        get() = sp.getBoolean(KEY_AI, false)
        set(v) = sp.edit { putBoolean(KEY_AI, v) }

    /** First launch, used to unlock the week-one goal projection. */
    var firstOpenEpochMs: Long
        get() = sp.getLong(KEY_FIRST_OPEN, 0L)
        set(v) = sp.edit { putLong(KEY_FIRST_OPEN, v) }

    /**
     * The day the user joined FastTrack (derived from [firstOpenEpochMs]). Day navigation never
     * goes earlier than this — there's nothing to show before the app existed. Falls back to today
     * if first-open hasn't been recorded yet.
     */
    fun joinedDayKey(): String =
        if (firstOpenEpochMs > 0L) DayKeys.of(firstOpenEpochMs) else DayKeys.today()

    /** Weight captured on first launch so we can sketch pace toward the goal. */
    var baselineWeightKg: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_BASELINE_KG, 0L))
        set(v) = sp.edit { putLong(KEY_BASELINE_KG, java.lang.Double.doubleToRawLongBits(v)) }

    var googleAccountEmail: String
        get() = sp.getString(KEY_GOOGLE_EMAIL, "") ?: ""
        set(v) = sp.edit { putString(KEY_GOOGLE_EMAIL, v) }

    var googleCalendarConnected: Boolean
        get() = sp.getBoolean(KEY_GOOGLE_CAL, false)
        set(v) = sp.edit { putBoolean(KEY_GOOGLE_CAL, v) }

    var googleSyncConnected: Boolean
        get() = sp.getBoolean(KEY_GOOGLE_SYNC, false)
        set(v) = sp.edit { putBoolean(KEY_GOOGLE_SYNC, v) }

    var googleSyncEmail: String
        get() = sp.getString(KEY_GOOGLE_SYNC_EMAIL, "") ?: ""
        set(v) = sp.edit { putString(KEY_GOOGLE_SYNC_EMAIL, v) }

    var snapshotUpdatedAt: Long
        get() = sp.getLong(KEY_SNAPSHOT_UPDATED, 0L)
        set(v) = sp.edit { putLong(KEY_SNAPSHOT_UPDATED, v) }

    /** Last activity type started from the app or widget. */
    var lastWorkoutType: String
        get() = sp.getString(KEY_LAST_WORKOUT, "Walk") ?: "Walk"
        set(v) = sp.edit { putString(KEY_LAST_WORKOUT, v) }

    /** Consecutive days the user has opened the app. Showing up matters as much as fasting. */
    var loginStreak: Int
        get() = sp.getInt(KEY_LOGIN_STREAK, 0)
        set(v) = sp.edit { putInt(KEY_LOGIN_STREAK, v) }

    var longestLoginStreak: Int
        get() = sp.getInt(KEY_LOGIN_BEST, 0)
        set(v) = sp.edit { putInt(KEY_LOGIN_BEST, v) }

    var lastLoginDayKey: String
        get() = sp.getString(KEY_LAST_LOGIN_DAY, "") ?: ""
        set(v) = sp.edit { putString(KEY_LAST_LOGIN_DAY, v) }

    /** Day key (yyyy-MM-dd) the daily-update popup was last shown, so it appears once per day. */
    var lastDailyUpdateShownDay: String
        get() = sp.getString(KEY_DAILY_UPDATE_SHOWN, "") ?: ""
        set(v) = sp.edit { putString(KEY_DAILY_UPDATE_SHOWN, v) }

    // ---- Enterprise (organization) enrollment ----
    /** Stable per-install id, used as the default member id when joining an organization. */
    val installId: String
        get() = sp.getString(KEY_INSTALL_ID, null) ?: run {
            val id = "ft-" + UUID.randomUUID().toString().take(12)
            sp.edit { putString(KEY_INSTALL_ID, id) }
            id
        }

    /** Base URL of the enterprise API this phone pushes to (from the scanned join QR). */
    var enterpriseApiBase: String
        get() = sp.getString(KEY_ENT_BASE, "") ?: ""
        set(v) = sp.edit { putString(KEY_ENT_BASE, v) }

    /** Organization slug this phone joined. */
    var enterpriseOrg: String
        get() = sp.getString(KEY_ENT_ORG, "") ?: ""
        set(v) = sp.edit { putString(KEY_ENT_ORG, v) }

    /** This phone's member id within the organization. */
    var enterpriseMemberId: String
        get() = sp.getString(KEY_ENT_MEMBER, "") ?: ""
        set(v) = sp.edit { putString(KEY_ENT_MEMBER, v) }

    /** Long-lived device token authorizing pushes to the organization. */
    var enterpriseDeviceToken: String
        get() = sp.getString(KEY_ENT_TOKEN, "") ?: ""
        set(v) = sp.edit { putString(KEY_ENT_TOKEN, v) }

    /** Epoch millis of the last successful push to the org dashboard. */
    var enterpriseLastPushMs: Long
        get() = sp.getLong(KEY_ENT_PUSH, 0L)
        set(v) = sp.edit { putLong(KEY_ENT_PUSH, v) }

    /** Leave the organization — forget the token and enrollment. */
    fun clearEnterprise() {
        enterpriseDeviceToken = ""
        enterpriseOrg = ""
        enterpriseMemberId = ""
        enterpriseLastPushMs = 0L
    }

    /**
     * Whether a daily "time to start your fast" reminder is active. Turned on automatically the
     * first time the user starts a fast, so the app can nudge them at the same time every day.
     */
    var dailyFastReminderEnabled: Boolean
        get() = sp.getBoolean(KEY_DAILY_FAST_ON, false)
        set(v) = sp.edit { putBoolean(KEY_DAILY_FAST_ON, v) }

    /** Minutes past midnight the daily fast begins, e.g. 20:00 → 1200. -1 means not set yet. */
    var dailyFastStartMinOfDay: Int
        get() = sp.getInt(KEY_DAILY_FAST_MIN, -1)
        set(v) = sp.edit { putInt(KEY_DAILY_FAST_MIN, v) }

    /**
     * Daily morning "how did you sleep?" nudge. Opt-in: when on, FastTrack can estimate last
     * night's sleep from phone-usage gaps (if Usage Access is granted) for the user to confirm.
     */
    var sleepReminderEnabled: Boolean
        get() = sp.getBoolean(KEY_SLEEP_ON, false)
        set(v) = sp.edit { putBoolean(KEY_SLEEP_ON, v) }

    /** Minutes past midnight the morning sleep nudge fires. Default 8:00 AM. */
    var sleepReminderMinOfDay: Int
        get() = sp.getInt(KEY_SLEEP_MIN, 8 * 60)
        set(v) = sp.edit { putInt(KEY_SLEEP_MIN, v.coerceIn(0, 1439)) }

    /**
     * Records a login for [todayKey]. Returns true only the first time it's called on a given
     * day, so callers can archive a fresh daily update exactly once per day. Keeps the streak
     * alive when the previous login was [yesterdayKey], otherwise resets it to 1.
     */
    fun recordLogin(todayKey: String, yesterdayKey: String): Boolean {
        if (lastLoginDayKey == todayKey) return false
        loginStreak = if (lastLoginDayKey == yesterdayKey) loginStreak + 1 else 1
        if (loginStreak > longestLoginStreak) longestLoginStreak = loginStreak
        lastLoginDayKey = todayKey
        return true
    }

    fun toJson(): JSONObject = JSONObject()
        .put("waterReminderEnabled", waterReminderEnabled)
        .put("waterIntervalMin", waterIntervalMin)
        .put("waterGoalOverrideOz", waterGoalOverrideOz)
        .put("fastingRemindersEnabled", fastingRemindersEnabled)
        .put("workoutRemindersEnabled", workoutRemindersEnabled)
        .put("aiFeaturesEnabled", aiFeaturesEnabled)
        .put("firstOpenEpochMs", firstOpenEpochMs)
        .put("baselineWeightKg", baselineWeightKg)
        .put("googleAccountEmail", googleAccountEmail)
        .put("googleCalendarConnected", googleCalendarConnected)
        .put("lastWorkoutType", lastWorkoutType)

    fun applyJson(obj: JSONObject) {
        if (obj.has("waterReminderEnabled")) waterReminderEnabled = obj.optBoolean("waterReminderEnabled")
        if (obj.has("waterIntervalMin")) waterIntervalMin = obj.optInt("waterIntervalMin")
        if (obj.has("waterGoalOverrideOz")) waterGoalOverrideOz = obj.optInt("waterGoalOverrideOz")
        if (obj.has("fastingRemindersEnabled")) fastingRemindersEnabled = obj.optBoolean("fastingRemindersEnabled")
        if (obj.has("workoutRemindersEnabled")) workoutRemindersEnabled = obj.optBoolean("workoutRemindersEnabled")
        if (obj.has("aiFeaturesEnabled")) aiFeaturesEnabled = obj.optBoolean("aiFeaturesEnabled")
        if (obj.has("firstOpenEpochMs")) firstOpenEpochMs = obj.optLong("firstOpenEpochMs")
        if (obj.has("baselineWeightKg")) baselineWeightKg = obj.optDouble("baselineWeightKg")
        if (obj.has("googleAccountEmail")) googleAccountEmail = obj.optString("googleAccountEmail")
        if (obj.has("googleCalendarConnected")) googleCalendarConnected = obj.optBoolean("googleCalendarConnected")
        if (obj.has("lastWorkoutType")) lastWorkoutType = obj.optString("lastWorkoutType", "Walk")
    }

    companion object {
        private const val KEY_WATER = "water_reminder"
        private const val KEY_WATER_INTERVAL = "water_interval"
        private const val KEY_WATER_GOAL_OZ = "water_goal_oz"
        private const val KEY_FASTING = "fasting_reminder"
        private const val KEY_WORKOUT = "workout_reminder"
        private const val KEY_FASTING_ONGOING = "fasting_ongoing_notification"
        private const val KEY_WEEKLY_REVIEW = "weekly_review_notification"
        private const val KEY_WEEKLY_SEEN = "weekly_review_seen"
        private const val KEY_FAST_TOMBSTONES = "fast_tombstones"
        private const val KEY_FAST_RULES_VERSION = "fast_rules_version"
        private const val KEY_AI = "ai_features"
        private const val KEY_FIRST_OPEN = "first_open_ms"
        private const val KEY_BASELINE_KG = "baseline_weight_kg"
        private const val KEY_GOOGLE_EMAIL = "google_account_email"
        private const val KEY_GOOGLE_CAL = "google_calendar_connected"
        private const val KEY_GOOGLE_SYNC = "google_sync_connected"
        private const val KEY_GOOGLE_SYNC_EMAIL = "google_sync_email"
        private const val KEY_SNAPSHOT_UPDATED = "snapshot_updated_at"
        private const val KEY_LAST_WORKOUT = "last_workout_type"
        private const val KEY_LOGIN_STREAK = "login_streak"
        private const val KEY_LOGIN_BEST = "login_streak_best"
        private const val KEY_LAST_LOGIN_DAY = "last_login_day"
        private const val KEY_DAILY_UPDATE_SHOWN = "daily_update_shown_day"
        private const val KEY_DAILY_FAST_ON = "daily_fast_reminder_on"
        private const val KEY_DAILY_FAST_MIN = "daily_fast_min_of_day"
        private const val KEY_SLEEP_ON = "sleep_reminder_on"
        private const val KEY_SLEEP_MIN = "sleep_reminder_min_of_day"
        private const val KEY_INSTALL_ID = "install_id"
        private const val KEY_ENT_BASE = "enterprise_api_base"
        private const val KEY_ENT_ORG = "enterprise_org"
        private const val KEY_ENT_MEMBER = "enterprise_member_id"
        private const val KEY_ENT_TOKEN = "enterprise_device_token"
        private const val KEY_ENT_PUSH = "enterprise_last_push_ms"
    }
}
