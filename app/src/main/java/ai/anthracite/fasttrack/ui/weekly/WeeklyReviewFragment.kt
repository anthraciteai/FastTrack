package ai.anthracite.fasttrack.ui.weekly

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.StreaksEngine
import ai.anthracite.fasttrack.data.health.WeeklyReviewEngine
import ai.anthracite.fasttrack.data.health.WeeklyReviewService
import ai.anthracite.fasttrack.databinding.FragmentWeeklyReviewBinding
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class WeeklyReviewFragment : Fragment() {

    private var _binding: FragmentWeeklyReviewBinding? = null
    private val binding get() = _binding!!
    private val app get() = requireActivity().application as FastTrackApp

    private var weekStart: LocalDate = WeeklyReviewEngine.reviewWeekFor(LocalDate.now())
    private var report: WeeklyReviewEngine.Report? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWeeklyReviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        savedInstanceState?.getString(KEY_WEEK)?.let { weekStart = LocalDate.parse(it) }
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.prevWeek.setOnClickListener { weekStart = weekStart.minusWeeks(1); load() }
        binding.nextWeek.setOnClickListener { weekStart = weekStart.plusWeeks(1); load() }
        binding.aiButton.setOnClickListener { writeWithAi() }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_WEEK, weekStart.toString())
    }

    private fun load() {
        val current = StreaksEngine.weekStart(LocalDate.now())
        binding.nextWeek.isEnabled = weekStart < current
        binding.nextWeek.alpha = if (weekStart < current) 1f else 0.3f
        viewLifecycleOwner.lifecycleScope.launch {
            val stored = WeeklyReviewService.generate(app, weekStart)
            val r = WeeklyReviewService.build(app, weekStart)
            app.prefs.lastSeenWeeklyReview = r.weekKey
            report = r
            val unit = runCatching { UnitSystem.valueOf(app.repository.getProfile().unitSystem) }
                .getOrDefault(UnitSystem.IMPERIAL)
            if (_binding == null) return@launch
            render(r, unit, stored.aiText)
        }
    }

    private fun render(r: WeeklyReviewEngine.Report, unit: UnitSystem, aiText: String?) {
        val inProgress = weekStart == StreaksEngine.weekStart(LocalDate.now()) &&
            LocalDate.now().dayOfWeek != java.time.DayOfWeek.SUNDAY
        binding.weekRange.text = if (inProgress) "${r.rangeLabel} · so far" else r.rangeLabel
        binding.headline.text = r.headline
        binding.focus.text = "Focus next week: ${r.focus}"

        val aiReady = app.prefs.aiFeaturesEnabled && app.onDeviceLlm.isModelPresent
        binding.aiText.visibility = if (aiText.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.aiText.text = aiText.orEmpty()
        binding.aiHint.visibility = if (aiText.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.aiHint.text = if (aiReady) {
            "Let the on-device model turn these numbers into a personal note. Nothing leaves your phone."
        } else {
            "Turn on on-device AI in Settings to get a personal note written from these numbers."
        }
        binding.aiButton.isEnabled = aiReady
        binding.aiButton.text = if (aiText.isNullOrBlank()) "Write my review" else "Rewrite"

        renderStats(r, unit)
        renderSections(r)
    }

    private fun renderStats(r: WeeklyReviewEngine.Report, unit: UnitSystem) {
        val s = r.stats
        val p = r.previous
        binding.statsList.removeAllViews()
        fun h(v: Double?) = v?.let { String.format(Locale.US, "%.1fh", it) } ?: "–"
        fun d(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "–"
        fun delta(now: Number?, before: Number?, higherIsBetter: Boolean = true, fmt: (Double) -> String): Pair<String, Boolean?>? {
            if (now == null || before == null) return null
            val diff = now.toDouble() - before.toDouble()
            if (abs(diff) < 0.05) return "same" to null
            val sign = if (diff > 0) "+" else "−"
            return "$sign${fmt(abs(diff))}" to ((diff > 0) == higherIsBetter)
        }
        val int: (Double) -> String = { it.roundToInt().toString() }
        val one: (Double) -> String = { String.format(Locale.US, "%.1f", it) }

        row("Fasts completed", "${s.fastsCompleted} of ${s.fastsStarted}", delta(s.fastsCompleted, p.fastsCompleted, fmt = int))
        row("Average fast", h(s.avgFastHours), delta(s.avgFastHours, p.avgFastHours, fmt = { one(it) + "h" }))
        row("Longest fast", h(s.longestFastHours), null)
        row("Avg calories", s.avgCalories?.let { "$it kcal" } ?: "–", delta(s.avgCalories, p.avgCalories, higherIsBetter = false, fmt = int))
        row("Days logged", "${s.daysLogged} / 7", delta(s.daysLogged, p.daysLogged, fmt = int))
        row("Water goal days", "${s.waterGoalDays} / 7", delta(s.waterGoalDays, p.waterGoalDays, fmt = int))
        row("Active minutes", "${s.activeMinutes}", delta(s.activeMinutes, p.activeMinutes, fmt = int))
        if (s.avgSleepHours != null || p.avgSleepHours != null) {
            row("Avg sleep", h(s.avgSleepHours), delta(s.avgSleepHours, p.avgSleepHours, fmt = { one(it) + "h" }))
        }
        row(
            "Weight trend",
            s.weightChangeKg?.let { (if (it < 0) "−" else "+") + Units.weightLabel(abs(it), unit) } ?: "–",
            null
        )
        row("Mood / energy", "${d(s.avgMood)} / ${d(s.avgEnergy)}", null)
    }

    private fun row(label: String, value: String, delta: Pair<String, Boolean?>?) {
        val ctx = requireContext()
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        line.addView(TextView(ctx).apply {
            setTextAppearance(R.style.Text_FastTrack_Caption)
            text = label
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(ctx).apply {
            setTextAppearance(R.style.Text_FastTrack_SectionTitle)
            text = value
        })
        if (delta != null) {
            line.addView(TextView(ctx).apply {
                setTextAppearance(R.style.Text_FastTrack_Caption)
                text = delta.first
                setPadding(dp(8), 0, 0, 0)
                delta.second?.let { good ->
                    setTextColor(ContextCompat.getColor(ctx, if (good) R.color.accent_nutrition else R.color.accent_fasting))
                }
            })
        }
        binding.statsList.addView(line)
    }

    private fun renderSections(r: WeeklyReviewEngine.Report) {
        binding.sections.removeAllViews()
        section("Wins", r.wins.ifEmpty { listOf("Every logged day counts — next week is a fresh start.") }, R.color.accent_nutrition)
        if (r.watchOuts.isNotEmpty()) section("Watch-outs", r.watchOuts, R.color.accent_fasting)
        if (r.patterns.isNotEmpty()) section("Patterns", r.patterns, R.color.accent_water)
    }

    private fun section(title: String, lines: List<String>, colorRes: Int) {
        val ctx = requireContext()
        binding.sections.addView(TextView(ctx).apply {
            setTextAppearance(R.style.Text_FastTrack_SectionTitle)
            text = title
            setTextColor(ContextCompat.getColor(ctx, colorRes))
            setPadding(0, dp(20), 0, dp(6))
        })
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainer))
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = 0
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        lines.forEach { l ->
            list.addView(TextView(ctx).apply {
                setTextAppearance(R.style.Text_FastTrack_Body)
                text = "•  $l"
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        card.addView(list)
        binding.sections.addView(card)
    }

    private fun writeWithAi() {
        val r = report ?: return
        binding.aiButton.isEnabled = false
        binding.aiProgress.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val text = WeeklyReviewService.writeWithAi(app, r)
            if (_binding == null) return@launch
            binding.aiProgress.visibility = View.GONE
            binding.aiButton.isEnabled = true
            if (text == null) {
                Snackbar.make(binding.root, "The on-device model couldn't write a review just now.", Snackbar.LENGTH_LONG).show()
            } else {
                binding.aiText.text = text
                binding.aiText.visibility = View.VISIBLE
                binding.aiHint.visibility = View.GONE
                binding.aiButton.text = "Rewrite"
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object { const val KEY_WEEK = "week_start" }
}
