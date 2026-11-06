package ai.anthracite.fasttrack.widget

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.MainActivity
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.WorkoutSession
import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FastTrackWidget {

    const val ACTION_START_FAST = "ai.anthracite.fasttrack.widget.START_FAST"
    const val ACTION_END_FAST = "ai.anthracite.fasttrack.widget.END_FAST"
    const val ACTION_STOP_WORKOUT = "ai.anthracite.fasttrack.widget.STOP_WORKOUT"
    const val ACTION_REFRESH = "ai.anthracite.fasttrack.widget.REFRESH"
    const val EXTRA_DEST = "widget_dest"
    const val DEST_FASTING = "fasting"
    const val DEST_EXERCISE = "exercise"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val REQ_TICK = 7101
    private const val REQ_GOAL = 7102

    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        scope.launch { renderAll(appContext) }
    }

    suspend fun renderAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(ComponentName(context, FastTrackWidgetProvider::class.java))
        if (ids.isEmpty()) {
            cancelTick(context)
            return
        }
        val app = context.applicationContext as? FastTrackApp ?: return
        val fast = app.repository.getActiveFast()
        val workout = app.repository.getActiveWorkout()
        val protocol = FastingProtocol.fromId(app.repository.getProfile().fastingProtocol)
        // When not fasting, the most recent fast's end + eating window is our countdown to the
        // next fast (mirrors the in-app card) instead of a static "Ready to fast".
        val lastFinished = if (fast == null) app.repository.getLastFinishedFast() else null
        val eatingWindowMs = ((24.0 - protocol.fastHours) * 3_600_000L).toLong()
        val nextFastStart = lastFinished?.endEpochMs
            ?.let { it + eatingWindowMs }
            ?.takeIf { eatingWindowMs > 0L && it > System.currentTimeMillis() }
        ids.forEach { id ->
            mgr.updateAppWidget(id, buildRemoteViews(context, fast, workout, protocol, nextFastStart))
        }
        syncTick(context, fast != null || workout != null, fast, nextFastStart)
    }

    private fun buildRemoteViews(
        context: Context,
        fast: FastingSession?,
        workout: WorkoutSession?,
        protocol: FastingProtocol,
        nextFastStart: Long?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_fasttrack)
        val now = System.currentTimeMillis()

        views.setOnClickPendingIntent(R.id.widgetRoot, openApp(context, DEST_FASTING))
        views.setOnClickPendingIntent(
            R.id.widgetFastButton,
            if (fast != null) broadcast(context, ACTION_END_FAST, 4) else broadcast(context, ACTION_START_FAST, 1)
        )

        if (fast != null) {
            val elapsed = now - fast.startEpochMs
            val goalMs = (fast.goalHours * 3_600_000L).toLong()
            val remaining = goalMs - elapsed
            if (remaining > 0) {
                views.setTextViewText(R.id.widgetStatus, context.getString(R.string.fasting_remaining))
                views.setChronometerCountDown(R.id.widgetTimer, true)
                views.setChronometer(R.id.widgetTimer, SystemClock.elapsedRealtime() + remaining, null, true)
            } else {
                views.setTextViewText(R.id.widgetStatus, context.getString(R.string.fasting_elapsed))
                views.setChronometerCountDown(R.id.widgetTimer, false)
                views.setChronometer(R.id.widgetTimer, SystemClock.elapsedRealtime() - elapsed, null, true)
            }
            views.setTextViewText(R.id.widgetFastButton, context.getString(R.string.fasting_stop))
        } else if (nextFastStart != null) {
            // Eating window: count down to the next fast, relative to when the last fast ended.
            views.setTextViewText(R.id.widgetStatus, context.getString(R.string.fasting_eating_window))
            views.setChronometerCountDown(R.id.widgetTimer, true)
            views.setChronometer(
                R.id.widgetTimer,
                SystemClock.elapsedRealtime() + (nextFastStart - now),
                null,
                true
            )
            views.setTextViewText(R.id.widgetFastButton, context.getString(R.string.fasting_start))
        } else {
            views.setTextViewText(R.id.widgetStatus, context.getString(R.string.widget_ready_to_fast))
            views.setChronometerCountDown(R.id.widgetTimer, false)
            views.setChronometer(R.id.widgetTimer, SystemClock.elapsedRealtime(), null, false)
            views.setTextViewText(R.id.widgetTimer, "${protocol.fastHours.toInt()}h")
            views.setTextViewText(R.id.widgetFastButton, context.getString(R.string.fasting_start))
        }

        if (workout != null) {
            views.setViewVisibility(R.id.widgetWorkoutRow, View.VISIBLE)
            views.setTextViewText(R.id.widgetWorkoutType, workout.type)
            val elapsed = now - workout.startEpochMs
            views.setChronometerCountDown(R.id.widgetWorkoutTimer, false)
            views.setChronometer(
                R.id.widgetWorkoutTimer,
                SystemClock.elapsedRealtime() - elapsed,
                null,
                true
            )
            views.setTextViewText(R.id.widgetActivityButton, context.getString(R.string.exercise_stop_activity))
            views.setOnClickPendingIntent(
                R.id.widgetActivityButton,
                broadcast(context, ACTION_STOP_WORKOUT, 2)
            )
        } else {
            views.setViewVisibility(R.id.widgetWorkoutRow, View.GONE)
            views.setTextViewText(R.id.widgetActivityButton, context.getString(R.string.exercise_start_activity))
            views.setOnClickPendingIntent(R.id.widgetActivityButton, startActivityPicker(context))
        }
        return views
    }

    private fun openApp(context: Context, dest: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_DEST, dest)
        }
        return PendingIntent.getActivity(
            context,
            dest.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun startActivityPicker(context: Context): PendingIntent {
        val intent = Intent(context, WidgetActionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            context,
            3,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, FastTrackWidgetProvider::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun syncTick(context: Context, active: Boolean, fast: FastingSession?, nextFastStart: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val tick = refreshPending(context, REQ_TICK)
        val goal = refreshPending(context, REQ_GOAL)
        val now = System.currentTimeMillis()
        if (!active && nextFastStart == null) {
            am.cancel(tick)
            am.cancel(goal)
            return
        }
        // The RemoteViews Chronometer ticks itself; the 60s alarm is only needed to keep an active
        // fast's progress fresh. The one-shot "goal" alarm flips the widget at the key moment:
        // fast goal reached, or the eating window closing back into "Ready to fast".
        if (active) {
            am.setInexactRepeating(AlarmManager.RTC, now + 60_000L, 60_000L, tick)
        } else {
            am.cancel(tick)
        }
        val refreshAt = when {
            fast != null -> (fast.startEpochMs + (fast.goalHours * 3_600_000L).toLong())
            else -> nextFastStart
        }
        if (refreshAt != null && refreshAt > now) {
            am.setAndAllowWhileIdle(AlarmManager.RTC, refreshAt + 500L, goal)
        } else {
            am.cancel(goal)
        }
    }

    private fun cancelTick(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(refreshPending(context, REQ_TICK))
        am.cancel(refreshPending(context, REQ_GOAL))
    }

    private fun refreshPending(context: Context, requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, FastTrackWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
