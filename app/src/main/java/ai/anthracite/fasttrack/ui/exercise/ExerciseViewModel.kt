package ai.anthracite.fasttrack.ui.exercise

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WorkoutSession
import ai.anthracite.fasttrack.session.LiveSessions
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.util.UnitSystem
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExerciseUiState(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val weightKg: Double = 80.0,
    val minutes: Int = 0,
    val calories: Int = 0,
    val sourceStatus: String = "",
    val logs: List<ExerciseLog> = emptyList()
)

class ExerciseViewModel(app: Application) : AndroidViewModel(app) {
    private val application = app as FastTrackApp
    private val repo = application.repository

    private val sourceStatus = MutableStateFlow("Checking connections…")

    val activeWorkout: StateFlow<WorkoutSession?> =
        repo.observeActiveWorkout()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<ExerciseUiState> = combine(
        repo.observeProfile(),
        repo.observeExerciseMinutesToday(),
        repo.observeExerciseCaloriesToday(),
        repo.observeExerciseRecent(),
        sourceStatus
    ) { profile, minutes, calories, logs, status ->
        val p = profile ?: UserProfile()
        ExerciseUiState(
            unitSystem = runCatching { UnitSystem.valueOf(p.unitSystem) }
                .getOrDefault(UnitSystem.IMPERIAL),
            weightKg = p.weightKg,
            minutes = minutes,
            calories = calories,
            sourceStatus = status,
            logs = logs
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExerciseUiState())

    init {
        refreshSourceStatus()
    }

    fun refreshSourceStatus() = viewModelScope.launch {
        val garmin = application.garminClient.status()
        val hc = application.healthConnectSource.status()
        sourceStatus.value = "Garmin: ${garmin.detail} · Health Connect: ${hc.detail}"
    }

    /** Pick a type and the timer starts immediately. Also drops a workout on the schedule. */
    fun startActivity(type: String, onStarted: (String) -> Unit) = viewModelScope.launch {
        if (activeWorkout.value != null) return@launch
        LiveSessions.startWorkout(application, type)
        onStarted(type)
    }

    fun stopActivity(onFinished: (ExerciseLog) -> Unit) = viewModelScope.launch {
        val log = LiveSessions.stopWorkout(application) ?: return@launch
        onFinished(log)
    }

    fun logManual(
        type: String,
        durationMin: Int,
        calories: Int,
        distanceMeters: Double?,
        avgHeartRate: Int? = null
    ) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        repo.addExercise(
            ExerciseLog(
                epochMs = now,
                dayKey = DayKeys.of(now),
                type = type,
                durationMin = durationMin,
                calories = calories,
                distanceMeters = distanceMeters,
                avgHeartRate = avgHeartRate,
                source = DataSource.MANUAL.name
            )
        )
    }

    fun delete(id: Long) = viewModelScope.launch { repo.deleteExercise(id) }
}
