package ai.anthracite.fasttrack.notifications

import ai.anthracite.fasttrack.data.health.FastingStages
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.util.Prefs
import ai.anthracite.fasttrack.widget.FastTrackWidget
import android.content.Context

/**
 * Keeps the fasting-stage alarms, the goal-reached alarm and the ongoing timer notification in
 * step with the active fast. Called whenever the active fast changes (start, edit, end).
 */
object FastingMilestones {
    private const val REQ_GOAL = 3099
    private const val REQ_STAGE_BASE = 3100

    /** Stages before this index (Fed, Blood sugar falling) are too early to be worth a ping. */
    private const val FIRST_NOTIFIED_STAGE = 2

    /** A stage that starts within this window of the goal is folded into the goal alert. */
    private const val MERGE_WINDOW_MS = 5 * 60_000L

    data class Milestone(val atMs: Long, val stageIndex: Int, val isGoal: Boolean)

    /** Upcoming milestones for a fast that started at [startMs], in time order. */
    fun plan(startMs: Long, goalHours: Double, nowMs: Long): List<Milestone> {
        val goalAt = startMs + (goalHours * 3_600_000L).toLong()
        val stages = FastingStages.ALL.withIndex()
            .filter { it.index >= FIRST_NOTIFIED_STAGE }
            .map { Milestone(startMs + (it.value.startHour * 3_600_000L).toLong(), it.index, false) }
            .filter { kotlin.math.abs(it.atMs - goalAt) > MERGE_WINDOW_MS }
        val goalStage = FastingStages.indexFor(goalHours)
        return (stages + Milestone(goalAt, goalStage, true))
            .filter { it.atMs > nowMs }
            .sortedBy { it.atMs }
    }

    fun sync(context: Context, active: FastingSession?) {
        cancelAll(context)
        FastTrackWidget.refreshAll(context)
        val prefs = Prefs(context)
        if (active == null || active.endEpochMs != null) {
            NotificationHelper.cancelFastingOngoing(context)
            return
        }
        if (prefs.fastingRemindersEnabled || prefs.fastingOngoingEnabled) {
            plan(active.startEpochMs, active.goalHours, System.currentTimeMillis()).forEach { m ->
                ReminderScheduler.scheduleFastingAlarm(
                    context,
                    requestCode = if (m.isGoal) REQ_GOAL else REQ_STAGE_BASE + m.stageIndex,
                    triggerAtMs = m.atMs,
                    kind = if (m.isGoal) ReminderScheduler.KIND_FASTING_END else ReminderScheduler.KIND_FASTING_STAGE,
                    fastStartMs = active.startEpochMs,
                    stageIndex = m.stageIndex
                )
            }
        }
        if (prefs.fastingOngoingEnabled) {
            NotificationHelper.showFastingOngoing(context, active)
        } else {
            NotificationHelper.cancelFastingOngoing(context)
        }
    }

    private fun cancelAll(context: Context) {
        ReminderScheduler.cancelFastingAlarm(context, REQ_GOAL, ReminderScheduler.KIND_FASTING_END)
        FastingStages.ALL.indices.forEach { i ->
            ReminderScheduler.cancelFastingAlarm(context, REQ_STAGE_BASE + i, ReminderScheduler.KIND_FASTING_STAGE)
        }
    }
}
