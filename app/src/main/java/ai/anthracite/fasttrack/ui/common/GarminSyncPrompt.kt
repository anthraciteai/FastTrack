package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.R
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Small, dependency-free prompts around pulling Garmin data. The actual pull is handled by the
 * caller via [onSync]; these just explain the flow and ask for intent.
 */
object GarminSyncPrompt {

    /** Explains how Garmin data gets here, with a "Sync now" button that triggers a pull. */
    fun showHowTo(context: Context, onSync: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sync_howto_title)
            .setMessage(R.string.sync_howto_body)
            .setPositiveButton(R.string.sync_now) { d, _ ->
                d.dismiss()
                onSync()
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }
}
