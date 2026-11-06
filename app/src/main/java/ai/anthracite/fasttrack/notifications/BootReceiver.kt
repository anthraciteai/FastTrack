package ai.anthracite.fasttrack.notifications

import ai.anthracite.fasttrack.widget.FastTrackWidget
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms reminder alarms after a reboot (alarms don't survive restarts). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            NotificationHelper.ensureChannels(context)
            ReminderScheduler.syncFromPrefs(context)
            FastTrackWidget.refreshAll(context)
        }
    }
}
