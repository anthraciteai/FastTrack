package ai.anthracite.fasttrack.integration

import ai.anthracite.fasttrack.data.health.ActivityDedup
import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.data.local.ExerciseDao
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.integration.garmin.GarminClient
import ai.anthracite.fasttrack.integration.healthconnect.HealthConnectSource
import ai.anthracite.fasttrack.util.DayKeys

/**
 * Orchestrates the two activity providers and resolves the "watch vs. phone" overlap problem.
 *
 * De-dup strategy (see [ActivityDedup]):
 *   - Every external activity carries (source, externalId); re-importing the same pair is ignored
 *     at the DB level (unique index + INSERT IGNORE).
 *   - A run recorded by both the watch and the phone is the *same activity* when it's the same type
 *     and started within a couple of minutes (phenomenon time, with slack for start desync). We keep
 *     a single copy from the richest source (Garmin > Health Connect > manual) and delete the rest —
 *     including an earlier manual app entry the watch later supersedes.
 */
class HealthSyncManager(
    private val garmin: GarminClient,
    private val healthConnect: HealthConnectSource,
    private val exerciseDao: ExerciseDao
) {
    val providers: List<HealthDataSource> = listOf(garmin, healthConnect)

    /** Pull from every connected provider and merge into the local DB. Returns # imported. */
    suspend fun syncRange(sinceEpochMs: Long, untilEpochMs: Long): Int {
        var imported = 0
        val all = providers.flatMap { it.fetchActivities(sinceEpochMs, untilEpochMs) }
            // Highest priority first so they win ties during de-dup.
            .sortedByDescending { ActivityDedup.priority(it.source) }

        for (activity in all) {
            // Already imported this exact record before — nothing to do.
            if (exerciseDao.findBySource(activity.source.name, activity.externalId) != null) continue

            val duplicates = exerciseDao
                .findSameTypeNearStart(activity.type, activity.startEpochMs, ActivityDedup.START_TOLERANCE_MS)
                .filter { ActivityDedup.isSameActivity(it.type, it.epochMs, activity.type, activity.startEpochMs) }
                .map { it.id to sourceOf(it) }

            when (val decision = ActivityDedup.decide(activity.source, duplicates)) {
                is ActivityDedup.Decision.Skip -> Unit
                is ActivityDedup.Decision.Replace -> {
                    decision.supersededIds.forEach { exerciseDao.delete(it) }
                    exerciseDao.insert(activity.toEntity())
                    imported++
                }
            }
        }
        return imported
    }

    private fun sourceOf(log: ExerciseLog): DataSource =
        runCatching { DataSource.valueOf(log.source) }.getOrDefault(DataSource.MANUAL)
}

fun ExternalActivity.toEntity(): ExerciseLog = ExerciseLog(
    epochMs = startEpochMs,
    dayKey = DayKeys.of(startEpochMs),
    type = type,
    durationMin = durationMin,
    calories = calories,
    distanceMeters = distanceMeters,
    avgHeartRate = avgHeartRate,
    source = source.name,
    externalId = externalId
)
