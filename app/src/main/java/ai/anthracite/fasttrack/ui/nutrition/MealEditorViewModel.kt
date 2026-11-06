package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.nutrition.model.EntryType
import ai.anthracite.fasttrack.nutrition.model.FoodItem
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.Iso8601
import ai.anthracite.fasttrack.nutrition.model.MealSlot
import ai.anthracite.fasttrack.util.DayKeys
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transient status shown under the parse button. */
sealed interface ParseStatus {
    data object Idle : ParseStatus
    data object Running : ParseStatus
    data class Done(val count: Int, val engine: String, val elapsedMs: Long) : ParseStatus
    data class Failed(val message: String) : ParseStatus
}

data class MealEditorState(
    val items: List<FoodItem> = emptyList(),
    val mealName: String = "",
    val mealSlot: MealSlot = MealSlot.UNSPECIFIED,
    val loggedAtMs: Long = System.currentTimeMillis(),
    val parse: ParseStatus = ParseStatus.Idle,
    val isEditing: Boolean = false
)

/**
 * Backs the multi-item meal editor. Holds the in-progress [FoodItem] list, parses free-text meals
 * via the app's [ai.anthracite.fasttrack.nutrition.estimator.MealEstimator], and persists a rich
 * [FoodLogEntry] through the repository (which mirrors items to the flat food log).
 */
class MealEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as FastTrackApp
    private val repo = application.repository

    private val _state = MutableStateFlow(MealEditorState())
    val state: StateFlow<MealEditorState> = _state.asStateFlow()

    private var entryId: String? = null
    private var createdAt: Long = System.currentTimeMillis()
    private var notes: String? = null
    private var tags: List<String> = emptyList()

    /** Load an existing entry for editing, or start fresh pinned to [initialDayMs]. */
    fun load(existingEntryId: String?, initialDayMs: Long) {
        if (existingEntryId == null) {
            _state.value = MealEditorState(loggedAtMs = initialDayMs, isEditing = false)
            return
        }
        viewModelScope.launch {
            val entry = repo.getMealEntry(existingEntryId)
            if (entry == null) {
                _state.value = MealEditorState(loggedAtMs = initialDayMs, isEditing = false)
                return@launch
            }
            entryId = entry.id
            createdAt = entry.createdAt
            notes = entry.notes
            tags = entry.tags
            _state.value = MealEditorState(
                items = entry.items,
                mealName = entry.mealName.orEmpty(),
                mealSlot = entry.mealType,
                loggedAtMs = Iso8601.parseOrNull(entry.loggedAt) ?: System.currentTimeMillis(),
                isEditing = true
            )
        }
    }

    fun setMealName(name: String) {
        _state.value = _state.value.copy(mealName = name)
    }

    fun setSlot(slot: MealSlot) {
        _state.value = _state.value.copy(mealSlot = slot)
    }

    fun setDateTime(epochMs: Long) {
        _state.value = _state.value.copy(loggedAtMs = epochMs)
    }

    /** Parse a free-text description and append the resulting items to the list. */
    fun parse(description: String) {
        val text = description.trim()
        if (text.isBlank()) return
        _state.value = _state.value.copy(parse = ParseStatus.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                application.mealEstimator.parseMeal(text)
            }
            if (!result.ok || result.items.isEmpty()) {
                _state.value = _state.value.copy(
                    parse = ParseStatus.Failed(result.error ?: "No items parsed")
                )
                return@launch
            }
            _state.value = _state.value.copy(
                items = _state.value.items + result.items,
                parse = ParseStatus.Done(result.items.size, result.engine, result.elapsedMs)
            )
        }
    }

    /** Itemize a meal photo with the on-device vision model and append the items. */
    fun parsePhoto(jpeg: ByteArray, hint: String) {
        _state.value = _state.value.copy(parse = ParseStatus.Running)
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                application.onDeviceLlm.parseMealPhoto(jpeg, hint.trim())
            }
            if (!result.ok || result.items.isEmpty()) {
                _state.value = _state.value.copy(
                    parse = ParseStatus.Failed(result.error ?: "Couldn't read that photo")
                )
                return@launch
            }
            _state.value = _state.value.copy(
                items = _state.value.items + result.items,
                parse = ParseStatus.Done(result.items.size, result.engine, result.elapsedMs)
            )
        }
    }

    fun setParseRunning() {
        _state.value = _state.value.copy(parse = ParseStatus.Running)
    }

    fun setParseFailed(message: String) {
        _state.value = _state.value.copy(parse = ParseStatus.Failed(message))
    }

    fun clearParseStatus() {
        if (_state.value.parse != ParseStatus.Idle) {
            _state.value = _state.value.copy(parse = ParseStatus.Idle)
        }
    }

    /** Insert a new item or replace an existing one (matched by id). */
    fun upsertItem(item: FoodItem) {
        val current = _state.value.items
        val idx = current.indexOfFirst { it.id == item.id }
        val next = if (idx >= 0) current.toMutableList().also { it[idx] = item }
        else current + item
        _state.value = _state.value.copy(items = next)
    }

    fun removeItem(itemId: String) {
        _state.value = _state.value.copy(items = _state.value.items.filterNot { it.id == itemId })
    }

    fun loadSample() {
        val sample = ai.anthracite.fasttrack.nutrition.model.SampleMeals.drinksAndCheesesteak()
        _state.value = _state.value.copy(
            items = _state.value.items + sample.items,
            mealName = _state.value.mealName.ifBlank { sample.mealName.orEmpty() },
            mealSlot = if (_state.value.mealSlot == MealSlot.UNSPECIFIED) sample.mealType else _state.value.mealSlot
        )
    }

    /** Build the entry from current state and persist it. [onSaved] runs on success. */
    fun save(onSaved: () -> Unit) {
        val s = _state.value
        if (s.items.isEmpty()) return
        val id = entryId ?: java.util.UUID.randomUUID().toString()
        entryId = id
        val entry = FoodLogEntry(
            id = id,
            loggedAt = Iso8601.format(s.loggedAtMs),
            entryType = if (s.items.size > 1) EntryType.MEAL else EntryType.INDIVIDUAL_ITEM,
            mealName = s.mealName.trim().ifBlank { null },
            mealType = s.mealSlot,
            items = s.items,
            notes = notes,
            tags = tags,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            repo.saveMealEntry(entry, DayKeys.of(s.loggedAtMs))
            _state.value = _state.value.copy(isEditing = true)
            onSaved()
        }
    }

    fun delete(onDeleted: () -> Unit) {
        val id = entryId ?: run { onDeleted(); return }
        viewModelScope.launch {
            repo.deleteMealEntry(id)
            onDeleted()
        }
    }

    /** Export the entire food log as a JSON envelope; returns the JSON and a suggested filename. */
    suspend fun exportAll(): Pair<String, String> =
        repo.exportFoodJson(ai.anthracite.fasttrack.nutrition.export.ExportRange.ALL_TIME) to
            repo.exportFoodFileName(ai.anthracite.fasttrack.nutrition.export.ExportRange.ALL_TIME)
}
