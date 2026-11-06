package ai.anthracite.fasttrack.ui.dashboard

import ai.anthracite.fasttrack.MainActivity
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentDashboardBinding
import ai.anthracite.fasttrack.databinding.ItemDayRingBinding
import ai.anthracite.fasttrack.databinding.ItemDayStripBinding
import ai.anthracite.fasttrack.databinding.ItemMovementCategoryBinding
import ai.anthracite.fasttrack.data.health.FastingStages
import ai.anthracite.fasttrack.data.health.GoalProjection
import ai.anthracite.fasttrack.ui.common.WeightLogDialog
import ai.anthracite.fasttrack.util.TimeFmt
import ai.anthracite.fasttrack.util.Units
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()

    private val ticker = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var current: DashboardUiState = DashboardUiState()
    /** True while the user is arrowed back on a past day (read-only mode). */
    private var viewingPast = false
    private val celebratedSessionIds = mutableSetOf<Long>()
    /** Guards the once-per-launch daily-update popup check. */
    private var dailyUpdateHandled = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.greetingText.text = greetingForHour()

        binding.fastingActionButton.setOnClickListener {
            if (current.activeFast == null) {
                viewModel.startFast()
            } else {
                viewModel.endFast { ended ->
                    val act = activity ?: return@endFast
                    if (celebratedSessionIds.add(ended.id)) {
                        ai.anthracite.fasttrack.ui.checkin.PostFastCheckIn.afterEnded(act, ended)
                    }
                }
            }
        }
        binding.fastingHero.setOnClickListener {
            // Fasting is a bottom-nav tab — select the tab so the back stack stays consistent.
            (activity as? MainActivity)?.selectTab(R.id.fastingFragment)
        }
        binding.waterCard.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_water)
        }
        binding.energyCard.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_nutrition)
        }
        binding.movementCard.setOnClickListener {
            // Movement is now a bottom-nav tab (Trends/recovery) — select the tab.
            (activity as? MainActivity)?.selectTab(R.id.trendsFragment)
        }
        // Today / Week toggle re-renders the movement section from the latest state.
        binding.movementToggle.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) renderMovement(current)
        }
        binding.weightCard.setOnClickListener {
            WeightLogDialog.show(
                requireContext(),
                current.unitSystem,
                current.currentWeightKg,
                if (current.hasWeightData) current.currentWeightKg else null
            ) { kg -> viewModel.logWeight(kg) }
        }
        binding.profileButton.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_profile)
        }
        binding.checkInCard.setOnClickListener {
            current.checkInFast?.let { f ->
                ai.anthracite.fasttrack.ui.checkin.PostFastCheckIn.prompt(requireActivity(), f)
            }
        }

        // Arrow back to review how a past day went; today stays the live dashboard.
        binding.dashPrevDay.setOnClickListener { viewModel.goPrevDay() }
        binding.dashNextDay.setOnClickListener { viewModel.goNextDay() }

        // On a past day, the hydration/energy rows jump to that day's editor so water and food can
        // be back-filled, just like navigating with the arrows.
        binding.histWaterRow.setOnClickListener {
            val args = Bundle().apply {
                putLong(ai.anthracite.fasttrack.ui.water.WaterFragment.ARG_DAY_MS, viewModel.selectedDayMs())
            }
            findNavController().navigate(R.id.action_dashboard_to_water, args)
        }
        binding.histEnergyRow.setOnClickListener {
            val args = Bundle().apply {
                putLong(ai.anthracite.fasttrack.ui.nutrition.NutritionFragment.ARG_DAY_MS, viewModel.selectedDayMs())
            }
            findNavController().navigate(R.id.action_dashboard_to_nutrition, args)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.dayLabel.collect { binding.dashDayLabel.text = it } }
                launch {
                    viewModel.isToday.collect { today ->
                        binding.dashNextDay.isEnabled = !today
                        binding.dashNextDay.alpha = if (today) 0.35f else 1f
                    }
                }
                launch {
                    viewModel.canGoBack.collect { canBack ->
                        binding.dashPrevDay.isEnabled = canBack
                        binding.dashPrevDay.alpha = if (canBack) 1f else 0.35f
                    }
                }
                launch { viewModel.history.collect { applyDayMode(it) } }
                launch { viewModel.weekStrip.collect { renderStrip(it) } }
            }
        }
    }

    /** Toggles between the live "today" dashboard and the read-only recap of a past day. */
    private fun applyDayMode(h: DaySnapshot?) {
        viewingPast = h != null
        val liveVis = if (h == null) View.VISIBLE else View.GONE
        binding.fastingHero.visibility = liveVis
        binding.todayTitle.visibility = liveVis
        binding.todayRow.visibility = liveVis
        binding.movementCard.visibility = liveVis
        binding.projectionCard.visibility = liveVis
        if (h != null) {
            binding.recoveryCard.visibility = View.GONE
            binding.checkInCard.visibility = View.GONE
        } else {
            renderHabitCards(current)
        }
        // Weight card is live-only and only when a weigh-in exists.
        binding.weightCard.visibility =
            if (h == null && current.hasWeightData) View.VISIBLE else View.GONE
        binding.historyContainer.visibility = if (h == null) View.GONE else View.VISIBLE

        if (h != null) {
            binding.histScoreHeadline.text = h.scoreHeadline
            binding.histScoreLine.text = h.scoreLine
            renderRings(h.rings)
            binding.histRecapHeadline.visibility =
                if (h.recapHeadline.isBlank()) View.GONE else View.VISIBLE
            binding.histRecapHeadline.text = h.recapHeadline
            binding.histRecapSummary.text = h.recapSummary
            binding.histFasting.text = h.fasting
            binding.histHydration.text = h.hydration
            binding.histEnergy.text = h.energy
            binding.histMovement.text = h.movement
        }
    }

    /** Builds the row of goal rings for a past day — each fills toward its goal, ✓ when closed. */
    private fun renderRings(rings: List<RingData>) {
        val container = binding.histRings
        container.removeAllViews()
        rings.forEach { r ->
            val item = ItemDayRingBinding.inflate(layoutInflater, container, false)
            val color = ContextCompat.getColor(requireContext(), r.colorRes)
            item.ringProgress.setIndicatorColor(color)
            item.ringProgress.trackColor = ColorUtils.setAlphaComponent(color, 38)
            // A closed goal always reads as a full ring; otherwise show real progress.
            item.ringProgress.setProgressCompat(if (r.closed) 100 else r.progress, false)
            item.ringLabel.text = r.label
            item.ringValue.text = r.valueText
            if (r.closed) {
                item.ringIcon.setImageResource(R.drawable.ic_check)
                item.ringIcon.setColorFilter(color)
            } else {
                item.ringIcon.setImageResource(r.iconRes)
                item.ringIcon.setColorFilter(ColorUtils.setAlphaComponent(color, 170))
            }
            container.addView(item.root)
        }
    }

    /** Builds the 7-day heatmap strip; brighter tile = more goals closed that day. */
    private fun renderStrip(cells: List<DayCell>) {
        val container = binding.histStrip
        container.removeAllViews()
        if (cells.isEmpty()) return
        val accent = ContextCompat.getColor(requireContext(), R.color.accent_fasting)
        val dark = ContextCompat.getColor(requireContext(), R.color.brand_ink)
        val muted = com.google.android.material.color.MaterialColors.getColor(
            container, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        cells.forEach { c ->
            val item = ItemDayStripBinding.inflate(layoutInflater, container, false)
            item.stripDow.text = c.dowInitial
            item.stripDay.text = c.dayNum
            val alpha = (36 + c.score * 55).coerceAtMost(255)
            item.stripTile.backgroundTintList =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, alpha))
            item.stripDay.setTextColor(if (c.score >= 3) dark else muted)
            if (c.selected) {
                item.stripTile.foreground =
                    ContextCompat.getDrawable(requireContext(), R.drawable.bg_ring_outline)
                item.stripDow.setTypeface(null, Typeface.BOLD)
                item.stripDow.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_fasting)
                )
            }
            container.addView(item.root)
        }
    }

    /** Recovery readiness and the daily check-in card. */
    private fun renderHabitCards(s: DashboardUiState) {
        val r = s.readiness
        binding.recoveryCard.visibility = if (r == null) View.GONE else View.VISIBLE
        if (r != null) {
            val colorRes = when (r.level) {
                ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.LOW -> R.color.macro_protein
                ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.MODERATE -> R.color.accent_fasting
                ai.anthracite.fasttrack.data.health.RecoveryEngine.Level.HIGH -> R.color.accent_nutrition
            }
            val color = ContextCompat.getColor(requireContext(), colorRes)
            binding.recoveryRing.setIndicatorColor(color)
            binding.recoveryRing.trackColor = ColorUtils.setAlphaComponent(color, 40)
            binding.recoveryRing.setProgressCompat(r.score, false)
            binding.recoveryScore.text = r.score.toString()
            binding.recoveryHeadline.text = r.headline
            binding.recoveryFactors.text = r.factors.joinToString(" · ")
            binding.recoveryFasting.text = "Fasting: ${r.fastingAdvice}"
            binding.recoveryTraining.text = "Training: ${r.trainingAdvice}"
        }

        // The check-in follows a fast that reached at least half its goal; no fast, no card.
        val c = s.checkInToday
        val f = s.checkInFast
        binding.checkInCard.visibility = if (f == null) View.GONE else View.VISIBLE
        if (f == null) return
        if (c == null) {
            val hours = ai.anthracite.fasttrack.data.health.FastHistory.hours(f)
            binding.checkInEmoji.text = "🙂"
            binding.checkInTitle.text = "How did your ${String.format(Locale.US, "%.0f", hours)}h fast feel?"
            binding.checkInBody.text = "Mood, energy and hunger — takes 5 seconds."
        } else {
            val labels = ai.anthracite.fasttrack.data.health.CheckInInsights
            binding.checkInEmoji.text = ai.anthracite.fasttrack.ui.checkin.CheckInDialog.MOOD_EMOJI[c.mood - 1]
            binding.checkInTitle.text =
                "${labels.MOOD_LABELS[c.mood - 1]} · ${labels.ENERGY_LABELS[c.energy - 1].lowercase()}"
            binding.checkInBody.text = s.checkInInsight ?: "Tap to update how that fast felt."
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back to the tab always lands on today's live dashboard.
        viewModel.resetToToday()
        if (!viewingPast) renderHabitCards(current)
    }

    private fun render(s: DashboardUiState) {
        current = s
        binding.nameText.text = s.name
        binding.fastingProtocolChip.text = s.fastingProtocolName

        // Streak lives next to the name in the header.
        if (s.streak > 0) {
            binding.streakChip.visibility = View.VISIBLE
            binding.streakValue.text = s.streak.toString()
        } else {
            binding.streakChip.visibility = View.GONE
        }

        // Water — single-line "64 / 105 oz" so the card never grows when the number gets big,
        // plus a hydration-specific nudge where the water lives.
        val waterPct = if (s.waterGoalMl > 0) (s.waterMl / s.waterGoalMl * 100).roundToInt() else 0
        binding.waterProgress.setProgressCompat(waterPct.coerceIn(0, 100), true)
        binding.waterValue.text = Units.volumeRangeLabel(s.waterMl, s.waterGoalMl, s.unitSystem)
        binding.waterInsight.text = hydrationNote(s)

        renderEnergy(s.restingBurnedToday)

        binding.projectionTitle.text = s.projectionTitle
        binding.projectionBody.text = s.projectionBody

        // Weight trend (card hidden until there's a weigh-in; axis fitted to the data).
        if (s.hasWeightData) {
            binding.weightValue.text = s.weightLabel
            binding.weightTrend.text = s.weightTrendText
            binding.weightSpark.baselineZero = false
            binding.weightSpark.setData(s.weightSparkValues)
        }
        if (!viewingPast) {
            binding.weightCard.visibility = if (s.hasWeightData) View.VISIBLE else View.GONE
        }

        if (!viewingPast) renderHabitCards(s)

        // Movement: default to today's breakdown, with a Today/Week toggle.
        renderMovement(s)

        // Fasting button + timer
        binding.fastingActionButton.text =
            getString(if (s.activeFast == null) R.string.fasting_start else R.string.fasting_stop)
        restartTicker()

        maybeShowDailyUpdate(s)
    }

    /**
     * Shows the daily-update popup at most once per calendar day, as soon as there's something to
     * show. Also (re)archives today's update — including recommendations — so it can be revisited
     * from Profile ▸ Daily update.
     */
    private fun maybeShowDailyUpdate(s: DashboardUiState) {
        if (dailyUpdateHandled || !isAdded) return
        val hasContent = s.recap.hasData || s.recommendations.isNotEmpty()
        if (!hasContent) return
        dailyUpdateHandled = true

        val app = requireContext().applicationContext as ai.anthracite.fasttrack.FastTrackApp
        val prefs = app.prefs
        val today = ai.anthracite.fasttrack.util.DayKeys.today()
        val dateLabel = fullDateLabel()
        // Same fasting streak shown in the header chip, so the two never disagree.
        val streak = s.streak

        viewModel.archiveToday(dateLabel, s.recap, s.recommendations, streak)

        if (prefs.lastDailyUpdateShownDay == today) return
        prefs.lastDailyUpdateShownDay = today

        // Daily update popup: the classic recap for everyone, or — when on-device AI is enabled — a
        // personalized, goal-aware coach note in its place (with a shortcut to keep chatting).
        val weekly = s.weeklyReviewKey
            ?.takeIf { prefs.lastSeenWeeklyReview != it }
            ?.let {
                ai.anthracite.fasttrack.ui.common.DailyUpdateDialog.WeeklyLink(s.weeklyHeadline) {
                    if (isAdded) findNavController().navigate(R.id.weeklyReviewFragment)
                }
            }
        val aiReady = prefs.aiFeaturesEnabled && app.onDeviceLlm.isModelPresent
        if (aiReady) {
            ai.anthracite.fasttrack.ui.common.DailyUpdateDialog.showAi(
                requireContext(),
                viewLifecycleOwner.lifecycleScope,
                app,
                dateLabel,
                streakLine(streak),
                s.recap,
                s.recommendations,
                weekly
            ) { (activity as? ai.anthracite.fasttrack.MainActivity)?.selectTab(R.id.coachFragment) }
        } else {
            ai.anthracite.fasttrack.ui.common.DailyUpdateDialog.show(
                requireContext(), dateLabel, streakLine(streak), s.recap, s.recommendations, weekly
            )
        }
    }

    /** Short hydration nudge shown on the water card (one glass ≈ 8 oz / 240 ml). */
    private fun hydrationNote(s: DashboardUiState): String {
        val remainingMl = (s.waterGoalMl - s.waterMl)
        if (remainingMl <= 0) return "Goal reached \uD83C\uDF89"
        val glasses = Math.ceil(remainingMl / 240.0).toInt().coerceAtLeast(1)
        return "$glasses glass${if (glasses == 1) "" else "es"} to goal"
    }

    private fun streakLine(streak: Int): String = when {
        streak <= 0 -> "No fasting streak yet · finish a fast today to start one"
        streak == 1 -> "1-day fasting streak · keep it going"
        else -> "$streak-day fasting streak · keep it going"
    }

    /**
     * Net energy balance: calories in − calories out (resting burn + exercise). Negative means a
     * deficit (good for a loss goal); positive means a surplus. [restingBurned] is passed in so the
     * ticker can refresh it live as the day's resting burn climbs.
     */
    private fun renderEnergy(restingBurned: Int) {
        val s = current
        val out = restingBurned + s.exerciseCal
        val net = s.calories - out
        val sign = if (net < 0) "\u2212" else if (net > 0) "+" else ""
        binding.energyNetValue.text = "$sign${"%,d".format(kotlin.math.abs(net))} kcal"

        val color = ContextCompat.getColor(
            requireContext(),
            if (net <= 0) R.color.accent_nutrition else R.color.macro_carbs
        )
        binding.energyNetValue.setTextColor(color)
        binding.energyStateChip.setTextColor(color)
        binding.energyStateChip.text = when {
            net < 0 -> "Deficit"
            net > 0 -> "Surplus"
            else -> "Even"
        }
        binding.energyBreakdown.text =
            "${"%,d".format(s.calories)} in · ${"%,d".format(out)} out"

        // Intake toward today's calorie goal (merged in from the old nutrition card).
        val calPct = if (s.calorieGoal > 0) (s.calories.toDouble() / s.calorieGoal * 100).roundToInt() else 0
        binding.energyProgress.setProgressCompat(calPct.coerceIn(0, 100), true)
    }

    /** Movement card: "Today" shows today's per-activity breakdown; "Week" shows the weekly chart. */
    private fun renderMovement(s: DashboardUiState) {
        val weekMode = binding.movementToggle.checkedButtonId == R.id.toggleWeek
        if (weekMode) {
            binding.movementWeekTotal.text = s.weekDistanceText
            binding.movementTrend.text = s.distanceTrendText
            binding.movementTrend.visibility = View.VISIBLE
            binding.movementTodaySub.text = s.weekMovementSub
            binding.movementTodaySub.visibility =
                if (s.weekMovementSub.isBlank()) View.GONE else View.VISIBLE
            if (s.hasDistanceData) {
                binding.movementChart.visibility = View.VISIBLE
                binding.movementEmpty.visibility = View.GONE
                binding.movementChart.setData(s.weeklyDistanceValues, s.weeklyDistanceLabels)
            } else {
                binding.movementChart.visibility = View.GONE
                binding.movementEmpty.visibility = View.VISIBLE
                binding.movementEmpty.text = getString(R.string.dash_movement_empty)
            }
            renderCategories(s.movementCategories)
        } else {
            binding.movementWeekTotal.text = s.todayDistanceText
            binding.movementTrend.visibility = View.GONE
            binding.movementTodaySub.text = s.todayMovementSub
            binding.movementTodaySub.visibility =
                if (s.todayMovementSub.isBlank()) View.GONE else View.VISIBLE
            // No weekly chart in the Today view — the per-activity rows are the breakdown.
            binding.movementChart.visibility = View.GONE
            renderCategories(s.todayCategories)
            binding.movementEmpty.visibility =
                if (s.hasTodayMovement) View.GONE else View.VISIBLE
            if (!s.hasTodayMovement) {
                binding.movementEmpty.text = getString(R.string.dash_movement_today_empty)
            }
        }
    }

    private fun renderCategories(categories: List<MovementCategory>) {
        val container = binding.movementCategories
        container.removeAllViews()
        container.visibility = if (categories.isEmpty()) View.GONE else View.VISIBLE
        categories.forEach { c ->
            val row = ItemMovementCategoryBinding.inflate(layoutInflater, container, false)
            row.categoryDot.backgroundTintList =
                ContextCompat.getColorStateList(requireContext(), c.colorRes)
            row.categoryLabel.text = c.label
            row.categoryValue.text =
                c.valueText.ifBlank { String.format(Locale.US, "%.1f %s", c.distance, c.unit) }
            container.addView(row.root)
        }
    }

    private fun restartTicker() {
        tickRunnable?.let { ticker.removeCallbacks(it) }
        val active = current.activeFast
        val goalMs = ((active?.goalHours ?: 0.0) * 3_600_000L).toLong()
        val runnable = object : Runnable {
            override fun run() {
                renderEnergy(GoalProjection.restingBurnedSoFar(current.bmrKcal))
                if (active != null && goalMs > 0) {
                    binding.fastingHeroTitle.text = getString(R.string.dash_fasting_card)
                    val elapsed = System.currentTimeMillis() - active.startEpochMs
                    val remaining = goalMs - elapsed
                    if (remaining > 0) {
                        binding.fastingStatusLabel.text = getString(R.string.fasting_remaining)
                        binding.fastingTimer.text = TimeFmt.hms(remaining)
                    } else {
                        binding.fastingStatusLabel.text = getString(R.string.fasting_elapsed)
                        binding.fastingTimer.text = TimeFmt.hms(elapsed)
                    }
                    val pct = (elapsed.toDouble() / goalMs * 100).roundToInt().coerceIn(0, 100)
                    binding.fastingProgress.setProgressCompat(pct, true)
                    showFastingStage(elapsed)
                    ticker.postDelayed(this, 1000)
                } else {
                    binding.fastingStageLabel.visibility = View.GONE
                    // Idle: after a fast ends, count down the eating window (24 - fastHours) to the
                    // next fast, relative to when *this* fast ended — not the fixed daily reminder.
                    val eatingWindowMs = ((24.0 - current.fastingGoalHours) * 3_600_000L).toLong()
                    val nextFastStart = current.lastFastEndMs?.let { it + eatingWindowMs }
                    val now = System.currentTimeMillis()
                    if (nextFastStart != null && eatingWindowMs > 0L && now < nextFastStart) {
                        val remaining = nextFastStart - now
                        binding.fastingHeroTitle.text = getString(R.string.fasting_eating_window)
                        binding.fastingStatusLabel.text = "Next fast ~${TimeFmt.clock(nextFastStart)}"
                        binding.fastingTimer.text = TimeFmt.hms(remaining)
                        val pct = (((eatingWindowMs - remaining).toDouble() / eatingWindowMs) * 100)
                            .roundToInt().coerceIn(0, 100)
                        binding.fastingProgress.setProgressCompat(pct, true)
                        ticker.postDelayed(this, 1000)
                    } else {
                        // No recent fast (or window elapsed). Only quote the daily reminder while
                        // it's still ahead today — a time that already passed reads like a bug.
                        binding.fastingHeroTitle.text = "Not fasting"
                        val minOfDay = idleFastMinOfDay()
                        val cal = Calendar.getInstance()
                        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                        binding.fastingStatusLabel.text = when {
                            minOfDay != null && minOfDay > nowMin -> "Next fast ~${clockLabel(minOfDay)}"
                            nextFastStart != null && eatingWindowMs > 0L ->
                                "Ready to fast · window opened ${TimeFmt.clock(nextFastStart)}"
                            else -> "Ready when you are"
                        }
                        binding.fastingTimer.text = "${current.fastingGoalHours.toInt()}h fast"
                        binding.fastingProgress.setProgressCompat(0, true)
                        ticker.postDelayed(this, 30_000)
                    }
                }
            }
        }
        tickRunnable = runnable
        ticker.post(runnable)
    }

    /** Show the current physiological fasting stage on the hero (so you don't have to open Fasting). */
    private fun showFastingStage(elapsedMs: Long) {
        val stage = FastingStages.current(elapsedMs / 3_600_000.0)
        val label = binding.fastingStageLabel
        label.text = "${stage.title} · ${stage.bodyState}"
        label.setCompoundDrawablesRelativeWithIntrinsicBounds(stage.iconRes, 0, 0, 0)
        TextViewCompat.setCompoundDrawableTintList(
            label,
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.brand_ink))
        )
        label.visibility = View.VISIBLE
    }

    /** "4:22 PM" for a minutes-past-midnight value. */
    private fun clockLabel(minOfDay: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minOfDay / 60)
            set(Calendar.MINUTE, minOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return TimeFmt.clock(cal.timeInMillis)
    }

    /** Minutes-past-midnight of the user's daily fast, or null if no reminder is set. */
    private fun idleFastMinOfDay(): Int? {
        val prefs = (requireContext().applicationContext as ai.anthracite.fasttrack.FastTrackApp).prefs
        val min = prefs.dailyFastStartMinOfDay
        return if (prefs.dailyFastReminderEnabled && min in 0..1439) min else null
    }

    /** "Monday October 19th" — the dated header under "Today". */
    private fun fullDateLabel(): String {
        val cal = Calendar.getInstance()
        val weekday = java.text.SimpleDateFormat("EEEE", Locale.US).format(cal.time)
        val month = java.text.SimpleDateFormat("MMMM", Locale.US).format(cal.time)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        return "$weekday $month $day${ordinalSuffix(day)}"
    }

    private fun ordinalSuffix(day: Int): String =
        if (day in 11..13) "th" else when (day % 10) {
            1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th"
        }

    private fun greetingForHour(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    override fun onDestroyView() {
        tickRunnable?.let { ticker.removeCallbacks(it) }
        tickRunnable = null
        _binding = null
        super.onDestroyView()
    }
}
