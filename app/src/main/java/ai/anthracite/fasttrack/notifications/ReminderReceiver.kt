package ai.anthracite.fasttrack.notifications

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.FastingStages
import ai.anthracite.fasttrack.data.health.WeeklyReviewService
import ai.anthracite.fasttrack.util.Prefs
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationHelper.ensureChannels(context)
        when (val kind = intent.getStringExtra(ReminderScheduler.EXTRA_KIND)) {
            ReminderScheduler.KIND_WATER -> NotificationHelper.show(
                context, 2001,
                "Time to hydrate", "Take a sip to stay on track with your daily goal."
            )
            ReminderScheduler.KIND_FASTING_END, ReminderScheduler.KIND_FASTING_STAGE ->
                onFastingMilestone(context, intent, kind == ReminderScheduler.KIND_FASTING_END)
            ReminderScheduler.KIND_FASTING_START -> NotificationHelper.show(
                context, 2004,
                "Time to start your fast", "Your daily fasting window begins now. Tap to start.",
                NotificationHelper.CHANNEL_FASTING
            )
            ReminderScheduler.KIND_WORKOUT -> NotificationHelper.show(
                context, 2003,
                "Workout coming up", "You have a scheduled activity soon."
            )
            ReminderScheduler.KIND_SLEEP -> NotificationHelper.showSleepNudge(
                context, 2005,
                context.getString(R.string.sleep_nudge_title),
                context.getString(R.string.sleep_nudge_body)
            )
            ReminderScheduler.KIND_WEEKLY_REVIEW -> onWeeklyReview(context)
        }
    }

    /** Only alert if the fast this alarm was scheduled for is still the one running. */
    private fun onFastingMilestone(context: Context, intent: Intent, isGoal: Boolean) {
        val app = context.applicationContext as? FastTrackApp ?: return
        val expectedStart = intent.getLongExtra(ReminderScheduler.EXTRA_FAST_START, 0L)
        val stageIndex = intent.getIntExtra(ReminderScheduler.EXTRA_STAGE_INDEX, -1)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val active = app.repository.getActiveFast() ?: return@launch
                if (expectedStart != 0L && active.startEpochMs != expectedStart) return@launch
                val prefs = Prefs(context)
                if (prefs.fastingRemindersEnabled) {
                    val stage = FastingStages.ALL.getOrNull(stageIndex)
                    val goalLabel = active.goalHours.let { if (it % 1.0 == 0.0) "${it.toInt()}h" else "${it}h" }
                    if (isGoal) {
                        val body = buildString {
                            append("You hit your $goalLabel fast. Break it gently — protein and water first.")
                            if (stage != null) append(" You're in ${stage.title}: ${stage.bodyState}.")
                        }
                        NotificationHelper.showMilestone(context, NotificationHelper.ID_MILESTONE, "Fasting goal reached", body)
                    } else if (stage != null) {
                        NotificationHelper.showMilestone(
                            context, NotificationHelper.ID_MILESTONE,
                            "${stage.title} · ${stage.startHour.toInt()}h",
                            stage.description
                        )
                    }
                }
                if (prefs.fastingOngoingEnabled) NotificationHelper.showFastingOngoing(context, active)
            } finally {
                pending.finish()
            }
        }
    }

    private fun onWeeklyReview(context: Context) {
        val app = context.applicationContext as? FastTrackApp ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val review = WeeklyReviewService.generateCurrent(app)
                NotificationHelper.showWeeklyReview(context, "Your weekly review is ready", review.headline)
            } finally {
                pending.finish()
            }
        }
    }
}
