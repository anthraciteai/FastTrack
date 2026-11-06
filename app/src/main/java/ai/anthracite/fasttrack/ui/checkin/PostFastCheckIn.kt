package ai.anthracite.fasttrack.ui.checkin

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.ui.common.FastCelebration
import ai.anthracite.fasttrack.util.TimeFmt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The mood / energy / hunger check-in belongs to a fast: it's asked right after one ends, and only
 * when it got far enough to say something ([FastHistory.qualifiesForCheckIn]).
 */
object PostFastCheckIn {

    /** Call when the user ends a fast in-app: celebrate a reached goal, then ask how it felt. */
    fun afterEnded(activity: FragmentActivity, ended: FastingSession) {
        val ms = (ended.endEpochMs ?: return) - ended.startEpochMs
        when {
            FastHistory.isCompleted(ended) -> FastCelebration.show(activity, ms) { prompt(activity, ended) }
            FastHistory.qualifiesForCheckIn(ended) -> prompt(activity, ended)
        }
    }

    fun prompt(activity: FragmentActivity, fast: FastingSession) {
        if (activity.isFinishing || activity.isDestroyed) return
        val app = activity.application as FastTrackApp
        val day = FastHistory.dayOf(fast)
        activity.lifecycleScope.launch {
            val existing = app.repository.allCheckIns().firstOrNull { it.dayKey == day }
            val duration = TimeFmt.hmShort((fast.endEpochMs ?: System.currentTimeMillis()) - fast.startEpochMs)
            CheckInDialog.show(activity, existing, "How do you feel after your $duration fast?") { m, e, h, note ->
                activity.lifecycleScope.launch {
                    app.repository.saveCheckIn(
                        DailyCheckIn(
                            dayKey = day,
                            epochMs = System.currentTimeMillis(),
                            mood = m, energy = e, hunger = h,
                            note = note?.trim()?.ifBlank { null }
                        )
                    )
                }
            }
        }
    }
}
