package ai.anthracite.fasttrack

import ai.anthracite.fasttrack.data.SnapshotStore
import ai.anthracite.fasttrack.data.local.FastTrackDatabase
import ai.anthracite.fasttrack.data.repository.FastTrackRepository
import androidx.room.InvalidationTracker
import ai.anthracite.fasttrack.notifications.FastingMilestones
import kotlinx.coroutines.flow.distinctUntilChanged
import ai.anthracite.fasttrack.integration.HealthSyncManager
import ai.anthracite.fasttrack.integration.calendar.GoogleCalendarClient
import ai.anthracite.fasttrack.integration.enterprise.EnterpriseClient
import ai.anthracite.fasttrack.integration.garmin.GarminClient
import ai.anthracite.fasttrack.integration.healthconnect.HealthConnectSource
import ai.anthracite.fasttrack.integration.sync.GoogleIdentity
import ai.anthracite.fasttrack.integration.sync.RetrofitSyncClient
import ai.anthracite.fasttrack.nutrition.estimator.CompositeMealEstimator
import ai.anthracite.fasttrack.nutrition.estimator.HeuristicMealEstimator
import ai.anthracite.fasttrack.nutrition.estimator.LiteRtMealEstimator
import ai.anthracite.fasttrack.nutrition.estimator.LlmModel
import ai.anthracite.fasttrack.nutrition.estimator.MealEstimator
import ai.anthracite.fasttrack.nutrition.estimator.MealModelManager
import ai.anthracite.fasttrack.notifications.NotificationHelper
import ai.anthracite.fasttrack.notifications.ReminderScheduler
import ai.anthracite.fasttrack.util.Prefs
import ai.anthracite.fasttrack.widget.FastTrackWidget
import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * App entry point doubling as a tiny service locator. ViewModels reach dependencies via
 * (application as FastTrackApp). Keeps things dependency-injection-ready without extra libraries.
 */
class FastTrackApp : Application() {

    private val database by lazy { FastTrackDatabase.get(this) }
    val repository by lazy { FastTrackRepository(database, prefs.fastTombstones) }
    val prefs by lazy { Prefs(this) }

    // Integration clients (stubbed plumbing; see integration package).
    val garminClient by lazy { GarminClient() }
    val healthConnectSource by lazy { HealthConnectSource(this) }
    val calendarClient by lazy { GoogleCalendarClient(this) }
    val googleIdentity by lazy { GoogleIdentity(this) }
    private val snapshotStore by lazy { SnapshotStore(this) }
    val syncClient by lazy { RetrofitSyncClient(this) }
    val enterpriseClient by lazy { EnterpriseClient(this) }
    @Volatile var syncing: Boolean = false
    private var syncJob: Job? = null
    private var enterprisePushJob: Job? = null
    val healthSyncManager by lazy {
        HealthSyncManager(garminClient, healthConnectSource, database.exerciseDao())
    }

    // Foreground Garmin auto-refresh: pulls while the app is open on a sensible cadence.
    @Volatile private var lastGarminSyncMs: Long = 0L
    @Volatile var garminSyncing: Boolean = false
        private set
    private var garminLoopJob: Job? = null
    private var startedActivities = 0

    /** Which on-device model the food-log estimator targets. */
    private val mealModel = LlmModel.DEFAULT

    private val heuristicEstimator by lazy { HeuristicMealEstimator() }
    private val liteRtEstimator by lazy { LiteRtMealEstimator(this, mealModel) }

    /**
     * Food-log estimation. The LiteRT model is only used when the user has opted into AI
     * features; otherwise this is the silent heuristic (and the UI hides the estimate controls).
     */
    val onDeviceLlm: LiteRtMealEstimator get() = liteRtEstimator

    val mealEstimator: MealEstimator
        get() = if (prefs.aiFeaturesEnabled) {
            CompositeMealEstimator(liteRtEstimator, heuristicEstimator)
        } else {
            heuristicEstimator
        }

    /** Download/manage the on-device model weights (observed by the UI). */
    val mealModelManager by lazy {
        MealModelManager(this, mealModel, BuildConfig.MEAL_MODEL_URL)
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        ReminderScheduler.syncFromPrefs(this)
        appScope.launch {
            snapshotStore.restoreIfEmpty(repository, prefs)
            if (prefs.firstOpenEpochMs == 0L) prefs.firstOpenEpochMs = System.currentTimeMillis()
            if (prefs.baselineWeightKg == 0.0) {
                prefs.baselineWeightKg = repository.getProfile().weightKg
            }
            recordDailyLogin()
            snapshotStore.write(repository, prefs)
            if (googleIdentity.isSignedIn()) syncClient.syncNow()
            // Keep the org dashboard current on launch.
            runCatching { enterpriseClient.pushIfEnrolled() }
        }
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(
                "user_profile", "fasting_session", "workout_session", "water_log",
                "food_log", "exercise_log", "schedule_event", "daily_health_metric",
                "weight_log", "daily_checkin"
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    appScope.launch { snapshotStore.write(repository, prefs) }
                    // Debounced push to the org dashboard whenever local data changes.
                    if (enterpriseClient.isEnrolled) {
                        enterprisePushJob?.cancel()
                        enterprisePushJob = appScope.launch {
                            delay(2_000)
                            runCatching { enterpriseClient.pushIfEnrolled() }
                        }
                    }
                    if (syncing || !googleIdentity.isSignedIn()) return
                    syncJob?.cancel()
                    syncJob = appScope.launch {
                        delay(2_000)
                        syncClient.syncNow()
                    }
                }
            }
        )
        // Only copy/load the model if the user has opted into on-device AI.
        if (prefs.aiFeaturesEnabled) {
            appScope.launch { mealModelManager.ensureInstalled() }
        }
        FastTrackWidget.refreshAll(this)
        appScope.launch {
            val repaired = repository.repairFastHistory()
            val rulesChanged = prefs.fastRulesVersion < FAST_RULES_VERSION
            if (repaired > 0 || rulesChanged) {
                prefs.fastRulesVersion = FAST_RULES_VERSION
                // Archived reviews were computed from the bad rows; rebuild their numbers.
                repository.allWeeklyReviews().forEach { r ->
                    runCatching {
                        val fresh = ai.anthracite.fasttrack.data.health.WeeklyReviewService
                            .generate(this@FastTrackApp, java.time.LocalDate.parse(r.weekKey))
                        repository.saveWeeklyReview(fresh.copy(aiText = null))
                    }
                }
                FastTrackWidget.refreshAll(this@FastTrackApp)
            }
        }
        appScope.launch {
            repository.observeActiveFast()
                .distinctUntilChanged()
                .collect { FastingMilestones.sync(this@FastTrackApp, it) }
        }

        // Keep Garmin data fresh in the background (covers times the app is closed).
        ai.anthracite.fasttrack.integration.garmin.GarminSyncWorker.schedule(this)

        // Keep Garmin data fresh while the app is open; pause the loop when backgrounded.
        registerActivityLifecycleCallbacks(object : SimpleActivityLifecycleCallbacks() {
            override fun onActivityStarted(activity: Activity) {
                // Foregrounding (including returning from background) resumes the auto-sync loop,
                // which immediately pulls if the data is stale. No "welcome back" prompt.
                if (startedActivities++ == 0) startGarminAutoSync()
            }

            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities <= 0) {
                    startedActivities = 0
                    stopGarminAutoSync()
                }
            }
        })
    }

    /**
     * Records that the user opened the app today, keeping the login streak alive. The daily-update
     * popup + its archive are handled by the dashboard (which has the recap + recommendations).
     * Logging in is treated as a first-class streak, as important as fasting.
     */
    private fun recordDailyLogin() {
        val now = System.currentTimeMillis()
        val todayKey = ai.anthracite.fasttrack.util.DayKeys.of(now)
        val yesterdayKey = ai.anthracite.fasttrack.util.DayKeys.of(now - TimeUnit.DAYS.toMillis(1))
        prefs.recordLogin(todayKey, yesterdayKey)
    }

    /**
     * Pull Garmin daily wellness snapshots (stress, resting HR, body battery, sleep) for the given
     * window and upsert them. Returns the number of days written. Activities are handled separately
     * by [healthSyncManager]. No-op when Garmin isn't configured.
     */
    suspend fun syncGarminDailyMetrics(sinceEpochMs: Long, untilEpochMs: Long): Int {
        val metrics = garminClient.fetchDailyMetrics(sinceEpochMs, untilEpochMs)
        if (metrics.isNotEmpty()) repository.upsertDailyMetrics(metrics)
        return metrics.size
    }

    /**
     * Pull the latest activities + daily wellness from Garmin now. Safe to call often — it no-ops
     * when Garmin isn't configured or a pull is already in flight. Because the UI observes Room
     * flows, any new data lights up the dashboard/trends the moment it lands.
     */
    suspend fun syncGarminNow() {
        if (!garminClient.isConfigured || garminSyncing) return
        garminSyncing = true
        try {
            val now = System.currentTimeMillis()
            val since = now - TimeUnit.DAYS.toMillis(14)
            runCatching { healthSyncManager.syncRange(since, now) }
            runCatching { syncGarminDailyMetrics(since, now) }
            lastGarminSyncMs = System.currentTimeMillis()
        } finally {
            garminSyncing = false
        }
    }

    /** Pull only if it's been at least [GARMIN_MIN_INTERVAL_MS] since the last successful sync. */
    private suspend fun autoSyncGarminIfStale() {
        if (!garminClient.isConfigured) return
        if (System.currentTimeMillis() - lastGarminSyncMs < GARMIN_MIN_INTERVAL_MS) return
        syncGarminNow()
    }

    /** Starts a refresh loop that runs while the app is foregrounded, then stops in the background. */
    private fun startGarminAutoSync() {
        if (garminLoopJob?.isActive == true) return
        garminLoopJob = appScope.launch {
            while (isActive) {
                autoSyncGarminIfStale()
                delay(GARMIN_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopGarminAutoSync() {
        garminLoopJob?.cancel()
        garminLoopJob = null
    }

    /** Called when the user turns AI on or off in Settings. */
    fun setAiFeaturesEnabled(enabled: Boolean) {
        prefs.aiFeaturesEnabled = enabled
        if (enabled) {
            appScope.launch { mealModelManager.ensureInstalled() }
        } else {
            liteRtEstimator.close()
        }
    }

    companion object {
        /** Bump when fasting stat rules change so archived weekly reviews get recomputed. */
        private const val FAST_RULES_VERSION = 2
        /** How often to re-pull while the app is in the foreground. */
        private val GARMIN_REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(3)
        /** Don't pull more often than this, even across foreground events. */
        private val GARMIN_MIN_INTERVAL_MS = TimeUnit.SECONDS.toMillis(60)
    }
}

/** Boilerplate-free base so we only override the two lifecycle callbacks we care about. */
private abstract class SimpleActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
