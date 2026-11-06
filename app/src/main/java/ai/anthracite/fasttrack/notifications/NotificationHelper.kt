package ai.anthracite.fasttrack.notifications

import ai.anthracite.fasttrack.MainActivity
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.data.health.FastingStages
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.util.TimeFmt
import ai.anthracite.fasttrack.widget.FastTrackWidget
import ai.anthracite.fasttrack.widget.FastTrackWidgetProvider
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object NotificationHelper {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_FASTING = "fasting"
    const val CHANNEL_MILESTONES = "fasting_milestones"

    const val ID_FASTING_ONGOING = 2100
    const val ID_WORKOUT_ONGOING = 2101
    const val ID_WEEKLY_REVIEW = 2006
    const val ID_MILESTONE = 2007
    const val ID_CHECK_IN = 2008

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_reminders_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FASTING,
                context.getString(R.string.channel_fasting_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_fasting_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MILESTONES,
                "Fasting milestones",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Alerts when you enter a new fasting stage or reach your goal" }
        )
    }

    fun show(context: Context, id: Int, title: String, body: String, channel: String = CHANNEL_REMINDERS) {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    /** Morning nudge whose tap deep-links into the sleep-logging dialog. */
    fun showSleepNudge(context: Context, id: Int, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SLEEP, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    /** Fasting-stage / goal alert. Tapping opens the Fasting tab. */
    fun showMilestone(context: Context, id: Int, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_MILESTONES)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, id, FastTrackWidget.EXTRA_DEST, FastTrackWidget.DEST_FASTING))
            .setAutoCancel(true)
            .build()
        post(context, id, notification)
    }

    /** Fast ended outside the app (widget / notification): tapping opens the post-fast check-in. */
    fun showCheckInPrompt(context: Context, fast: FastingSession) {
        val duration = TimeFmt.hmShort((fast.endEpochMs ?: return) - fast.startEpochMs)
        val title = if (FastHistory.isCompleted(fast)) "Fast complete · $duration" else "Fast ended · $duration"
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText("How do you feel? Log mood, energy and hunger in 5 seconds.")
            .setContentIntent(openApp(context, ID_CHECK_IN, MainActivity.EXTRA_CHECK_IN_FAST, fast.id.toString()))
            .setAutoCancel(true)
            .build()
        post(context, ID_CHECK_IN, notification)
    }

    /** "Your weekly review is ready" — tapping opens the review screen. */
    fun showWeeklyReview(context: Context, title: String, body: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openApp(context, ID_WEEKLY_REVIEW, MainActivity.EXTRA_OPEN_WEEKLY_REVIEW, "1"))
            .setAutoCancel(true)
            .build()
        post(context, ID_WEEKLY_REVIEW, notification)
    }

    /**
     * Persistent fasting timer: counts down to the goal, then counts up once it's reached.
     * Shows the current body stage and an "End fast" action.
     */
    fun showFastingOngoing(context: Context, fast: FastingSession) {
        val now = System.currentTimeMillis()
        val goalAt = fast.startEpochMs + (fast.goalHours * 3_600_000L).toLong()
        val elapsedH = (now - fast.startEpochMs) / 3_600_000.0
        val stage = FastingStages.current(elapsedH)
        val next = FastingStages.next(elapsedH)
        val goalLabel = "${fmtHours(fast.goalHours)}h"
        val beforeGoal = now < goalAt
        val title = if (beforeGoal) "Fasting · ${stage.title}" else "Goal reached · ${stage.title}"
        val body = buildString {
            append(if (beforeGoal) "$goalLabel goal at ${TimeFmt.clock(goalAt)}" else "Past your $goalLabel goal — end whenever you're ready")
            if (next != null) append(" · ${next.title} at ${fmtHours(next.startHour)}h")
        }
        val endIntent = PendingIntent.getBroadcast(
            context, ID_FASTING_ONGOING,
            Intent(context, FastTrackWidgetProvider::class.java).setAction(FastTrackWidget.ACTION_END_FAST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_FASTING)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(beforeGoal)
            .setWhen(if (beforeGoal) goalAt else fast.startEpochMs)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(context, ID_FASTING_ONGOING, FastTrackWidget.EXTRA_DEST, FastTrackWidget.DEST_FASTING))
            .addAction(0, "End fast", endIntent)
            .build()
        post(context, ID_FASTING_ONGOING, notification)
    }

    fun cancelFastingOngoing(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_FASTING_ONGOING)
    }

    private fun fmtHours(h: Double): String =
        if (h % 1.0 == 0.0) h.toInt().toString() else String.format(java.util.Locale.US, "%.1f", h)

    private fun openApp(context: Context, requestCode: Int, extra: String, value: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(extra, value)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Update an already-shown notification (e.g. the ongoing workout), respecting permission. */
    fun postIfAllowed(context: Context, id: Int, notification: Notification) =
        post(context, id, notification)

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}
