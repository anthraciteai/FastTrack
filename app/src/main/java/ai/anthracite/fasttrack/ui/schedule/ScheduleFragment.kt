package ai.anthracite.fasttrack.ui.schedule

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.databinding.DialogAddEventBinding
import ai.anthracite.fasttrack.databinding.FragmentScheduleBinding
import ai.anthracite.fasttrack.ui.common.showScrollable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.launch
import java.util.Calendar

class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScheduleViewModel by viewModels()
    private val adapter = ScheduleAdapter { id -> confirmDelete(id) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.eventList.layoutManager = LinearLayoutManager(requireContext())
        binding.eventList.adapter = adapter

        binding.addEventFab.setOnClickListener { pickDate() }
        binding.syncButton.setOnClickListener {
            viewModel.syncCalendar { msg, movedCount -> showResult(msg, movedCount) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { events ->
                    adapter.submitList(events)
                    binding.emptyText.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Pick a date")
            .setSelection(MaterialDatePicker.todayInUtcMilliseconds())
            .build()
        picker.addOnPositiveButtonClickListener { dateUtcMs -> pickTime(dateUtcMs) }
        picker.show(childFragmentManager, "date")
    }

    private fun pickTime(dateUtcMs: Long) {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(7)
            .setMinute(0)
            .setTitleText("Pick a time")
            .build()
        picker.addOnPositiveButtonClickListener {
            // Combine the UTC date with local hour/minute into a local epoch.
            val cal = Calendar.getInstance()
            val dateCal = Calendar.getInstance().apply { timeInMillis = dateUtcMs }
            cal.set(Calendar.YEAR, dateCal.get(Calendar.YEAR))
            cal.set(Calendar.MONTH, dateCal.get(Calendar.MONTH))
            cal.set(Calendar.DAY_OF_MONTH, dateCal.get(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.HOUR_OF_DAY, picker.hour)
            cal.set(Calendar.MINUTE, picker.minute)
            cal.set(Calendar.SECOND, 0)
            showDetails(cal.timeInMillis)
        }
        picker.show(childFragmentManager, "time")
    }

    private fun showDetails(startMs: Long) {
        val dialogBinding = DialogAddEventBinding.inflate(layoutInflater)
        dialogBinding.typeWorkout.isChecked = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.schedule_add_event)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val title = dialogBinding.titleInput.text?.toString()?.ifBlank { "Activity" } ?: "Activity"
                val type = when (dialogBinding.typeChips.checkedChipId) {
                    R.id.typeFast -> EventType.FAST
                    R.id.typeMeal -> EventType.MEAL
                    R.id.typeOther -> EventType.OTHER
                    else -> EventType.WORKOUT
                }
                val durationMin = dialogBinding.durationInput.text?.toString()?.toIntOrNull() ?: 45
                val endMs = startMs + durationMin * 60_000L
                val push = dialogBinding.pushToCalendarSwitch.isChecked
                viewModel.addEvent(title, type, startMs, endMs, push) { msg, movedCount ->
                    showResult(msg, movedCount)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showScrollable(dialogBinding.root)
    }

    /**
     * Shows the outcome of a sync/add. When the auto-rescheduler moved one or more activities
     * around calendar conflicts, offers a one-tap Undo.
     */
    private fun showResult(message: String, movedCount: Int) {
        val view = _binding?.root ?: return
        if (movedCount > 0) {
            Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) {
                    viewModel.undoReschedule {
                        _binding?.root?.let { Snackbar.make(it, R.string.schedule_reschedule_undone, Snackbar.LENGTH_SHORT).show() }
                    }
                }
                .show()
        } else {
            Snackbar.make(view, message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(id: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Remove event?")
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.delete(id) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
