package ai.anthracite.fasttrack.ui.exercise

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.WorkoutCalories
import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.data.local.WorkoutSession
import ai.anthracite.fasttrack.databinding.DialogAddExerciseBinding
import ai.anthracite.fasttrack.databinding.FragmentExerciseBinding
import ai.anthracite.fasttrack.ui.common.LogEntryAdapter
import ai.anthracite.fasttrack.ui.common.LogRow
import ai.anthracite.fasttrack.session.LiveSessions
import ai.anthracite.fasttrack.session.WorkoutTracker
import ai.anthracite.fasttrack.ui.common.showScrollable
import ai.anthracite.fasttrack.util.TimeFmt
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ExerciseFragment : Fragment() {

    private var _binding: FragmentExerciseBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExerciseViewModel by viewModels()
    private lateinit var adapter: LogEntryAdapter
    private var unitSystem: UnitSystem = UnitSystem.IMPERIAL
    private var weightKg: Double = 80.0
    private var liveSession: WorkoutSession? = null

    private val ticker = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null

    private val activityTypes get() = LiveSessions.activityTypes

    /** A distance activity waiting on the location-permission prompt before it starts. */
    private var pendingDistanceType: String? = null

    private val locationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        val type = pendingDistanceType ?: return@registerForActivityResult
        pendingDistanceType = null
        if (!granted) {
            Snackbar.make(
                binding.root,
                "No location access — $type will still time, just without distance.",
                Snackbar.LENGTH_LONG
            ).show()
        }
        beginActivity(type)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExerciseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = LogEntryAdapter { id -> viewModel.delete(id) }
        binding.activityList.layoutManager = LinearLayoutManager(requireContext())
        binding.activityList.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.startButton.setOnClickListener { chooseTypeThenStart() }
        binding.stopButton.setOnClickListener { stopLiveSession() }
        binding.logButton.setOnClickListener { showManualDialog() }
        binding.trendsCard.setOnClickListener {
            (activity as? ai.anthracite.fasttrack.MainActivity)?.selectTab(R.id.trendsFragment)
        }
        binding.sourceCard.setOnClickListener {
            Snackbar.make(binding.root, "Manage connections in Profile ▸ Connections", Snackbar.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.activeWorkout) { state, live ->
                    state to live
                }.collect { (state, live) -> render(state, live) }
            }
        }
    }

    private fun render(s: ExerciseUiState, live: WorkoutSession?) {
        unitSystem = s.unitSystem
        weightKg = s.weightKg
        liveSession = live
        binding.minutesValue.text = s.minutes.toString()
        binding.caloriesValue.text = s.calories.toString()
        binding.sourceStatusText.text = s.sourceStatus

        val running = live != null
        binding.liveSessionCard.visibility = if (running) View.VISIBLE else View.GONE
        binding.startButton.visibility = if (running) View.GONE else View.VISIBLE
        if (running) {
            binding.liveType.text = live!!.type
            restartTicker()
        } else {
            stopTicker()
        }

        adapter.submitList(s.logs.map {
            val src = runCatching { DataSource.valueOf(it.source) }.getOrDefault(DataSource.MANUAL)
            val srcLabel = when (src) {
                DataSource.GARMIN -> "Garmin"
                DataSource.HEALTH_CONNECT -> "Health Connect"
                DataSource.MANUAL -> "Manual"
            }
            val distance = it.distanceMeters?.let { d -> " · ${Units.distanceLabel(d, s.unitSystem)}" } ?: ""
            LogRow(
                id = it.id,
                icon = R.drawable.ic_exercise,
                badgeTint = ContextCompat.getColor(requireContext(), R.color.accent_exercise_container),
                iconTint = ContextCompat.getColor(requireContext(), R.color.accent_exercise),
                title = it.type,
                subtitle = "${it.durationMin} min$distance · $srcLabel",
                value = "${it.calories} kcal"
            )
        })
        binding.emptyText.visibility = if (s.logs.isEmpty() && !running) View.VISIBLE else View.GONE
    }

    private fun chooseTypeThenStart() {
        val types = activityTypes.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exercise_start_activity)
            .setItems(types) { _, which ->
                val type = types[which]
                // GPS activities need location access; ask first, then start either way.
                if (WorkoutTracker.tracksDistance(type) && !hasLocationPermission()) {
                    pendingDistanceType = type
                    locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else {
                    beginActivity(type)
                }
            }
            .show()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun beginActivity(type: String) {
        viewModel.startActivity(type) {
            Snackbar.make(binding.root, "$type started", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun stopLiveSession() {
        viewModel.stopActivity { log ->
            val distance = log.distanceMeters?.takeIf { it > 0 }
                ?.let { " · ${Units.distanceLabel(it, unitSystem)}" }.orEmpty()
            Snackbar.make(
                binding.root,
                "${log.type} · ${log.durationMin} min$distance · ${log.calories} kcal",
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    private fun restartTicker() {
        stopTicker()
        val session = liveSession ?: return
        val runnable = object : Runnable {
            override fun run() {
                val b = _binding ?: return
                val elapsed = System.currentTimeMillis() - session.startEpochMs
                b.liveTimer.text = TimeFmt.hms(elapsed)
                val kcal = WorkoutCalories.estimateElapsed(session.type, elapsed, weightKg)
                b.liveCalories.text = if (kcal > 0) "~$kcal kcal" else "Workout session"
                updateLiveDistance(elapsed)
                ticker.postDelayed(this, 1000)
            }
        }
        tickRunnable = runnable
        ticker.post(runnable)
    }

    /** Live GPS distance + pace on the active-workout card, driven by [WorkoutTracker]. */
    private fun updateLiveDistance(elapsedMs: Long) {
        val b = _binding ?: return
        val track = WorkoutTracker.state.value
        if (track == null || !WorkoutTracker.tracksDistance(track.type)) {
            b.liveDistance.visibility = View.GONE
            return
        }
        b.liveDistance.visibility = View.VISIBLE
        if (!track.hasFix) {
            b.liveDistance.text = "Acquiring GPS…"
            return
        }
        val distanceLabel = Units.distanceLabel(track.distanceMeters, unitSystem)
        val durationMin = (elapsedMs / 60_000L).toInt()
        val pace = Units.paceMinPerUnit(track.distanceMeters, durationMin, unitSystem)
            ?.let { " · " + Units.paceLabel(it, unitSystem) }
            .orEmpty()
        b.liveDistance.text = "$distanceLabel$pace"
    }

    private fun stopTicker() {
        tickRunnable?.let { ticker.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun showManualDialog(prefillType: String? = null) {
        val dialogBinding = DialogAddExerciseBinding.inflate(layoutInflater)
        dialogBinding.typeInput.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, activityTypes)
        )
        dialogBinding.typeInput.setText(prefillType ?: activityTypes.first(), false)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.exercise_log_manual)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val type = dialogBinding.typeInput.text?.toString()?.ifBlank { "Workout" } ?: "Workout"
                val duration = dialogBinding.durationInput.text?.toString()?.toIntOrNull() ?: 0
                val calories = dialogBinding.caloriesInput.text?.toString()?.toIntOrNull() ?: 0
                val distanceInput = dialogBinding.distanceInput.text?.toString()?.toDoubleOrNull()
                val distanceMeters = distanceInput?.let {
                    if (unitSystem == UnitSystem.IMPERIAL) it * 1609.344 else it * 1000.0
                }
                val avgHr = dialogBinding.avgHrInput.text?.toString()?.toIntOrNull()
                viewModel.logManual(type, duration, calories, distanceMeters, avgHr)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .showScrollable(dialogBinding.root)
    }

    override fun onDestroyView() {
        stopTicker()
        _binding = null
        super.onDestroyView()
    }
}
