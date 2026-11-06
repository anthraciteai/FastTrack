package ai.anthracite.fasttrack.ui.checkin

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.CheckInInsights
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt

/** Quick daily check-in: mood, energy and hunger on a 1–5 scale plus an optional note. */
object CheckInDialog {

    val MOOD_EMOJI = listOf("😣", "😕", "😐", "🙂", "😄")
    private val ENERGY_EMOJI = listOf("🪫", "😴", "⚖️", "⚡", "🚀")
    private val HUNGER_EMOJI = listOf("😌", "🙂", "🍽️", "😋", "🤤")

    fun show(
        context: Context,
        existing: DailyCheckIn?,
        title: String? = null,
        onSave: (mood: Int, energy: Int, hunger: Int, note: String?) -> Unit
    ) {
        val pad = (20 * context.resources.displayMetrics.density).roundToInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val mood = scale(context, root, "Mood", CheckInInsights.MOOD_LABELS, MOOD_EMOJI, existing?.mood ?: 3)
        val energy = scale(context, root, "Energy", CheckInInsights.ENERGY_LABELS, ENERGY_EMOJI, existing?.energy ?: 3)
        val hunger = scale(context, root, "Hunger", CheckInInsights.HUNGER_LABELS, HUNGER_EMOJI, existing?.hunger ?: 2)
        val noteInput = TextInputEditText(context).apply { setText(existing?.note.orEmpty()) }
        root.addView(TextInputLayout(context).apply {
            hint = "Anything worth noting? (optional)"
            addView(noteInput)
            setPadding(0, pad / 2, 0, 0)
        })

        MaterialAlertDialogBuilder(context)
            .setTitle(title ?: if (existing == null) "How are you feeling?" else "Update today's check-in")
            .setView(root)
            .setNegativeButton("Later", null)
            .setPositiveButton("Save") { _, _ ->
                onSave(mood.selected(), energy.selected(), hunger.selected(), noteInput.text?.toString())
            }
            .show()
    }

    private fun scale(
        context: Context,
        parent: LinearLayout,
        title: String,
        labels: List<String>,
        emoji: List<String>,
        initial: Int
    ): ChipGroup {
        parent.addView(TextView(context).apply {
            setTextAppearance(R.style.Text_FastTrack_SectionTitle)
            text = title
            setPadding(0, (12 * context.resources.displayMetrics.density).roundToInt(), 0, 0)
        })
        val group = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        labels.forEachIndexed { i, label ->
            group.addView(Chip(context).apply {
                id = android.view.View.generateViewId()
                text = "${emoji[i]} $label"
                isCheckable = true
                isChecked = i + 1 == initial
                tag = i + 1
            })
        }
        parent.addView(group)
        return group
    }

    private fun ChipGroup.selected(): Int =
        (findViewById<Chip>(checkedChipId)?.tag as? Int) ?: 3
}
