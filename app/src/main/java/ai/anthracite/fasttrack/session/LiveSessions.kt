package ai.anthracite.fasttrack.session

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.notifications.ReminderScheduler
import ai.anthracite.fasttrack.widget.FastTrackWidget
import java.util.Calendar

/**
 * Shared start/stop for fasting and live workouts so the home-screen widget and in-app screens
 * stay in sync (schedule, calendar, Garmin kickoff, widget refresh).
 */
object LiveSessions {

    val activityTypes = listOf(
        "Run", "Walk", "Cycling", "Strength", "Yoga", "Swim", "HIIT", "Hike", "Row",
        "Soccer", "Basketball", "Tennis", "Other"
    )

    /**
     * Start a fast on the user's protocol. Start and end are explicit, never a "toggle": a stale
     * button (widget, notification, double-tap) must not flip you into the opposite state.
     */
    suspend fun startFast(app: FastTrackApp) {
        val protocol = FastingProtocol.fromId(app.repository.getProfile().fastingProtocol)
        if (app.repository.startFast(protocol)) {
            // Lock in this start time as a daily reminder so we nudge at the same time each day.
            lockInDailyFastTime(app)
        }
        FastTrackWidget.refreshAll(app)
    }

    /** End the running fast; returns it (null if nothing was running or it was a mis-tap). */
    suspend fun endFast(app: FastTrackApp) =
        app.repository.endFast().also { FastTrackWidget.refreshAll(app) }

    private fun hasLocationPermission(app: FastTrackApp): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            app, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Discards the active fast without recording it. */
    suspend fun cancelFast(app: FastTrackApp) {
        app.repository.cancelActiveFast()
        FastTrackWidget.refreshAll(app)
    }

    /**
     * Remember the time-of-day of the user's *first* fast as their daily fast start and schedule
     * the recurring nudge. Once set it stays put — we don't silently drift it every time they tap
     * Start, so the "next fast" reminder represents a stable daily schedule.
     */
    private fun lockInDailyFastTime(app: FastTrackApp) {
        if (app.prefs.dailyFastReminderEnabled && app.prefs.dailyFastStartMinOfDay in 0..1439) return
        val cal = Calendar.getInstance()
        val minOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        app.prefs.dailyFastStartMinOfDay = minOfDay
        app.prefs.dailyFastReminderEnabled = true
        ReminderScheduler.scheduleDailyFastStart(app, minOfDay)
    }

    suspend fun startWorkout(app: FastTrackApp, type: String) {
        if (app.repository.getActiveWorkout() != null) return
        app.prefs.lastWorkoutType = type
        val session = app.repository.startWorkout(type)
        // Track GPS distance for walks/runs/rides so the live card and the saved log have distance.
        if (WorkoutTracker.tracksDistance(type) && hasLocationPermission(app)) {
            val unit = runCatching {
                ai.anthracite.fasttrack.util.UnitSystem.valueOf(app.repository.getProfile().unitSystem)
            }.getOrDefault(ai.anthracite.fasttrack.util.UnitSystem.IMPERIAL)
            WorkoutTracker.begin(type, session.startEpochMs)
            WorkoutTrackingService.start(app, type, unit)
        }
        for (provider in app.healthSyncManager.providers) {
            if (provider.status().state == ConnectionState.CONNECTED) {
                provider.startLiveSession(type)
                break
            }
        }
        val event = app.repository.scheduleEventForWorkout(session)
        if (event != null) {
            val remoteId = app.calendarClient.pushEvent(event)
            if (!remoteId.isNullOrBlank()) {
                app.repository.updateEvent(event.copy(calendarEventId = remoteId, synced = true))
            }
        }
        FastTrackWidget.refreshAll(app)
    }

    suspend fun stopWorkout(app: FastTrackApp): ExerciseLog? {
        // Capture the GPS distance (if we were tracking) before tearing the tracker down.
        val trackedDistance = if (WorkoutTracker.isTracking) WorkoutTracker.distanceMeters else null
        WorkoutTrackingService.stop(app)
        WorkoutTracker.clear()
        val finished = app.repository.endWorkout(trackedDistance) ?: return null
        finished.event?.let { event ->
            if (!event.calendarEventId.isNullOrBlank()) {
                app.calendarClient.updateEvent(event)
            } else {
                val remoteId = app.calendarClient.pushEvent(event)
                if (!remoteId.isNullOrBlank()) {
                    app.repository.updateEvent(event.copy(calendarEventId = remoteId, synced = true))
                }
            }
        }
        FastTrackWidget.refreshAll(app)
        return finished.log
    }
}
