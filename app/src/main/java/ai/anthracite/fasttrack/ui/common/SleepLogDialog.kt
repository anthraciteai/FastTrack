package ai.anthracite.fasttrack.ui.common

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.DialogLogSleepBinding
import ai.anthracite.fasttrack.integration.sleep.SleepEstimator
import android.app.TimePickerDialog
import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import java.util.Locale

/**
 * Lets the user log/confirm last night's sleep by picking a bedtime and wake time. When an
 * [SleepEstimator.SleepEstimate] is supplied (phone-usage estimate), the pickers are pre-filled so
 * the user usually just taps Save. Reports the resulting duration in minutes via [onSave].
 */
object SleepLogDialog {

    fun show(
        context: Context,
        estimate: SleepEstimator.SleepEstimate?,
        onSave: (minutes: Int) -> Unit
    ) {
        val binding = DialogLogSleepBinding.inflate(LayoutInflater.from(context))

        // Minutes-past-midnight for each picker. Defaults to a plausible 11pm–7am if no estimate.
        var bedMin = estimate?.let { minuteOfDay(it.bedtimeMs) } ?: (23 * 60)
        var wakeMin = estimate?.let { minuteOfDay(it.wakeMs) } ?: (7 * 60)

        binding.sleepEstimateNote.text = context.getString(
            if (estimate != null) R.string.sleep_estimate_note else R.string.sleep_estimate_none
        )

        fun refresh() {
            binding.bedtimeButton.text = formatTime(bedMin)
            binding.wakeButton.text = formatTime(wakeMin)
            val mins = durationMinutes(bedMin, wakeMin)
            binding.sleepDurationLabel.text =
                context.getString(R.string.sleep_duration, mins / 60, mins % 60)
        }
        refresh()

        binding.bedtimeButton.setOnClickListener {
            pickTime(context, bedMin) { bedMin = it; refresh() }
        }
        binding.wakeButton.setOnClickListener {
            pickTime(context, wakeMin) { wakeMin = it; refresh() }
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.sleep_log_title)
            .setView(binding.root)
            .setPositiveButton(R.string.sleep_save) { _, _ ->
                onSave(durationMinutes(bedMin, wakeMin))
            }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    /** Overnight-aware duration: if wake is at/earlier than bed, it's the next morning. */
    private fun durationMinutes(bedMin: Int, wakeMin: Int): Int {
        val diff = wakeMin - bedMin
        return if (diff <= 0) diff + 24 * 60 else diff
    }

    private fun pickTime(context: Context, current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            current / 60, current % 60, false
        ).show()
    }

    private fun minuteOfDay(ms: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun formatTime(minuteOfDay: Int): String {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
        }
        return java.text.SimpleDateFormat("h:mm a", Locale.US).format(c.time)
    }
}
