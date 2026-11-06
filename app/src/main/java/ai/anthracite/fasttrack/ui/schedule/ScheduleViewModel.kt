package ai.anthracite.fasttrack.ui.schedule

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.data.local.ScheduleEvent
import ai.anthracite.fasttrack.scheduling.AutoScheduler
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduleViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as FastTrackApp
    private val repo = application.repository
    private val calendar = application.calendarClient

    private val slotFmt = SimpleDateFormat("EEE h:mm a", Locale.US)

    /** Snapshot of the activities we last auto-moved (original state), so the UI can offer Undo. */
    private var lastMovedOriginals: List<ScheduleEvent> = emptyList()

    val events: StateFlow<List<ScheduleEvent>> =
        repo.observeUpcoming().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addEvent(
        title: String,
        type: EventType,
        startMs: Long,
        endMs: Long,
        pushToCalendar: Boolean,
        onDone: (message: String, movedCount: Int) -> Unit
    ) = viewModelScope.launch {
        var event = ScheduleEvent(title = title, startEpochMs = startMs, endEpochMs = endMs, type = type.name)
        val id = repo.addEvent(event)
        event = event.copy(id = id)
        val base = if (pushToCalendar) {
            val remoteId = calendar.pushEvent(event)
            if (remoteId != null) {
                repo.updateEvent(event.copy(calendarEventId = remoteId, synced = true))
                "Added and synced to Google Calendar"
            } else {
                "Saved locally — connect Google Calendar to sync"
            }
        } else {
            "Added to your schedule"
        }
        // A newly added meeting/meal may collide with a planned workout — slide it automatically.
        val moved = resolveConflictsNow()
        onDone(appendMoveSummary(base, moved), moved.size)
    }

    fun syncCalendar(onDone: (message: String, movedCount: Int) -> Unit) = viewModelScope.launch {
        val status = calendar.status()
        val now = System.currentTimeMillis()
        val pulled = calendar.pullEvents(now, now + 30L * 24 * 3_600_000L)
        val imported = repo.importCalendarEvents(pulled)
        val moved = resolveConflictsNow()
        val base =
            if (pulled.isEmpty()) "Google Calendar: ${status.detail}"
            else "Imported $imported events"
        onDone(appendMoveSummary(base, moved), moved.size)
    }

    /**
     * Restores the activities moved by the most recent auto-reschedule to their original times
     * (local + calendar). Safe to call when there is nothing to undo.
     */
    fun undoReschedule(onDone: () -> Unit) = viewModelScope.launch {
        val originals = lastMovedOriginals
        lastMovedOriginals = emptyList()
        originals.forEach { original ->
            repo.updateEvent(original)
            if (original.synced && original.calendarEventId != null) calendar.updateEvent(original)
        }
        onDone()
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteEvent(id) }

    /** Applies any pending conflict moves and returns the applied moves (for messaging/undo). */
    private suspend fun resolveConflictsNow(): List<AutoScheduler.Move> {
        val moves = AutoScheduler.resolve(repo.allEvents())
        if (moves.isEmpty()) return emptyList()
        moves.forEach { move ->
            val updated = move.event.copy(startEpochMs = move.newStartMs, endEpochMs = move.newEndMs)
            repo.updateEvent(updated)
            if (updated.synced && updated.calendarEventId != null) calendar.updateEvent(updated)
        }
        lastMovedOriginals = moves.map { it.event }
        return moves
    }

    private fun appendMoveSummary(base: String, moves: List<AutoScheduler.Move>): String = when {
        moves.isEmpty() -> base
        moves.size == 1 -> {
            val m = moves.first()
            "$base · moved “${m.event.title}” to ${slotFmt.format(Date(m.newStartMs))}"
        }
        else -> "$base · rescheduled ${moves.size} activities around conflicts"
    }
}
