package ai.anthracite.fasttrack.ui.nutrition

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.local.FoodLog
import ai.anthracite.fasttrack.data.local.MealType
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.nutrition.estimator.NlpProbeResult
import ai.anthracite.fasttrack.nutrition.export.ExportRange
import ai.anthracite.fasttrack.util.DayKeys
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class NutritionUiState(
    val calories: Int = 0,
    val calorieGoal: Int = 1800,
    val proteinG: Int = 0,
    val proteinGoal: Int = 140,
    val carbsG: Int = 0,
    val carbsGoal: Int = 180,
    val fatG: Int = 0,
    val fatGoal: Int = 60,
    val logs: List<FoodLog> = emptyList(),
    val dayKey: String = DayKeys.today(),
    val dayLabel: String = "Today",
    val isToday: Boolean = true,
    /** False when already at the join date — there are no earlier days to show. */
    val canGoBack: Boolean = true
)

class NutritionViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<FastTrackApp>().repository

    /** Earliest day the user can navigate to — the day they joined the app. */
    private val joinDayKey = getApplication<FastTrackApp>().prefs.joinedDayKey()

    /** The day currently being viewed/edited (defaults to today). */
    private val selectedDay = MutableStateFlow(DayKeys.today())

    /** Open directly on a given day (e.g. when arrowed back from the dashboard). */
    fun setInitialDay(dayMs: Long) {
        if (dayMs <= 0L) return
        val day = DayKeys.of(dayMs)
        if (DayKeys.startMs(day) in DayKeys.startMs(joinDayKey)..System.currentTimeMillis()) {
            selectedDay.value = day
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<NutritionUiState> = selectedDay.flatMapLatest { day ->
        combine(repo.observeProfile(), repo.observeFood(day)) { profile, logs ->
            val p = profile ?: UserProfile()
            val targets = GuidanceEngine.compute(p)
            NutritionUiState(
                calories = logs.sumOf { it.calories },
                calorieGoal = targets.calories,
                proteinG = logs.sumOf { it.proteinG }.toInt(),
                proteinGoal = targets.proteinG,
                carbsG = logs.sumOf { it.carbsG }.toInt(),
                carbsGoal = targets.carbsG,
                fatG = logs.sumOf { it.fatG }.toInt(),
                fatGoal = targets.fatG,
                logs = logs,
                dayKey = day,
                dayLabel = labelFor(day),
                isToday = day == DayKeys.today(),
                canGoBack = DayKeys.startMs(day) > DayKeys.startMs(joinDayKey)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NutritionUiState())

    fun add(name: String, meal: MealType, calories: Int, p: Double, c: Double, f: Double) =
        viewModelScope.launch { repo.addFood(name, meal, calories, p, c, f, selectedDay.value) }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteFood(id) }

    /** Delete a whole rich meal entry (and its mirrored flat rows). */
    fun deleteEntry(entryId: String) = viewModelScope.launch { repo.deleteMealEntry(entryId) }

    /** Build a JSON export of the food log over [range]; returns the JSON and a suggested filename. */
    suspend fun buildFoodExport(range: ExportRange): Pair<String, String> =
        repo.exportFoodJson(range) to repo.exportFoodFileName(range)

    /** Representative timestamp for the currently-viewed day (now if today, else local noon). */
    fun selectedDayMs(): Long {
        val day = selectedDay.value
        return if (day == DayKeys.today()) System.currentTimeMillis()
        else DayKeys.startMs(day) + 12L * 60L * 60L * 1000L
    }

    /** Step back a day, but never before the join date. */
    fun goPrevDay() {
        val prev = shiftDay(selectedDay.value, -1)
        if (DayKeys.startMs(prev) >= DayKeys.startMs(joinDayKey)) selectedDay.value = prev
    }

    /** Step forward a day, but never past today. */
    fun goNextDay() {
        val next = shiftDay(selectedDay.value, 1)
        if (DayKeys.startMs(next) <= System.currentTimeMillis()) selectedDay.value = next
    }

    private fun shiftDay(day: String, delta: Int): String {
        val cal = Calendar.getInstance().apply { timeInMillis = DayKeys.startMs(day) }
        cal.add(Calendar.DAY_OF_YEAR, delta)
        return DayKeys.of(cal.timeInMillis)
    }

    private fun labelFor(day: String): String = when (day) {
        DayKeys.today() -> "Today"
        shiftDay(DayKeys.today(), -1) -> "Yesterday"
        else -> SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(DayKeys.startMs(day)))
    }

    /** Estimate macros from a free-text meal description. Null if AI is off or nothing parsed. */
    fun estimate(description: String, onResult: (NlpProbeResult) -> Unit) = viewModelScope.launch {
        if (!getApplication<FastTrackApp>().prefs.aiFeaturesEnabled) {
            onResult(
                NlpProbeResult(
                    ok = false,
                    engine = "off",
                    elapsedMs = 0,
                    estimate = null,
                    error = "On-device AI is off"
                )
            )
            return@launch
        }
        onResult(getApplication<FastTrackApp>().mealEstimator.probe(description))
    }
}
