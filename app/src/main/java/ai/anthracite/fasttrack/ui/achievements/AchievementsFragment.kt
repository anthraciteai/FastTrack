package ai.anthracite.fasttrack.ui.achievements

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.HabitHistory
import ai.anthracite.fasttrack.data.health.StreaksEngine
import ai.anthracite.fasttrack.databinding.FragmentAchievementsBinding
import ai.anthracite.fasttrack.databinding.ItemChallengeBinding
import ai.anthracite.fasttrack.databinding.ItemStreakBinding
import ai.anthracite.fasttrack.ui.fasting.AchievementAdapter
import ai.anthracite.fasttrack.ui.fasting.FastingViewModel
import ai.anthracite.fasttrack.util.DayKeys
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
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AchievementsFragment : Fragment() {

    private var _binding: FragmentAchievementsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FastingViewModel by viewModels()
    private val achievementAdapter = AchievementAdapter()
    private val habits = MutableStateFlow<StreaksEngine.Inputs?>(null)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAchievementsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.achievementsList.adapter = achievementAdapter

        binding.streakStat.statIcon.setImageResource(R.drawable.ic_flame)
        binding.streakStat.statLabel.text = "day streak"
        binding.bestStat.statIcon.setImageResource(R.drawable.ic_trophy)
        binding.bestStat.statLabel.text = "best streak"
        binding.totalStat.statIcon.setImageResource(R.drawable.ic_check)
        binding.totalStat.statLabel.text = "fasts done"

        val app = requireActivity().application as FastTrackApp
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { habits.value = HabitHistory.load(app).streakInputs(DayKeys.today()) }
                combine(viewModel.stats, habits) { s, h -> s to h }.collect { (s, h) ->
                    binding.streakStat.statValue.text = s.currentStreak.toString()
                    binding.bestStat.statValue.text = s.longestStreak.toString()
                    binding.totalStat.statValue.text = s.totalCompleted.toString()
                    val badges = h?.let { StreaksEngine.badges(it) }.orEmpty()
                    achievementAdapter.submitList(s.achievements + badges)
                    if (h != null) renderHabits(h)
                }
            }
        }
    }

    private fun renderHabits(inputs: StreaksEngine.Inputs) {
        val ctx = requireContext()
        val inflater = LayoutInflater.from(ctx)

        binding.streaksList.removeAllViews()
        StreaksEngine.streaks(inputs).forEach { s ->
            val row = ItemStreakBinding.inflate(inflater, binding.streaksList, false)
            val color = ContextCompat.getColor(ctx, s.colorRes)
            row.streakIcon.setImageResource(s.iconRes)
            row.streakIcon.imageTintList = ColorStateList.valueOf(color)
            row.streakBadge.backgroundTintList = ColorStateList.valueOf(color).withAlpha(40)
            row.streakTitle.text = s.title
            row.streakRule.text = "${s.rule} · best ${s.best}"
            row.streakValue.text = s.current.toString()
            row.streakFlame.imageTintList = ColorStateList.valueOf(
                if (s.doneToday) color else ContextCompat.getColor(ctx, R.color.md_tertiary)
            )
            row.streakFlame.alpha = if (s.current > 0) 1f else 0.3f
            row.root.contentDescription = "${s.title}: ${s.current} day streak, best ${s.best}"
            binding.streaksList.addView(row.root)
        }

        val challenges = StreaksEngine.weeklyChallenges(inputs)
        val done = challenges.count { it.completed }
        val left = StreaksEngine.daysLeftInWeek(inputs.todayKey)
        binding.challengesCaption.text =
            "$done of ${challenges.size} done · $left day${if (left == 1) "" else "s"} left"
        binding.challengesList.removeAllViews()
        challenges.forEach { c ->
            val card = ItemChallengeBinding.inflate(inflater, binding.challengesList, false)
            card.challengeTitle.text = c.title
            card.challengeDesc.text = c.description
            card.challengeProgress.text = "${c.progress} / ${c.target} ${c.unit}"
            card.challengeBar.setProgressCompat((c.fraction * 100).toInt(), false)
            card.challengeDone.visibility = if (c.completed) View.VISIBLE else View.GONE
            binding.challengesList.addView(card.root)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
