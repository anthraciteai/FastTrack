package ai.anthracite.fasttrack.integration.healthconnect

import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.integration.ConnectionState
import ai.anthracite.fasttrack.integration.ConnectionStatus
import ai.anthracite.fasttrack.integration.ExternalActivity
import ai.anthracite.fasttrack.integration.HealthDataSource
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.math.roundToInt

/**
 * Health Connect — the phone-only fallback when there is no Garmin watch.
 *
 * The permission sheet is launched from [ai.anthracite.fasttrack.ui.settings.IntegrationsFragment]
 * via [permissionContract]; this class checks availability, reads granted status, and maps
 * exercise sessions into [ExternalActivity].
 */
class HealthConnectSource(private val appContext: Context) : HealthDataSource {

    override val source: DataSource = DataSource.HEALTH_CONNECT

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(appContext)

    fun isAvailable(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    fun providerInstallIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PROVIDER_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun settingsIntent(): Intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)

    override suspend fun status(): ConnectionStatus {
        return when (sdkStatus()) {
            HealthConnectClient.SDK_UNAVAILABLE ->
                ConnectionStatus(ConnectionState.NOT_CONFIGURED, "Install Health Connect to sync phone workouts")
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                ConnectionStatus(ConnectionState.NOT_CONFIGURED, "Update Health Connect to continue")
            HealthConnectClient.SDK_AVAILABLE -> {
                if (hasAllPermissions()) {
                    ConnectionStatus(ConnectionState.CONNECTED, "Reading phone workouts")
                } else {
                    ConnectionStatus(ConnectionState.DISCONNECTED, "Tap to grant access")
                }
            }
            else -> ConnectionStatus(ConnectionState.NOT_CONFIGURED, "Health Connect unavailable")
        }
    }

    override suspend fun connect(): Boolean = hasAllPermissions()

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            val granted = client().permissionController.getGrantedPermissions()
            granted.containsAll(PERMISSIONS)
        }.getOrDefault(false)
    }

    override suspend fun fetchActivities(
        sinceEpochMs: Long,
        untilEpochMs: Long
    ): List<ExternalActivity> {
        if (!hasAllPermissions()) return emptyList()
        return runCatching {
            val client = client()
            val range = TimeRangeFilter.between(
                Instant.ofEpochMilli(sinceEpochMs),
                Instant.ofEpochMilli(untilEpochMs)
            )
            val sessions = client.readRecords(
                ReadRecordsRequest(ExerciseSessionRecord::class, range)
            ).records
            val calories = client.readRecords(
                ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range)
            ).records
            val distances = client.readRecords(
                ReadRecordsRequest(DistanceRecord::class, range)
            ).records

            sessions.map { session ->
                val start = session.startTime.toEpochMilli()
                val end = session.endTime.toEpochMilli()
                val kcal = calories.filter { overlaps(it.startTime, it.endTime, session.startTime, session.endTime) }
                    .sumOf { it.energy.inKilocalories }
                    .roundToInt()
                val meters = distances.filter { overlaps(it.startTime, it.endTime, session.startTime, session.endTime) }
                    .sumOf { it.distance.inMeters }
                ExternalActivity(
                    externalId = session.metadata.id.ifBlank { "${start}-${session.exerciseType}" },
                    source = DataSource.HEALTH_CONNECT,
                    type = typeLabel(session.exerciseType),
                    startEpochMs = start,
                    endEpochMs = end,
                    calories = kcal,
                    distanceMeters = meters.takeIf { it > 1.0 }
                )
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun startLiveSession(activityType: String): Boolean = false

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(appContext)

    private fun overlaps(aStart: Instant, aEnd: Instant, bStart: Instant, bEnd: Instant): Boolean =
        aStart < bEnd && bStart < aEnd

    companion object {
        private const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        val PERMISSIONS = setOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class)
        )

        fun permissionContract() = PermissionController.createRequestPermissionResultContract()

        fun typeLabel(exerciseType: Int): String = when (exerciseType) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Cycling"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Swim"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Row"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Soccer"
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
            else -> "Workout"
        }
    }
}
