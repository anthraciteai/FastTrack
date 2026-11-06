package ai.anthracite.fasttrack.ui.settings

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.databinding.FragmentSettingsBinding
import ai.anthracite.fasttrack.integration.sleep.SleepEstimator
import ai.anthracite.fasttrack.notifications.ReminderScheduler
import ai.anthracite.fasttrack.util.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        prefs = Prefs(requireContext())
        val app = requireContext().applicationContext as FastTrackApp
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.waterSwitch.isChecked = prefs.waterReminderEnabled
        binding.fastingSwitch.isChecked = prefs.fastingRemindersEnabled
        binding.fastingTimerSwitch.isChecked = prefs.fastingOngoingEnabled
        binding.weeklyReviewSwitch.isChecked = prefs.weeklyReviewEnabled
        binding.workoutSwitch.isChecked = prefs.workoutRemindersEnabled
        binding.sleepSwitch.isChecked = prefs.sleepReminderEnabled
        binding.aiSwitch.isChecked = prefs.aiFeaturesEnabled
        // Snap stored value to the slider's 15-minute step within [30, 240].
        val snapped = (Math.round(prefs.waterIntervalMin / 15.0) * 15).toInt().coerceIn(30, 240)
        binding.intervalSlider.value = snapped.toFloat()
        updateIntervalLabel(snapped)

        binding.waterSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.waterReminderEnabled = checked
            ReminderScheduler.syncFromPrefs(requireContext())
        }
        binding.fastingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.fastingRemindersEnabled = checked
            resyncFastingAlerts()
        }
        binding.fastingTimerSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.fastingOngoingEnabled = checked
            resyncFastingAlerts()
        }
        binding.weeklyReviewSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.weeklyReviewEnabled = checked
            ReminderScheduler.syncFromPrefs(requireContext())
        }
        binding.workoutSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.workoutRemindersEnabled = checked
        }
        binding.sleepSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.sleepReminderEnabled = checked
            ReminderScheduler.syncFromPrefs(requireContext())
            if (checked && !SleepEstimator.hasUsageAccess(requireContext())) {
                promptUsageAccess()
            }
        }
        binding.aiSwitch.setOnCheckedChangeListener { _, checked ->
            app.setAiFeaturesEnabled(checked)
            if (!checked) {
                Snackbar.make(
                    binding.root,
                    "AI features hidden — meals are entered by hand",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }
        binding.intervalSlider.addOnChangeListener { _, value, _ ->
            val minutes = value.toInt()
            prefs.waterIntervalMin = minutes
            updateIntervalLabel(minutes)
            ReminderScheduler.syncFromPrefs(requireContext())
        }

        // Custom hydration goal (blank = auto, half body-weight in oz).
        if (prefs.waterGoalOverrideOz > 0) {
            binding.waterGoalInput.setText(prefs.waterGoalOverrideOz.toString())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = app.repository.getProfile()
            val autoOz = (GuidanceEngine.recommendedWaterMl(profile.weightKg) / GuidanceEngine.ML_PER_OZ).roundToInt()
            binding.waterGoalHint.text = "Leave blank for auto (half your body weight): ~$autoOz oz"
        }
        binding.waterGoalInput.doAfterTextChanged { text ->
            prefs.waterGoalOverrideOz = text?.toString()?.trim()?.toIntOrNull() ?: 0
        }

        binding.syncButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val result = app.syncClient.pushAll()
                binding.syncStatus.text = result.message
                Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binding.syncStatus.text = app.syncClient.status().detail
        }
    }

    /** Explain and offer to open the system Usage Access screen so sleep can be auto-estimated. */
    private fun promptUsageAccess() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sleep_usage_title)
            .setMessage(R.string.sleep_usage_body)
            .setPositiveButton(R.string.sleep_usage_open) { _, _ ->
                runCatching { startActivity(SleepEstimator.usageAccessSettingsIntent()) }
            }
            .setNegativeButton(R.string.sleep_usage_skip, null)
            .show()
    }

    private fun updateIntervalLabel(minutes: Int) {
        binding.intervalLabel.text = "Every ${minutes / 60}h ${minutes % 60}m"
    }

    private fun resyncFastingAlerts() {
        val app = requireActivity().application as FastTrackApp
        viewLifecycleOwner.lifecycleScope.launch {
            ai.anthracite.fasttrack.notifications.FastingMilestones.sync(app, app.repository.getActiveFast())
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
