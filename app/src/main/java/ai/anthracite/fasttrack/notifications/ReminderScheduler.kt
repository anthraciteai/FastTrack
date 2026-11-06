package ai.anthracite.fasttrack.notifications

import ai.anthracite.fasttrack.util.Prefs
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Schedules repeating local reminders via AlarmManager. Water uses an inexact repeating alarm on
 * the user's chosen interval; fasting/workout reminders are scheduled on demand from their features.
 */
object ReminderScheduler {
    const val EXTRA_KIND = "kind"
    const val KIND_WATER = "water"
    const val KIND_FASTING_END = "fasting_end"
    const val KIND_FASTING_START = "fasting_start"
    const val KIND_WORKOUT = "workout"
    const val KIND_SLEEP = "sleep"
    const val KIND_FASTING_STAGE = "fasting_stage"
    const val KIND_WEEKLY_REVIEW = "weekly_review"
    const val EXTRA_FAST_START = "fast_start_ms"
    const val EXTRA_STAGE_INDEX = "stage_index"

    private const val REQ_WATER = 1001
    private const val REQ_FASTING_START = 1002
    private const val REQ_SLEEP = 1003
    private const val REQ_WEEKLY_REVIEW = 1004
    /** Weekly review fires Sunday at 6 PM. */
    private const val WEEKLY_REVIEW_MIN_OF_DAY = 18 * 60

    fun syncFromPrefs(context: Context) {
        val prefs = Prefs(context)
        if (prefs.waterReminderEnabled) {
            scheduleWater(context, prefs.waterIntervalMin)
        } else {
            cancelWater(context)
        }
        if (prefs.dailyFastReminderEnabled && prefs.dailyFastStartMinOfDay in 0..1439) {
            scheduleDailyFastStart(context, prefs.dailyFastStartMinOfDay)
        } else {
            cancelDailyFastStart(context)
        }
        if (prefs.sleepReminderEnabled) {
            scheduleDailySleep(context, prefs.sleepReminderMinOfDay)
        } else {
            cancelDailySleep(context)
        }
        val weekly = pending(context, REQ_WEEKLY_REVIEW, KIND_WEEKLY_REVIEW)
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (prefs.weeklyReviewEnabled) {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                nextWeeklyTrigger(java.util.Calendar.SUNDAY, WEEKLY_REVIEW_MIN_OF_DAY),
                AlarmManager.INTERVAL_DAY * 7,
                weekly
            )
        } else {
            am.cancel(weekly)
        }
    }

    /** Next epoch-ms for [dayOfWeek] (Calendar constant) at [minOfDay]. */
    fun nextWeeklyTrigger(dayOfWeek: Int, minOfDay: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, minOfDay / 60)
            set(java.util.Calendar.MINUTE, minOfDay % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val shift = (dayOfWeek - cal.get(java.util.Calendar.DAY_OF_WEEK) + 7) % 7
        cal.add(java.util.Calendar.DAY_OF_YEAR, shift)
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 7)
        return cal.timeInMillis
    }

    /** Exact one-shot for a fasting milestone, tagged with the fast it belongs to. */
    fun scheduleFastingAlarm(
        context: Context,
        requestCode: Int,
        triggerAtMs: Long,
        kind: String,
        fastStartMs: Long,
        stageIndex: Int
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_FAST_START, fastStartMs)
            .putExtra(EXTRA_STAGE_INDEX, stageIndex)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }

    fun cancelFastingAlarm(context: Context, requestCode: Int, kind: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, requestCode, kind))
    }

    private fun pending(context: Context, requestCode: Int, kind: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_KIND, kind)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun scheduleWater(context: Context, intervalMin: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intervalMs = intervalMin.coerceAtLeast(15) * 60_000L
        val triggerAt = System.currentTimeMillis() + intervalMs
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            intervalMs,
            pending(context, REQ_WATER, KIND_WATER)
        )
    }

    fun cancelWater(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, REQ_WATER, KIND_WATER))
    }

    /** Daily nudge to start the fast at the same time every day ([minOfDay] past midnight). */
    fun scheduleDailyFastStart(context: Context, minOfDay: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextDailyTrigger(minOfDay),
            AlarmManager.INTERVAL_DAY,
            pending(context, REQ_FASTING_START, KIND_FASTING_START)
        )
    }

    fun cancelDailyFastStart(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, REQ_FASTING_START, KIND_FASTING_START))
    }

    /** Daily morning nudge to log/confirm last night's sleep ([minOfDay] past midnight). */
    fun scheduleDailySleep(context: Context, minOfDay: Int) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            nextDailyTrigger(minOfDay),
            AlarmManager.INTERVAL_DAY,
            pending(context, REQ_SLEEP, KIND_SLEEP)
        )
    }

    fun cancelDailySleep(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, REQ_SLEEP, KIND_SLEEP))
    }

    /** Next epoch-ms at which [minOfDay] occurs (today if still ahead, otherwise tomorrow). */
    fun nextDailyTrigger(minOfDay: Int): Long {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, minOfDay / 60)
            set(java.util.Calendar.MINUTE, minOfDay % 60)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** One-shot reminder, e.g. "your fasting goal is reached" or a scheduled workout. */
    fun scheduleOneShot(context: Context, requestCode: Int, triggerAtMs: Long, kind: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, requestCode, kind)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
    }
}
