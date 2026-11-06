package ai.anthracite.fasttrack.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live GPS state for the workout in progress, shared between [WorkoutTrackingService] (which owns
 * the location updates) and the UI. A singleton so the active-workout card can show distance/pace
 * without binding to the service, and so [LiveSessions] can read the final distance when the
 * workout ends.
 */
object WorkoutTracker {

    /** Activities where GPS distance is meaningful. Strength/Yoga/etc. are time-only. */
    val DISTANCE_TYPES = setOf("Run", "Walk", "Hike", "Cycling", "Row")

    fun tracksDistance(type: String): Boolean =
        DISTANCE_TYPES.any { it.equals(type.trim(), ignoreCase = true) }

    data class State(
        val type: String,
        val startMs: Long,
        /** Accumulated GPS distance in meters. */
        val distanceMeters: Double = 0.0,
        /** True once we've had at least one usable fix (so the UI can say "acquiring GPS…"). */
        val hasFix: Boolean = false
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    val isTracking: Boolean get() = _state.value != null
    val distanceMeters: Double get() = _state.value?.distanceMeters ?: 0.0

    fun begin(type: String, startMs: Long) {
        _state.value = State(type = type, startMs = startMs)
    }

    fun addDistance(meters: Double) {
        val current = _state.value ?: return
        _state.value = current.copy(distanceMeters = current.distanceMeters + meters, hasFix = true)
    }

    fun clear() {
        _state.value = null
    }
}
