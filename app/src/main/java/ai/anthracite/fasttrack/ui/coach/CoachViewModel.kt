package ai.anthracite.fasttrack.ui.coach

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.CoachContext
import ai.anthracite.fasttrack.data.local.JournalCategory
import ai.anthracite.fasttrack.data.local.JournalEntry
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CoachMessage(val role: String, val content: String)

/** The two things the Chat tab can do. */
enum class ChatMode { COACH, JOURNAL }

/** A saved journal entry, shaped for the history list. */
data class JournalEntryUi(
    val id: Long,
    val createdAt: Long,
    val title: String,
    val summary: String,
    val categories: List<JournalCategory>,
    val mood: String?
)

data class CoachUiState(
    val mode: ChatMode = ChatMode.COACH,
    val messages: List<CoachMessage> = emptyList(),
    val busy: Boolean = false,
    /** Non-null while a journal save is being summarized. */
    val saving: Boolean = false,
    val journalHistory: List<JournalEntryUi> = emptyList()
)

/** One-shot UI event emitted after a journal entry is saved. */
sealed interface ChatEvent {
    data class JournalSaved(val title: String, val categories: List<JournalCategory>) : ChatEvent
    data class Error(val message: String) : ChatEvent
}

class CoachViewModel(app: Application) : AndroidViewModel(app) {
    private val ft = getApplication<FastTrackApp>()
    private val _state = MutableStateFlow(CoachUiState())
    val state = _state.asStateFlow()

    private val _events = MutableStateFlow<ChatEvent?>(null)
    val events = _events.asStateFlow()

    // Each mode keeps its own conversation so toggling never loses context.
    private var coachMessages = emptyList<CoachMessage>()
    private var journalMessages = emptyList<CoachMessage>()

    // Session tokens bump on new-chat / journal-save so the on-device model starts fresh;
    // they stay stable otherwise so the engine keeps context across turns.
    private var coachEpoch = 0
    private var journalEpoch = 0
    /** Guards the proactive opening briefing so it fires at most once per app session. */
    private var briefedThisSession = false
    private fun sessionId(mode: ChatMode) =
        if (mode == ChatMode.COACH) "coach-$coachEpoch" else "journal-$journalEpoch"

    init {
        viewModelScope.launch {
            ft.repository.observeJournalEntries().collect { entries ->
                _state.update { it.copy(journalHistory = entries.map { e -> e.toUi() }) }
            }
        }
    }

    fun setMode(mode: ChatMode) {
        if (_state.value.mode == mode) return
        _state.update {
            it.copy(
                mode = mode,
                messages = if (mode == ChatMode.COACH) coachMessages else journalMessages
            )
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    fun clear() {
        if (_state.value.mode == ChatMode.COACH) {
            coachMessages = emptyList()
            coachEpoch++
        } else {
            journalMessages = emptyList()
            journalEpoch++
        }
        _state.update { it.copy(messages = emptyList(), busy = false) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy || _state.value.saving) return
        val mode = _state.value.mode
        viewModelScope.launch {
            val outgoing = currentMessages(mode) + CoachMessage("user", prompt)
            if (!ft.prefs.aiFeaturesEnabled) {
                pushAssistant(mode, outgoing, offMessage(mode))
                return@launch
            }
            if (!ft.onDeviceLlm.isModelPresent) {
                val modelName = ft.onDeviceLlm.model.displayName
                pushAssistant(mode, outgoing, "Set up $modelName in Connections first, then we can ${verbFor(mode)}.")
                return@launch
            }
            setMessages(mode, outgoing, busy = true)
            val history = outgoing.map { it.role to it.content }
            val session = sessionId(mode)
            val turn = if (mode == ChatMode.JOURNAL) {
                ft.onDeviceLlm.journalReply(session, history, snapshotContext())
            } else {
                ft.onDeviceLlm.coachReply(session, history, snapshotContext())
            }
            val reply = turn.rawPreview.ifBlank {
                "NLP failed in ${turn.elapsedMs}ms: ${turn.error ?: "empty reply"}." +
                    if (turn.engineReset) " Engine was reset — send again." else ""
            }
            pushAssistant(mode, outgoing, reply)
        }
    }

    /**
     * Proactive opening check-in: when the user lands on an empty Coach, greet them with a short,
     * data-aware briefing (sleep, fasting, movement) and one thing to focus on — instead of a blank
     * box. Fires at most once per session and only when the on-device model is ready. The full
     * once-a-day "daily update" is delivered separately by the dashboard popup.
     */
    fun briefOnOpen() {
        if (briefedThisSession) return
        if (_state.value.busy || _state.value.saving) return
        if (coachMessages.isNotEmpty()) return
        if (!ft.prefs.aiFeaturesEnabled || !ft.onDeviceLlm.isModelPresent) return
        briefedThisSession = true
        viewModelScope.launch {
            // Show the typing indicator on the Coach tab while we think.
            if (_state.value.mode == ChatMode.COACH) _state.update { it.copy(busy = true) }
            val turn = ft.onDeviceLlm.coachReply(
                sessionId(ChatMode.COACH),
                listOf("user" to CoachContext.greetingInstruction()),
                snapshotContext()
            )
            val reply = turn.rawPreview
            if (reply.isNotBlank() && coachMessages.isEmpty()) {
                // Only the assistant's greeting is shown — the instruction stays behind the scenes.
                setMessages(ChatMode.COACH, listOf(CoachMessage("assistant", reply)), busy = false)
            } else if (_state.value.mode == ChatMode.COACH) {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /** End the current journal conversation: summarize, categorize, persist, then reset. */
    fun saveJournal() {
        if (_state.value.saving || _state.value.busy) return
        val convo = journalMessages
        if (convo.none { it.role == "user" }) {
            _events.value = ChatEvent.Error("Write a few thoughts before saving.")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            if (!ft.prefs.aiFeaturesEnabled || !ft.onDeviceLlm.isModelPresent) {
                // Store a raw entry even without the model so nothing is lost.
                val raw = convo.filter { it.role == "user" }.joinToString(" ") { it.content }
                persistEntry(
                    title = "Journal entry",
                    summary = raw.take(240),
                    categories = listOf(JournalCategory.OTHER),
                    mood = null,
                    convo = convo
                )
                journalMessages = emptyList()
                journalEpoch++
                _state.update { it.copy(saving = false, messages = emptyList()) }
                _events.value = ChatEvent.JournalSaved("Journal entry", listOf(JournalCategory.OTHER))
                return@launch
            }
            val history = convo.map { it.role to it.content }
            val result = ft.onDeviceLlm.summarizeJournal(history)
            persistEntry(result.title, result.summary, result.categories, result.mood, convo)
            journalMessages = emptyList()
            journalEpoch++
            _state.update { it.copy(saving = false, messages = emptyList()) }
            _events.value = ChatEvent.JournalSaved(result.title, result.categories)
        }
    }

    fun deleteEntry(id: Long) = viewModelScope.launch {
        ft.repository.deleteJournalEntry(id)
    }

    private suspend fun persistEntry(
        title: String,
        summary: String,
        categories: List<JournalCategory>,
        mood: String?,
        convo: List<CoachMessage>
    ) {
        ft.repository.addJournalEntry(
            JournalEntry(
                createdAt = System.currentTimeMillis(),
                title = title,
                summary = summary,
                categories = JournalCategory.join(categories),
                transcript = convo.joinToString("\n") { "${it.role}: ${it.content}" },
                mood = mood
            )
        )
    }

    private fun currentMessages(mode: ChatMode) =
        if (mode == ChatMode.COACH) coachMessages else journalMessages

    private fun setMessages(mode: ChatMode, messages: List<CoachMessage>, busy: Boolean) {
        if (mode == ChatMode.COACH) coachMessages = messages else journalMessages = messages
        if (_state.value.mode == mode) {
            _state.update { it.copy(messages = messages, busy = busy) }
        }
    }

    private fun pushAssistant(mode: ChatMode, outgoing: List<CoachMessage>, reply: String) {
        val next = outgoing + CoachMessage("assistant", reply)
        setMessages(mode, next, busy = false)
    }

    private fun offMessage(mode: ChatMode) = if (mode == ChatMode.JOURNAL) {
        "Turn on on-device AI in Settings to journal with a companion. Your entry will still be saved."
    } else {
        "Turn on on-device AI in Settings (open Profile from the top-right) to chat with Coach."
    }

    private fun verbFor(mode: ChatMode) = if (mode == ChatMode.JOURNAL) "journal together" else "chat"

    /** Delegates to the shared [CoachContext] so the chat, greeting, and daily update all match. */
    private suspend fun snapshotContext(): String = CoachContext.snapshot(ft)

    private fun JournalEntry.toUi() = JournalEntryUi(
        id = id,
        createdAt = createdAt,
        title = title,
        summary = summary,
        categories = JournalCategory.parse(categories),
        mood = mood
    )
}
