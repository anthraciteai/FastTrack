package ai.anthracite.fasttrack.ui.common

import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Shows a form dialog that stays usable with the keyboard up: the window resizes, and tall
 * content is capped so a [NestedScrollView] can actually scroll instead of stretching off-screen.
 */
fun MaterialAlertDialogBuilder.showScrollable(content: View): Dialog {
    val dialog = create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    dialog.setOnShowListener {
        val maxH = (content.resources.displayMetrics.heightPixels * 0.58f).toInt()
        content.post {
            if (content.height > maxH) {
                content.layoutParams = (content.layoutParams ?: ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, maxH
                )).apply { height = maxH }
            }
            (content as? NestedScrollView)?.apply {
                isFillViewport = true
                isNestedScrollingEnabled = true
            }
        }
    }
    dialog.show()
    return dialog
}
