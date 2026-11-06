package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentNutritionBinding
import ai.anthracite.fasttrack.nutrition.export.ExportRange
import ai.anthracite.fasttrack.ui.common.LogEntryAdapter
import ai.anthracite.fasttrack.ui.common.LogRow
import ai.anthracite.fasttrack.ui.common.ShareJson
import ai.anthracite.fasttrack.util.TimeFmt
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class NutritionFragment : Fragment() {

    private var _binding: FragmentNutritionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NutritionViewModel by viewModels()
    private lateinit var adapter: LogEntryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNutritionBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** Maps a flat food-log row id to the rich meal entry it belongs to (null = plain quick add). */
    private var entryIdByRow: Map<Long, String?> = emptyMap()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            arguments?.getLong(ARG_DAY_MS)?.takeIf { it > 0 }?.let { viewModel.setInitialDay(it) }
        }

        adapter = LogEntryAdapter(
            onDelete = { id ->
                val entryId = entryIdByRow[id]
                if (entryId != null) viewModel.deleteEntry(entryId) else viewModel.delete(id)
            },
            onClick = { id ->
                entryIdByRow[id]?.let { openEditor(it) }
            }
        )
        binding.foodList.layoutManager = LinearLayoutManager(requireContext())
        binding.foodList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.toolbar.inflateMenu(R.menu.menu_nutrition)
        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.action_export_food) { chooseExportRange(); true } else false
        }
        binding.addFoodFab.setOnClickListener { openEditor(null) }
        binding.prevDay.setOnClickListener { viewModel.goPrevDay() }
        binding.nextDay.setOnClickListener { viewModel.goNextDay() }

        binding.proteinBar.macroLabel.text = getString(R.string.nutrition_protein)
        binding.carbsBar.macroLabel.text = getString(R.string.nutrition_carbs)
        binding.fatBar.macroLabel.text = getString(R.string.nutrition_fat)
        binding.proteinBar.macroBar.setIndicatorColor(
            ContextCompat.getColor(requireContext(), R.color.macro_protein)
        )
        binding.carbsBar.macroBar.setIndicatorColor(
            ContextCompat.getColor(requireContext(), R.color.macro_carbs)
        )
        binding.fatBar.macroBar.setIndicatorColor(
            ContextCompat.getColor(requireContext(), R.color.macro_fat)
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(s: NutritionUiState) {
        binding.dayLabel.text = s.dayLabel
        binding.nextDay.isEnabled = !s.isToday
        binding.nextDay.alpha = if (s.isToday) 0.4f else 1f
        binding.prevDay.isEnabled = s.canGoBack
        binding.prevDay.alpha = if (s.canGoBack) 1f else 0.4f
        binding.addFoodFab.text = if (s.isToday) getString(R.string.nutrition_add_food)
            else "Add to ${s.dayLabel}"

        binding.calorieValue.text = s.calories.toString()
        binding.calorieRing.progress =
            if (s.calorieGoal > 0) (s.calories.toFloat() / s.calorieGoal) else 0f
        val left = (s.calorieGoal - s.calories).coerceAtLeast(0)
        binding.calorieGoalText.text = "$left kcal left of ${s.calorieGoal}"

        bindMacro(binding.proteinBar, s.proteinG, s.proteinGoal)
        bindMacro(binding.carbsBar, s.carbsG, s.carbsGoal)
        bindMacro(binding.fatBar, s.fatG, s.fatGoal)

        val badge = ContextCompat.getColor(requireContext(), R.color.accent_nutrition_container)
        val icon = ContextCompat.getColor(requireContext(), R.color.accent_nutrition)
        entryIdByRow = s.logs.associate { it.id to it.entryId }
        adapter.submitList(s.logs.map {
            LogRow(
                id = it.id,
                icon = R.drawable.ic_nutrition,
                badgeTint = badge,
                iconTint = icon,
                title = it.name,
                subtitle = "${it.mealType.lowercase().replaceFirstChar { c -> c.uppercase() }} · ${TimeFmt.clock(it.epochMs)}",
                value = "${it.calories} kcal"
            )
        })
        binding.emptyText.visibility = if (s.logs.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bindMacro(
        bar: ai.anthracite.fasttrack.databinding.PartialMacroBarBinding,
        value: Int,
        goal: Int
    ) {
        bar.macroValue.text = "$value / $goal g"
        bar.macroBar.setProgressCompat(
            if (goal > 0) (value.toFloat() / goal * 100).roundToInt().coerceIn(0, 100) else 0,
            true
        )
    }

    /** Let the user pick a time range, then export that slice of the food log as a JSON file. */
    private fun chooseExportRange() {
        val ranges = ExportRange.entries
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_choose_range)
            .setItems(ranges.map { it.label }.toTypedArray()) { _, which ->
                exportRange(ranges[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun exportRange(range: ExportRange) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (json, fileName) = runCatching { viewModel.buildFoodExport(range) }
                .getOrElse {
                    snack(getString(R.string.export_failed)); return@launch
                }
            // An export with no entries still serializes (empty "entries"); warn if nothing logged.
            if (!json.contains("\"id\"")) {
                snack(getString(R.string.export_empty))
                return@launch
            }
            ShareJson.share(requireContext(), json, fileName, getString(R.string.nutrition_export))
        }
    }

    private fun snack(message: String) =
        com.google.android.material.snackbar.Snackbar
            .make(binding.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
            .show()

    /** Open the multi-item meal editor to create a new meal (null) or edit an existing entry. */
    private fun openEditor(entryId: String?) {
        val args = Bundle().apply {
            putString(MealEditorFragment.ARG_ENTRY_ID, entryId)
            putLong(MealEditorFragment.ARG_DAY_MS, viewModel.selectedDayMs())
        }
        findNavController().navigate(R.id.action_nutrition_to_mealEditor, args)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ARG_DAY_MS = "dayMs"
    }
}
