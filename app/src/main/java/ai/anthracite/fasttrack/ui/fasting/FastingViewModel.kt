package ai.anthracite.fasttrack.ui.fasting

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.FastingGamification
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.health.GamificationStats
import ai.anthracite.fasttrack.data.health.RecoveryEngine
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.repository.FastTrackRepository
import ai.anthracite.fasttrack.session.LiveSessions
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.widget.FastTrackWidget
import android.app.Application
import kotlinx.coroutines.flow.map
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FastingViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<FastTrackApp>().repository

    val activeFast: StateFlow<FastingSession?> =
        repo.observeActiveFast().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val stats: StateFlow<GamificationStats> =
        repo.observeFinishedFasts()
            .map { FastingGamification.compute(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                FastingGamification.compute(emptyList())
            )

    /** The most recently ended fast — drives the live "eating window" countdown to the next fast. */
    val lastFinishedFast: StateFlow<FastingSession?> =
        repo.observeFinishedFasts()
            .map { list -> list.maxByOrNull { it.endEpochMs ?: it.startEpochMs } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Today's Garmin-based readiness, for the recovery hint on the active-fast card. */
    val readiness: StateFlow<RecoveryEngine.Readiness?> =
        repo.observeDailyMetrics()
            .map { RecoveryEngine.assess(it, DayKeys.today(), DayKeys.of(System.currentTimeMillis() - 86_400_000L)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedProtocol = MutableStateFlow(FastingProtocol.SIXTEEN_EIGHT)

    init {
        viewModelScope.launch {
            selectedProtocol.value = FastingProtocol.fromId(repo.getProfile().fastingProtocol)
        }
    }

    fun selectProtocol(protocol: FastingProtocol) = viewModelScope.launch {
        selectedProtocol.value = protocol
        val profile = repo.getProfile()
        repo.saveProfile(profile.copy(fastingProtocol = protocol.id))
        repo.retargetActiveFast(protocol)
        FastTrackWidget.refreshAll(getApplication())
    }

    fun start() = viewModelScope.launch {
        LiveSessions.startFast(getApplication())
    }

    /** Ends the running fast; [onEnded] gets it if one actually ended (for the celebration). */
    fun end(onEnded: (FastingSession) -> Unit) = viewModelScope.launch {
        LiveSessions.endFast(getApplication())?.let(onEnded)
    }

    /** Discards the active fast without recording it. */
    fun cancelFast() = viewModelScope.launch {
        LiveSessions.cancelFast(getApplication())
    }

    suspend fun sessionForDay(dayKey: String): FastingSession? = repo.fastForDay(dayKey)

    fun deleteFast(session: FastingSession) = viewModelScope.launch {
        repo.deleteFast(session.id)
        FastTrackWidget.refreshAll(getApplication())
    }

    fun saveTimes(
        existing: FastingSession?,
        startEpochMs: Long,
        endEpochMs: Long?,
        protocol: FastingProtocol,
        onRejected: (String) -> Unit
    ) =
        viewModelScope.launch {
            val session = (existing ?: FastingSession(
                startEpochMs = startEpochMs,
                goalHours = protocol.fastHours,
                protocol = protocol.id
            )).copy(startEpochMs = startEpochMs, endEpochMs = endEpochMs)
            val message = when (repo.saveFastTimes(session)) {
                FastTrackRepository.SaveFastResult.SAVED -> null
                FastTrackRepository.SaveFastResult.OVERLAPS ->
                    if (endEpochMs == null) "You already have a fast running — end or edit that one first."
                    else "Those times overlap another fast — edit that one instead."
                FastTrackRepository.SaveFastResult.TOO_SHORT -> "The stop time has to be after the start."
                FastTrackRepository.SaveFastResult.IN_FUTURE -> "A fast can't start or end in the future."
            }
            if (message != null) onRejected(message) else FastTrackWidget.refreshAll(getApplication())
        }
}
