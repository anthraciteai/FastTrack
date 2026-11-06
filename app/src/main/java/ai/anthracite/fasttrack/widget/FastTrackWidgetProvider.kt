package ai.anthracite.fasttrack.widget

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.notifications.NotificationHelper
import ai.anthracite.fasttrack.session.LiveSessions
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FastTrackWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        FastTrackWidget.refreshAll(context)
    }

    override fun onEnabled(context: Context) {
        FastTrackWidget.refreshAll(context)
    }

    override fun onDisabled(context: Context) {
        FastTrackWidget.refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == FastTrackWidget.ACTION_START_FAST ||
            action == FastTrackWidget.ACTION_END_FAST ||
            action == FastTrackWidget.ACTION_STOP_WORKOUT ||
            action == FastTrackWidget.ACTION_REFRESH
        ) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try {
                    val app = context.applicationContext as? FastTrackApp
                    if (app != null) {
                        when (action) {
                            FastTrackWidget.ACTION_START_FAST -> LiveSessions.startFast(app)
                            FastTrackWidget.ACTION_END_FAST -> LiveSessions.endFast(app)
                                ?.takeIf { FastHistory.qualifiesForCheckIn(it) }
                                ?.let { NotificationHelper.showCheckInPrompt(context, it) }
                            FastTrackWidget.ACTION_STOP_WORKOUT -> LiveSessions.stopWorkout(app)
                            FastTrackWidget.ACTION_REFRESH -> FastTrackWidget.renderAll(context)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }
}
