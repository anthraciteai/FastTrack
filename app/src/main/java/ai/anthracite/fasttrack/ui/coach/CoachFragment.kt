package ai.anthracite.fasttrack.ui.coach

import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.databinding.FragmentCoachBinding
import ai.anthracite.fasttrack.databinding.ItemCoachMessageBinding
import ai.anthracite.fasttrack.databinding.ItemJournalEntryBinding
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class CoachFragment : Fragment() {

    private var _binding: FragmentCoachBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CoachViewModel by viewModels()
    private val adapter = CoachAdapter()
    private val journalAdapter = JournalAdapter(
        onDelete = { confirmDelete(it) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCoachBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.messageList.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.messageList.adapter = adapter
        binding.journalHistoryList.layoutManager = LinearLayoutManager(requireContext())
        binding.journalHistoryList.adapter = journalAdapter

        binding.modeToggle.check(R.id.coachButton)
        binding.modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.setMode(if (checkedId == R.id.journalButton) ChatMode.JOURNAL else ChatMode.COACH)
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.actionNewChat -> {
                    viewModel.clear()
                    true
                }
                R.id.actionProfile -> {
                    findNavController().navigate(R.id.action_coach_to_profile)
                    true
                }
                else -> false
            }
        }

        binding.suggestFast.setOnClickListener { viewModel.send(binding.suggestFast.text.toString()) }
        binding.suggestBreak.setOnClickListener { viewModel.send(binding.suggestBreak.text.toString()) }
        binding.suggestCalories.setOnClickListener { viewModel.send(binding.suggestCalories.text.toString()) }
        binding.suggestTired.setOnClickListener { viewModel.send(binding.suggestTired.text.toString()) }

        binding.saveEntryButton.setOnClickListener { viewModel.saveJournal() }

        binding.sendButton.setOnClickListener { submit() }
        binding.promptInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEND) {
                submit()
                true
            } else false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event -> event?.let { handleEvent(it) } }
            }
        }

        // Proactive, data-aware greeting when the Coach opens empty (no-op if AI is off / not ready).
        viewModel.briefOnOpen()
    }

    private fun submit() {
        val text = binding.promptInput.text?.toString().orEmpty()
        binding.promptInput.setText("")
        viewModel.send(text)
    }

    private fun handleEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.JournalSaved ->
                Snackbar.make(binding.root, getString(R.string.journal_saved, event.title), Snackbar.LENGTH_LONG).show()
            is ChatEvent.Error ->
                Snackbar.make(binding.root, event.message, Snackbar.LENGTH_LONG).show()
        }
        viewModel.consumeEvent()
    }

    private fun confirmDelete(entry: JournalEntryUi) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.title)
            .setMessage(R.string.journal_delete)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteEntry(entry.id) }
            .show()
    }

    private fun render(state: CoachUiState) {
        val journal = state.mode == ChatMode.JOURNAL
        val targetId = if (journal) R.id.journalButton else R.id.coachButton
        if (binding.modeToggle.checkedButtonId != targetId) binding.modeToggle.check(targetId)

        binding.promptInput.hint = getString(if (journal) R.string.journal_hint else R.string.coach_hint)
        binding.typingText.text = getString(if (journal) R.string.journal_thinking else R.string.coach_thinking)

        val hasChat = state.messages.isNotEmpty()
        binding.messageList.isVisible = hasChat
        binding.emptyState.isVisible = !hasChat && !journal
        binding.journalEmpty.isVisible = !hasChat && journal

        binding.typingText.isVisible = state.busy
        val composerEnabled = !state.busy && !state.saving
        binding.sendButton.isEnabled = composerEnabled
        binding.promptInput.isEnabled = composerEnabled

        binding.saveEntryButton.isVisible = journal && hasChat
        binding.saveEntryButton.isEnabled = composerEnabled
        binding.saveEntryButton.setText(if (state.saving) R.string.journal_saving else R.string.journal_save)

        adapter.submit(state.messages)
        if (hasChat) binding.messageList.scrollToPosition(state.messages.lastIndex)

        journalAdapter.submit(state.journalHistory)
        binding.journalHistoryHeader.isVisible = state.journalHistory.isNotEmpty()
    }

    override fun onDestroyView() {
        binding.messageList.adapter = null
        binding.journalHistoryList.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

private class CoachAdapter : RecyclerView.Adapter<CoachAdapter.VH>() {
    private val items = mutableListOf<CoachMessage>()

    fun submit(next: List<CoachMessage>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemCoachMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemCoachMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = items[position]
        val user = msg.role == "user"
        holder.binding.bubble.text = msg.content
        holder.binding.bubble.setBackgroundResource(
            if (user) R.drawable.bg_coach_user else R.drawable.bg_coach_assistant
        )
        holder.binding.bubble.setTextColor(
            MaterialColors.getColor(
                holder.itemView,
                if (user) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurface
            )
        )
        holder.binding.avatar.visibility = if (user) View.GONE else View.VISIBLE
        holder.binding.root.gravity = if (user) Gravity.END else Gravity.START
    }

    override fun getItemCount() = items.size
}

private class JournalAdapter(
    private val onDelete: (JournalEntryUi) -> Unit
) : RecyclerView.Adapter<JournalAdapter.VH>() {
    private val items = mutableListOf<JournalEntryUi>()
    private val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())

    fun submit(next: List<JournalEntryUi>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemJournalEntryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemJournalEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.entryTitle.text = entry.title
        holder.binding.entryDate.text = dateFmt.format(entry.createdAt)
        holder.binding.entrySummary.text = entry.summary
        holder.binding.entryChips.removeAllViews()
        entry.categories.forEach { category ->
            val chip = Chip(holder.itemView.context).apply {
                text = category.label
                isClickable = false
                isCheckable = false
                setEnsureMinTouchTargetSize(false)
            }
            holder.binding.entryChips.addView(chip)
        }
        holder.binding.root.setOnLongClickListener {
            onDelete(entry)
            true
        }
    }

    override fun getItemCount() = items.size
}
