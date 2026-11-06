package ai.anthracite.fasttrack.integration

import ai.anthracite.fasttrack.data.local.DataSource

/**
 * A normalized activity coming from an external provider (Garmin watch or Health Connect).
 * This is the shared shape the de-duplicator and repository understand.
 */
data class ExternalActivity(
    val externalId: String,
    val source: DataSource,
    val type: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val calories: Int,
    val distanceMeters: Double? = null,
    val avgHeartRate: Int? = null
) {
    val durationMin: Int get() = ((endEpochMs - startEpochMs) / 60_000L).toInt().coerceAtLeast(0)
}

enum class ConnectionState { NOT_CONFIGURED, DISCONNECTED, CONNECTED }

data class ConnectionStatus(
    val state: ConnectionState,
    val detail: String
)

/**
 * Common contract for every activity provider. When the user has a Garmin watch we prefer it;
 * otherwise we fall back to Health Connect. Both implement this so the UI is source-agnostic.
 */
interface HealthDataSource {
    val source: DataSource

    /** Whether this provider is wired up (keys present, permissions granted, etc.). */
    suspend fun status(): ConnectionStatus

    /** Kick off the provider's auth/permission flow. Returns true if now usable. */
    suspend fun connect(): Boolean

    /** Pull activities that started within the given window. */
    suspend fun fetchActivities(sinceEpochMs: Long, untilEpochMs: Long): List<ExternalActivity>

    /** Called when the user taps "start activity" so the provider can begin a live session. */
    suspend fun startLiveSession(activityType: String): Boolean
}
