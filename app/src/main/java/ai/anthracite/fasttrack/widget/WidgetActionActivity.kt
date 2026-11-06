package ai.anthracite.fasttrack.widget

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.session.LiveSessions
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/** Lightweight picker so Start activity from the home-screen widget doesn't open the full app. */
class WidgetActionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val types = LiveSessions.activityTypes.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exercise_start_activity)
            .setItems(types) { _, which ->
                lifecycleScope.launch {
                    LiveSessions.startWorkout(application as FastTrackApp, types[which])
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
