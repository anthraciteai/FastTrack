package ai.anthracite.fasttrack.ui.log

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentLogHubBinding
import ai.anthracite.fasttrack.ui.common.WeightLogDialog
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class LogHubFragment : Fragment() {

    private var _binding: FragmentLogHubBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogHubBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.waterRow.setOnClickListener {
            findNavController().navigate(R.id.action_log_to_water)
        }
        binding.nutritionRow.setOnClickListener {
            findNavController().navigate(R.id.action_log_to_nutrition)
        }
        binding.exerciseRow.setOnClickListener {
            findNavController().navigate(R.id.action_log_to_exercise)
        }
        binding.weightRow.setOnClickListener { promptLogWeight() }
        binding.coachRow.setOnClickListener {
            // Coach is a top-level tab, so switch to it the same way the bottom nav does
            // (multiple-back-stack save/restore). Pushing it onto Log's stack via a plain action
            // corrupts the saved back stacks and leaves the Log tab showing Coach.
            val nav = findNavController()
            nav.navigate(
                R.id.coachFragment,
                null,
                NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(true)
                    .setPopUpTo(nav.graph.findStartDestination().id, inclusive = false, saveState = true)
                    .build()
            )
        }
    }

    /** Opens the weigh-in dialog seeded with the current weight, then saves + confirms. */
    private fun promptLogWeight() {
        val app = requireContext().applicationContext as FastTrackApp
        viewLifecycleOwner.lifecycleScope.launch {
            val profile = app.repository.getProfile()
            val unit = runCatching { UnitSystem.valueOf(profile.unitSystem) }
                .getOrDefault(UnitSystem.IMPERIAL)
            val lastLogged = app.repository.latestWeightKg()
            WeightLogDialog.show(requireContext(), unit, profile.weightKg, lastLogged) { kg ->
                viewLifecycleOwner.lifecycleScope.launch {
                    app.repository.addWeight(kg)
                    if (_binding != null) {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.weight_saved, Units.weightLabel(kg, unit)),
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
