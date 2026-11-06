package ai.anthracite.fasttrack.ui.dailyupdate

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.local.DailyUpdate
import ai.anthracite.fasttrack.databinding.FragmentDailyUpdateBinding
import ai.anthracite.fasttrack.databinding.ItemDailyUpdateBinding
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/** Read-only archive of past daily updates (recaps), reachable from Profile ▸ Daily update. */
class DailyUpdateFragment : Fragment() {

    private var _binding: FragmentDailyUpdateBinding? = null
    private val binding get() = _binding!!
    private val adapter = DailyUpdateAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDailyUpdateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.updatesList.adapter = adapter

        val repo = (requireContext().applicationContext as FastTrackApp).repository
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeDailyUpdates().collect { updates ->
                    adapter.submitList(updates)
                    binding.emptyText.visibility = if (updates.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

private class DailyUpdateAdapter :
    ListAdapter<DailyUpdate, DailyUpdateAdapter.VH>(DIFF) {

    class VH(val binding: ItemDailyUpdateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemDailyUpdateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val u = getItem(position)
        val b = holder.binding
        b.updateDate.text = u.dateLabel
        b.updateHeadline.visibility = if (u.headline.isBlank()) View.GONE else View.VISIBLE
        b.updateHeadline.text = u.headline
        b.updateSummary.visibility = if (u.summary.isBlank()) View.GONE else View.VISIBLE
        b.updateSummary.text = u.summary
        if (u.recommendations.isBlank()) {
            b.updateRecs.visibility = View.GONE
        } else {
            b.updateRecs.visibility = View.VISIBLE
            b.updateRecs.text = u.recommendations.lines().joinToString("\n") { "•  $it" }
        }
        if (u.loginStreak > 1) {
            b.updateStreak.visibility = View.VISIBLE
            b.updateStreak.text = "${u.loginStreak}-day fasting streak"
        } else {
            b.updateStreak.visibility = View.GONE
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DailyUpdate>() {
            override fun areItemsTheSame(a: DailyUpdate, b: DailyUpdate) = a.dayKey == b.dayKey
            override fun areContentsTheSame(a: DailyUpdate, b: DailyUpdate) = a == b
        }
    }
}
