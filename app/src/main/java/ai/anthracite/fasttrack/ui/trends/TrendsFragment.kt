package ai.anthracite.fasttrack.ui.trends

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.DayRecap
import ai.anthracite.fasttrack.databinding.FragmentTrendsBinding
import ai.anthracite.fasttrack.ui.common.GarminSyncPrompt
import ai.anthracite.fasttrack.ui.common.bindRecapStats
import ai.anthracite.fasttrack.ui.widget.TrendChartView
import android.content.res.ColorStateList
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
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class TrendsFragment : Fragment() {

    private var _binding: FragmentTrendsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrendsViewModel by viewModels()
    private val app get() = requireContext().applicationContext as FastTrackApp

    private var activitiesShown: List<String> = emptyList()
    private var syncing = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrendsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.inflateMenu(R.menu.trends_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_sync) {
                GarminSyncPrompt.showHowTo(requireContext()) { triggerSync() }
                true
            } else {
                false
            }
        }

        binding.bestStat.statIcon.setImageResource(R.drawable.ic_trophy)
        binding.avgStat.statIcon.setImageResource(R.drawable.ic_exercise)
        binding.trendStat.statIcon.setImageResource(R.drawable.ic_flame)

        binding.groupToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.selectGroupBy(
                if (checkedId == R.id.groupWeek) GroupBy.WEEK else GroupBy.SESSION
            )
        }

        buildMetricChips()

        binding.finishWorkoutBtn.setOnClickListener {
            viewModel.finishActiveWorkout { log ->
                if (_binding != null) {
                    snack(getString(R.string.workout_finished, log.type, log.durationMin))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchMetricsWithData.collect { renderWatchAvailability(it) }
            }
        }

        // Keep the active-workout flow hot and re-render whenever it appears/disappears.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeWorkout.collect { renderActiveWorkout() }
            }
        }

        // Tick the live-workout card every second so its elapsed timer stays current.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    renderActiveWorkout()
                    delay(1_000)
                }
            }
        }
    }

    private fun renderActiveWorkout() {
        val session = viewModel.activeWorkout.value
        if (session == null) {
            binding.activeWorkoutCard.visibility = View.GONE
            return
        }
        binding.activeWorkoutCard.visibility = View.VISIBLE
        val elapsedSec = ((System.currentTimeMillis() - session.startEpochMs) / 1000L)
            .coerceAtLeast(0L)
        val clock = String.format(Locale.US, "%d:%02d", elapsedSec / 60, elapsedSec % 60)
        binding.activeWorkoutTitle.text = "${session.type} · $clock"
    }

    private fun buildMetricChips() {
        binding.metricChips.removeAllViews()
        TrendMetric.entries.forEach { metric ->
            val chip = Chip(requireContext()).apply {
                text = metric.label
                isCheckable = true
                isCheckedIconVisible = true
                tag = metric
                setOnClickListener { viewModel.selectMetric(metric) }
            }
            binding.metricChips.addView(chip)
        }
    }

    /**
     * Without a connected watch, Body Battery / Resting HR / Stress can never fill in, so their chips,
     * the Garmin sync action and the recovery note are hidden instead of showing permanent blanks.
     */
    private fun renderWatchAvailability(withData: Set<TrendMetric>) {
        val connected = app.garminClient.isConfigured
        for (i in 0 until binding.metricChips.childCount) {
            val chip = binding.metricChips.getChildAt(i) as Chip
            val metric = chip.tag as TrendMetric
            chip.visibility =
                if (!metric.needsWatch || connected || metric in withData) View.VISIBLE else View.GONE
        }
        if (!connected && viewModel.state.value.metric.let { it.needsWatch && it !in withData }) {
            viewModel.selectMetric(TrendMetric.DISTANCE)
        }
        binding.toolbar.menu.findItem(R.id.action_sync)?.isVisible = connected
        val showNote = connected || withData.isNotEmpty()
        binding.recoveryNoteTitle.visibility = if (showNote) View.VISIBLE else View.GONE
        binding.recoveryNote.visibility = if (showNote) View.VISIBLE else View.GONE
    }

    private fun rebuildActivityChips(activities: List<String>) {
        activitiesShown = activities
        binding.activityChips.removeAllViews()
        activities.forEach { activity ->
            val chip = Chip(requireContext()).apply {
                text = activity
                isCheckable = true
                isCheckedIconVisible = true
                tag = activity
                setOnClickListener { viewModel.selectActivity(activity) }
            }
            binding.activityChips.addView(chip)
        }
    }

    private fun render(s: TrendsUiState) {
        renderRecap(s.recap)

        // Metric chip selection
        for (i in 0 until binding.metricChips.childCount) {
            val chip = binding.metricChips.getChildAt(i) as Chip
            chip.isChecked = chip.tag == s.metric
        }

        // Activity filter
        binding.activityScroll.visibility = if (s.showActivityFilter) View.VISIBLE else View.GONE
        if (s.showActivityFilter) {
            if (s.activities != activitiesShown) rebuildActivityChips(s.activities)
            for (i in 0 until binding.activityChips.childCount) {
                val chip = binding.activityChips.getChildAt(i) as Chip
                chip.isChecked = chip.tag == s.activity
            }
        }

        // Group toggle
        binding.groupToggle.visibility = if (s.showGroupToggle) View.VISIBLE else View.GONE
        val targetGroupId = if (s.groupBy == GroupBy.WEEK) R.id.groupWeek else R.id.groupSession
        if (binding.groupToggle.checkedButtonId != targetGroupId) {
            binding.groupToggle.check(targetGroupId)
        }

        binding.chartCaption.text = s.caption

        if (s.emptyMessage != null) {
            binding.chart.visibility = View.GONE
            binding.chartEmpty.visibility = View.VISIBLE
            binding.chartEmpty.text = s.emptyMessage
        } else {
            binding.chart.visibility = View.VISIBLE
            binding.chartEmpty.visibility = View.GONE
            binding.chart.mode =
                if (s.mode == ChartMode.BAR) TrendChartView.Mode.BAR else TrendChartView.Mode.LINE
            binding.chart.accent = ContextCompat.getColor(requireContext(), s.accentColorRes)
            binding.chart.setData(s.values, s.labels)
        }

        val accent = ContextCompat.getColor(requireContext(), s.accentColorRes)
        binding.bestStat.statIcon.imageTintList = ColorStateList.valueOf(accent)
        binding.bestStat.statValue.text = s.summaryBest
        binding.bestStat.statLabel.text = s.summaryBestLabel
        binding.avgStat.statValue.text = s.summaryAvg
        binding.avgStat.statLabel.text = "Average"

        if (s.hasTrend) {
            val up = s.trendImproved
            binding.trendStat.statValue.text = (if (up) "▲ " else "▼ ") + "${s.trendPct}%"
            binding.trendStat.statLabel.text = if (up) "improving" else "needs work"
            val tint = ContextCompat.getColor(
                requireContext(),
                if (up) R.color.accent_nutrition else R.color.macro_protein
            )
            binding.trendStat.statIcon.imageTintList = ColorStateList.valueOf(tint)
            binding.trendStat.statValue.setTextColor(tint)
        } else {
            binding.trendStat.statValue.text = "–"
            binding.trendStat.statLabel.text = "trend"
            binding.trendStat.statValue.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.md_on_surface)
            )
        }

    }

    private fun renderRecap(r: DayRecap) {
        if (!r.hasData) {
            binding.recapCard.visibility = View.GONE
            return
        }
        binding.recapCard.visibility = View.VISIBLE
        binding.recapDate.text = r.dateLabel
        binding.recapHeadline.text = r.headline
        binding.recapSummary.text = r.summary
        binding.recapStats.bindRecapStats(r.stats)
    }

    override fun onResume() {
        super.onResume()
        // Opening Movement quietly pulls the latest Garmin data — no "welcome back" popup.
        triggerSync(silent = true)
    }

    /**
     * Pulls the latest Garmin data. [silent] skips the snackbars so the automatic sync on entering
     * the Movement tab stays unobtrusive; the manual Sync button passes silent = false.
     */
    private fun triggerSync(silent: Boolean = false) {
        if (syncing) return
        if (!app.garminClient.isConfigured) {
            if (!silent) snack(getString(R.string.sync_not_connected))
            return
        }
        syncing = true
        if (!silent) snack(getString(R.string.sync_in_progress))
        viewLifecycleOwner.lifecycleScope.launch {
            app.syncGarminNow()
            syncing = false
            if (!silent && _binding != null) snack(getString(R.string.sync_done))
        }
    }

    private fun snack(msg: String) =
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
