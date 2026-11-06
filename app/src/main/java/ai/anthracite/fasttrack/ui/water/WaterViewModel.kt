package ai.anthracite.fasttrack.ui.water

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WaterLog
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.util.UnitSystem
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

data class WaterUiState(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val totalMl: Double = 0.0,
    val goalMl: Double = 2500.0,
    val logs: List<WaterLog> = emptyList(),
    val dayKey: String = DayKeys.today(),
    val dayLabel: String = "Today",
    val isToday: Boolean = true,
    /** False when already at the join date — there are no earlier days to show. */
    val canGoBack: Boolean = true
)

class WaterViewModel(app: Application) : AndroidViewModel(app) {
    private val ft = getApplication<FastTrackApp>()
    private val repo = ft.repository

    /** Earliest day the user can navigate to — the day they joined the app. */
    private val joinDayKey = ft.prefs.joinedDayKey()

    /** The day currently being viewed/edited (defaults to today). */
    private val selectedDay = MutableStateFlow(DayKeys.today())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WaterUiState> = selectedDay.flatMapLatest { day ->
        combine(
            repo.observeProfile(),
            repo.observeWaterTotal(day),
            repo.observeWaterDay(day)
        ) { profile, total, logs ->
            val p = profile ?: UserProfile()
            WaterUiState(
                unitSystem = runCatching { UnitSystem.valueOf(p.unitSystem) }
                    .getOrDefault(UnitSystem.IMPERIAL),
                totalMl = total,
                goalMl = GuidanceEngine.waterGoalMl(p.weightKg, ft.prefs.waterGoalOverrideOz),
                logs = logs,
                dayKey = day,
                dayLabel = labelFor(day),
                isToday = day == DayKeys.today(),
                canGoBack = DayKeys.startMs(day) > DayKeys.startMs(joinDayKey)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WaterUiState())

    /** Open directly on a given day (e.g. when arrowed back from the dashboard). */
    fun setInitialDay(dayMs: Long) {
        if (dayMs <= 0L) return
        val day = DayKeys.of(dayMs)
        if (DayKeys.startMs(day) in DayKeys.startMs(joinDayKey)..System.currentTimeMillis()) {
            selectedDay.value = day
        }
    }

    fun add(amountMl: Double) = viewModelScope.launch { repo.addWaterOn(selectedDay.value, amountMl) }
    fun delete(id: Long) = viewModelScope.launch { repo.deleteWater(id) }

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
}
