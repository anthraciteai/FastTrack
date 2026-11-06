package ai.anthracite.fasttrack.ui.water

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentWaterBinding
import ai.anthracite.fasttrack.ui.common.LogEntryAdapter
import ai.anthracite.fasttrack.ui.common.LogRow
import ai.anthracite.fasttrack.ui.common.showScrollable
import ai.anthracite.fasttrack.util.TimeFmt
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class WaterFragment : Fragment() {

    private var _binding: FragmentWaterBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WaterViewModel by viewModels()
    private lateinit var adapter: LogEntryAdapter
    private var unitSystem: UnitSystem = UnitSystem.IMPERIAL

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWaterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            arguments?.getLong(ARG_DAY_MS)?.takeIf { it > 0 }?.let { viewModel.setInitialDay(it) }
        }

        adapter = LogEntryAdapter { id -> viewModel.delete(id) }
        binding.logList.layoutManager = LinearLayoutManager(requireContext())
        binding.logList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.prevDay.setOnClickListener { viewModel.goPrevDay() }
        binding.nextDay.setOnClickListener { viewModel.goNextDay() }

        // Quick-add amounts depend on unit system; labels set in render.
        binding.addSmall.setOnClickListener { addPreset(0) }
        binding.addMedium.setOnClickListener { addPreset(1) }
        binding.addLarge.setOnClickListener { addPreset(2) }
        binding.addCustom.setOnClickListener { showCustomDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun presetsMl(): List<Double> = when (unitSystem) {
        UnitSystem.IMPERIAL -> listOf(Units.flOzToMl(8.0), Units.flOzToMl(12.0), Units.flOzToMl(16.0))
        UnitSystem.METRIC -> listOf(250.0, 500.0, 750.0)
    }

    private fun addPreset(index: Int) {
        viewModel.add(presetsMl()[index])
    }

    private fun render(s: WaterUiState) {
        unitSystem = s.unitSystem

        binding.dayLabel.text = s.dayLabel
        binding.nextDay.isEnabled = !s.isToday
        binding.nextDay.alpha = if (s.isToday) 0.4f else 1f
        binding.prevDay.isEnabled = s.canGoBack
        binding.prevDay.alpha = if (s.canGoBack) 1f else 0.4f
        binding.logSectionTitle.text = if (s.isToday) getString(R.string.dash_today) else s.dayLabel

        val pct = if (s.goalMl > 0) (s.totalMl / s.goalMl).toFloat() else 0f
        binding.ring.progress = pct
        binding.amountText.text = Units.volumeLabel(s.totalMl, s.unitSystem)
        binding.goalText.text = "of ${Units.volumeLabel(s.goalMl, s.unitSystem)} goal"

        val labels = when (s.unitSystem) {
            UnitSystem.IMPERIAL -> listOf("8 oz", "12 oz", "16 oz")
            UnitSystem.METRIC -> listOf("250 ml", "500 ml", "750 ml")
        }
        binding.addSmall.text = labels[0]
        binding.addMedium.text = labels[1]
        binding.addLarge.text = labels[2]

        val badge = ContextCompat.getColor(requireContext(), R.color.accent_water_container)
        val icon = ContextCompat.getColor(requireContext(), R.color.accent_water)
        adapter.submitList(s.logs.map {
            LogRow(
                id = it.id,
                icon = R.drawable.ic_water,
                badgeTint = badge,
                iconTint = icon,
                title = Units.volumeLabel(it.amountMl, s.unitSystem),
                subtitle = TimeFmt.clock(it.epochMs),
                value = ""
            )
        })
        binding.emptyText.visibility = if (s.logs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showCustomDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = if (unitSystem == UnitSystem.IMPERIAL) "Ounces" else "Milliliters"
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.water_add_custom)
            .setView(input)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val value = input.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val ml = if (unitSystem == UnitSystem.IMPERIAL) Units.flOzToMl(value) else value
                viewModel.add(ml)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showScrollable(input)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_DAY_MS = "dayMs"
    }
}
