package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.FastingGamification
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.health.FastingStages
import ai.anthracite.fasttrack.data.health.GamificationStats
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.databinding.DialogEditFastBinding
import ai.anthracite.fasttrack.ui.common.FastCelebration
import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.ui.checkin.PostFastCheckIn
import ai.anthracite.fasttrack.databinding.FragmentFastingBinding
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.util.TimeFmt
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private data class FastingSnapshot(
    val active: FastingSession?,
    val stats: GamificationStats,
    val protocol: FastingProtocol,
    val lastFinished: FastingSession?
)

class FastingFragment : Fragment() {

    private var _binding: FragmentFastingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FastingViewModel by viewModels()

    private val calendarAdapter = CalendarAdapter { cell ->
        if (cell.dayKey.isNotBlank()) openTimeEditor(cell.dayKey)
    }
    private val stageAdapter = FastingStageAdapter()
    private var lastStageIndex = Int.MIN_VALUE

    private val ticker = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var active: FastingSession? = null
    private var selected: FastingProtocol = FastingProtocol.SIXTEEN_EIGHT
    /** Most recent ended fast; used to count down the eating window to the next fast. */
    private var lastFinished: FastingSession? = null

    private val prefs by lazy { (requireContext().applicationContext as FastTrackApp).prefs }
    /** Fasts we've already celebrated, so the popup fires once per completed session. */
    private val celebratedSessionIds = mutableSetOf<Long>()
    /** True once the active fast has had time remaining, so we only celebrate a live crossing. */
    private var sawPositiveRemaining = false

    private var latestStats: GamificationStats = FastingGamification.compute(emptyList())
    private val shownMonth: Calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private val monthFmt = SimpleDateFormat("MMMM yyyy", Locale.US)
    private val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFastingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.calendarGrid.layoutManager = GridLayoutManager(requireContext(), 7)
        binding.calendarGrid.adapter = calendarAdapter
        binding.stageTimeline.adapter = stageAdapter

        // Stat tiles
        binding.streakStat.statIcon.setImageResource(R.drawable.ic_flame)
        binding.streakStat.statLabel.text = "day streak"
        binding.bestStat.statIcon.setImageResource(R.drawable.ic_trophy)
        binding.bestStat.statLabel.text = "best streak"
        binding.totalStat.statIcon.setImageResource(R.drawable.ic_check)
        binding.totalStat.statLabel.text = "fasts done"

        binding.protocolCard.setOnClickListener {
            findNavController().navigate(R.id.action_fasting_to_protocol)
        }
        binding.actionButton.setOnClickListener {
            if (active == null) {
                viewModel.start()
            } else {
                viewModel.end { ended ->
                    val act = activity ?: return@end
                    // Already celebrated live when the goal was crossed → go straight to the check-in.
                    if (celebratedSessionIds.add(ended.id)) PostFastCheckIn.afterEnded(act, ended)
                    else if (FastHistory.qualifiesForCheckIn(ended)) PostFastCheckIn.prompt(act, ended)
                }
            }
        }
        // These buttons edit the *active* fast (which may have started yesterday), not "today".
        binding.startedAtText.setOnClickListener { openTimeEditor(editorDayKey()) }
        binding.editTimesButton.setOnClickListener { openTimeEditor(editorDayKey()) }
        binding.prevMonth.setOnClickListener { shownMonth.add(Calendar.MONTH, -1); renderCalendar() }
        binding.nextMonth.setOnClickListener { shownMonth.add(Calendar.MONTH, 1); renderCalendar() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.readiness, viewModel.activeFast) { r, a -> r to a }.collect { (r, a) ->
                    val hint = when {
                        r == null || a == null -> null
                        r.level == ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.LOW &&
                            a.goalHours > (r.suggestedMaxFastHours ?: Double.MAX_VALUE) ->
                            "Recovery ${r.score} · Low today — consider ending around ${r.suggestedMaxFastHours!!.toInt()}h instead of ${a.goalHours.toInt()}h."
                        r.level == ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.LOW ->
                            "Recovery ${r.score} · ${r.fastingAdvice}"
                        r.level == ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.HIGH ->
                            "Recovery ${r.score} · ${r.fastingAdvice}"
                        else -> null
                    }
                    binding.recoveryHint.visibility = if (hint == null) View.GONE else View.VISIBLE
                    binding.recoveryHint.text = hint.orEmpty()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.activeFast,
                    viewModel.stats,
                    viewModel.selectedProtocol,
                    viewModel.lastFinishedFast
                ) { a, s, p, last -> FastingSnapshot(a, s, p, last) }.collect { snap ->
                    val a = snap.active
                    val s = snap.stats
                    val p = snap.protocol
                    active = a
                    selected = p
                    lastFinished = snap.lastFinished
                    latestStats = s
                    binding.protocolValue.text = "${p.displayName} · ${p.subtitle}"
                    binding.actionButton.text =
                        getString(if (a == null) R.string.fasting_start else R.string.fasting_stop)
                    binding.startedAtText.text = if (a != null) {
                        "Started ${TimeFmt.dateTime(a.startEpochMs)} · tap to edit"
                    } else {
                        "Tap a calendar day to set start and stop"
                    }

                    binding.streakStat.statValue.text = s.currentStreak.toString()
                    binding.bestStat.statValue.text = s.longestStreak.toString()
                    binding.totalStat.statValue.text = s.totalCompleted.toString()

                    renderCalendar()
                    restartTicker()
                }
            }
        }
    }

    private fun renderCalendar() {
        binding.monthLabel.text = monthFmt.format(shownMonth.time)
        val cells = buildCells(shownMonth)
        calendarAdapter.submit(cells)
    }

    private fun buildCells(month: Calendar): List<DayCell> {
        val cal = month.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val leadingBlanks = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val todayKey = dayKeyFmt.format(Calendar.getInstance().time)

        val cells = ArrayList<DayCell>()
        repeat(leadingBlanks) { cells.add(DayCell(0)) }
        for (day in 1..daysInMonth) {
            val dayCal = cal.clone() as Calendar
            dayCal.set(Calendar.DAY_OF_MONTH, day)
            val key = dayKeyFmt.format(dayCal.time)
            cells.add(
                DayCell(
                    dayNumber = day,
                    dayKey = key,
                    state = FastingGamification.dayState(key, latestStats),
                    isToday = key == todayKey
                )
            )
        }
        return cells
    }

    private fun restartTicker() {
        tickRunnable?.let { ticker.removeCallbacks(it) }
        val a = active
        if (a == null) {
            binding.currentStageCard.visibility = View.GONE
            updateStages(null)

            // If we just ended a fast, the eating window runs for (24 - fastHours). Count down to
            // the next fast relative to when *this* fast ended — not the fixed daily reminder time.
            val eatingWindowMs = ((24.0 - selected.fastHours) * 3_600_000L).toLong()
            val lastEnd = lastFinished?.endEpochMs
            val nextFastStart = lastEnd?.let { it + eatingWindowMs }
            if (nextFastStart != null && eatingWindowMs > 0L && System.currentTimeMillis() < nextFastStart) {
                val idle = object : Runnable {
                    override fun run() {
                        val remaining = nextFastStart - System.currentTimeMillis()
                        if (remaining <= 0L) {
                            // Window closed — flip to the "ready to fast" state.
                            restartTicker()
                            return
                        }
                        binding.statusLabel.text = getString(R.string.fasting_eating_window)
                        binding.timerText.text = TimeFmt.hms(remaining)
                        binding.goalText.text = "Next fast ~${TimeFmt.clock(nextFastStart)}"
                        val elapsed = (eatingWindowMs - remaining).toFloat()
                        binding.ring.progress = (elapsed / eatingWindowMs).coerceIn(0f, 1f)
                        ticker.postDelayed(this, 1000)
                    }
                }
                tickRunnable = idle
                ticker.post(idle)
                return
            }

            binding.ring.progress = 0f
            binding.statusLabel.text = "Ready to fast"
            binding.timerText.text = "${selected.fastHours.toInt()}h"
            val minOfDay = prefs.dailyFastStartMinOfDay
            val now = Calendar.getInstance()
            val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            binding.goalText.text = if (prefs.dailyFastReminderEnabled && minOfDay in 0..1439 && minOfDay > nowMin) {
                "Next fast ~${dailyTimeLabel(minOfDay)}"
            } else {
                selected.subtitle
            }
            return
        }
        sawPositiveRemaining = false
        val goalMs = (a.goalHours * 3_600_000L).toLong()
        binding.goalText.text = "Goal ${a.goalHours.toInt()}h"
        binding.currentStageCard.visibility = View.VISIBLE
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - a.startEpochMs
                val remaining = goalMs - elapsed
                if (remaining > 0) {
                    sawPositiveRemaining = true
                    binding.statusLabel.text = getString(R.string.fasting_remaining)
                    binding.timerText.text = TimeFmt.hms(remaining)
                } else {
                    // Celebrate the moment the goal is crossed live (not when re-opening a done fast).
                    if (sawPositiveRemaining && celebratedSessionIds.add(a.id)) {
                        FastCelebration.show(requireContext(), elapsed)
                    }
                    binding.statusLabel.text = getString(R.string.fasting_elapsed)
                    binding.timerText.text = TimeFmt.hms(elapsed)
                }
                binding.ring.progress = (elapsed.toFloat() / goalMs).coerceIn(0f, 1f)
                updateStages(elapsed / 3_600_000.0)
                ticker.postDelayed(this, 1000)
            }
        }
        tickRunnable = runnable
        ticker.post(runnable)
    }

    /** "8:00 PM" for a minutes-past-midnight value. */
    private fun dailyTimeLabel(minOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minOfDay / 60)
            set(Calendar.MINUTE, minOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return TimeFmt.clock(cal.timeInMillis)
    }

    /**
     * Refreshes the live "happening now" card and the timeline. [elapsedHours] is null when no
     * fast is active (timeline then shows every stage as upcoming).
     */
    private fun updateStages(elapsedHours: Double?) {
        val currentIndex = elapsedHours?.let { FastingStages.indexFor(it) } ?: -1

        // The live card updates only on stage change (its content is otherwise static) plus the
        // progress bar / countdown which we refresh every tick.
        if (elapsedHours != null) {
            val stage = FastingStages.current(elapsedHours)
            if (currentIndex != lastStageIndex) {
                val color = androidx.core.content.ContextCompat.getColor(requireContext(), stage.colorRes)
                binding.currentStageIcon.setImageResource(stage.iconRes)
                binding.currentStageIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
                binding.currentStageIconWrap.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(withAlpha(color, 0.20f))
                binding.currentStageTitle.text = stage.title
                binding.currentStageBody.text = stage.bodyState
                binding.currentStageBody.setTextColor(color)
                binding.currentStageDesc.text = stage.description
                binding.currentStageProgress.setIndicatorColor(color)
            }
            binding.currentStageProgress.progress =
                (FastingStages.progressWithinStage(elapsedHours) * 100).toInt()
            val next = FastingStages.next(elapsedHours)
            binding.currentStageNext.text = if (next != null) {
                val remainingMs = (next.startHour * 3_600_000L - elapsedHours * 3_600_000L).toLong()
                "${TimeFmt.hmShort(remainingMs)} until ${next.title}"
            } else {
                "You've reached the final tracked stage \uD83C\uDF89"
            }
        }

        // Re-submit the timeline only when the active stage changes, to avoid churn every second.
        if (currentIndex != lastStageIndex) {
            lastStageIndex = currentIndex
            val rows = FastingStages.ALL.mapIndexed { i, stage ->
                val state = when {
                    currentIndex < 0 -> StageUiState.UPCOMING
                    i < currentIndex -> StageUiState.REACHED
                    i == currentIndex -> StageUiState.CURRENT
                    else -> StageUiState.UPCOMING
                }
                StageRow(
                    stage = stage,
                    state = state,
                    isFirst = i == 0,
                    isLast = i == FastingStages.ALL.lastIndex
                )
            }
            stageAdapter.submitList(rows)
        }
    }

    /** Day key to seed the editor with: the active fast's start day, else today. */
    private fun editorDayKey(): String =
        active?.let { DayKeys.of(it.startEpochMs) } ?: DayKeys.today()

    /**
     * Fast-time editor. Both Start and Stop open a calendar first, then a clock, so you can log a
     * fast that began on a previous day (not just today). It's seeded from the tapped/active day,
     * and a live duration plus a "still fasting" switch keep it obvious. Save is blocked if the
     * stop isn't after the start.
     */
    private fun openTimeEditor(dayKey: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val existing = viewModel.sessionForDay(dayKey)
            val runningElsewhere = active != null && active?.id != existing?.id
            val now = System.currentTimeMillis()
            val goalMs = ((existing?.goalHours ?: selected.fastHours) * 3_600_000L).toLong()
            fun defaultEnd(start: Long) = minOf(start + goalMs, System.currentTimeMillis())
            // A new log for this day: fasts count toward the day they end, so default to the
            // overnight fast that ended on it (8 PM the evening before → goal hours later).
            var startMs = existing?.startEpochMs ?: (DayKeys.startMs(dayKey) - 4L * 3_600_000L)
            var endMs = if (existing != null) existing.endEpochMs else defaultEnd(startMs)
            val b = DialogEditFastBinding.inflate(layoutInflater)
            val dayFmt = SimpleDateFormat("EEEE, MMMM d", Locale.US)

            fun render() {
                val e = endMs
                b.dayLabel.text = if (e != null) "Counts toward ${dayFmt.format(Date(e))}" else "Fast in progress"
                b.startButton.text = TimeFmt.dateTime(startMs)
                b.endButton.isEnabled = e != null
                b.endButton.alpha = if (e != null) 1f else 0.5f
                b.endButton.text = e?.let { TimeFmt.dateTime(it) } ?: "Not set"
                b.durationLabel.text = if (e != null) formatDuration(e - startMs) else "In progress"
            }
            b.stillGoingSwitch.isChecked = endMs == null
            // Only one fast can be running; a past fast can't become "still going" alongside it.
            b.stillGoingSwitch.visibility = if (runningElsewhere) View.GONE else View.VISIBLE
            render()

            b.startButton.setOnClickListener {
                pickDateTime(startMs) { picked ->
                    startMs = picked
                    endMs?.let { if (it <= startMs) endMs = defaultEnd(startMs) }
                    render()
                }
            }
            b.endButton.setOnClickListener {
                pickDateTime(endMs ?: defaultEnd(startMs)) { picked ->
                    endMs = picked
                    render()
                }
            }
            b.stillGoingSwitch.setOnCheckedChangeListener { _, checked ->
                endMs = if (checked) null else defaultEnd(startMs)
                render()
            }

            val builder = MaterialAlertDialogBuilder(requireContext())
                .setTitle(if (existing == null) "Log a fast" else "Edit fast")
                .setView(b.root)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save) { _, _ ->
                    viewModel.saveTimes(existing, startMs, endMs, selected) { message ->
                        context?.let {
                            android.widget.Toast.makeText(it, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            if (existing != null) {
                builder.setNeutralButton("Delete") { _, _ -> confirmDelete(existing) }
            }
            builder.show()
        }
    }

    private fun confirmDelete(session: FastingSession) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete this fast?")
            .setMessage("${TimeFmt.dateTime(session.startEpochMs)} · ${
                session.endEpochMs?.let { formatDuration(it - session.startEpochMs) } ?: "in progress"
            }")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteFast(session) }
            .show()
    }

    /**
     * Pick a full date *and* time, seeded from [initialMs]. Shows a calendar first (so you can log
     * a fast that started on a previous day), then a clock, and returns the combined timestamp.
     * The calendar is capped at today and floored at the join date.
     */
    private fun pickDateTime(initialMs: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = initialMs }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                TimePickerDialog(
                    requireContext(),
                    { _, hour, minute ->
                        val picked = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onPicked(picked.timeInMillis)
                    },
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE),
                    false
                ).show()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            val prefs = (requireContext().applicationContext as FastTrackApp).prefs
            datePicker.minDate = DayKeys.startMs(prefs.joinedDayKey())
        }.show()
    }

    private fun formatDuration(ms: Long): String {
        val totalMin = (ms / 60_000L).toInt().coerceAtLeast(0)
        return "${totalMin / 60}h ${totalMin % 60}m"
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    override fun onDestroyView() {
        tickRunnable?.let { ticker.removeCallbacks(it) }
        tickRunnable = null
        _binding = null
        super.onDestroyView()
    }
}
