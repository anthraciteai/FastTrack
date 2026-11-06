package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.DataSource
import kotlin.math.abs

/**
 * Rules for not double-counting the same workout when it shows up from more than one place — e.g.
 * you record a run in the app *and* your Garmin watch logs the same run.
 *
 * Two records are treated as the same activity when they are the **same type** and started at
 * nearly the same **phenomenon time** (within [START_TOLERANCE_MS], which absorbs the few seconds
 * of desync between tapping the phone and the watch starting its GPS). When that happens we keep a
 * single record, preferring the richest source (Garmin watch > Health Connect > manual app entry).
 *
 * Pure and clock-free so the dedup decision can be unit-tested without a DB.
 */
object ActivityDedup {

    /** Start-time window that still counts as "the same run started around the same time". */
    const val START_TOLERANCE_MS = 120_000L

    fun priority(source: DataSource): Int = when (source) {
        DataSource.GARMIN -> 3
        DataSource.HEALTH_CONNECT -> 2
        DataSource.MANUAL -> 1
    }

    /** Same workout: identical type (case-insensitive) and starts within [START_TOLERANCE_MS]. */
    fun isSameActivity(typeA: String, startA: Long, typeB: String, startB: Long): Boolean =
        typeA.trim().equals(typeB.trim(), ignoreCase = true) &&
            abs(startA - startB) <= START_TOLERANCE_MS

    /** What a [source] record of the same activity should do against the records already stored. */
    sealed interface Decision {
        /** A same-or-higher-priority copy already exists — drop the incoming one. */
        data object Skip : Decision
        /** The incoming record wins; these existing (lower-priority) duplicates should be removed. */
        data class Replace(val supersededIds: List<Long>) : Decision
    }

    /**
     * @param incomingSource where the new record comes from.
     * @param existingDuplicates (id, source) of stored records already judged to be the same
     *   activity (same type + near start) as the incoming one.
     */
    fun decide(incomingSource: DataSource, existingDuplicates: List<Pair<Long, DataSource>>): Decision {
        val incoming = priority(incomingSource)
        return if (existingDuplicates.any { priority(it.second) >= incoming }) {
            Decision.Skip
        } else {
            Decision.Replace(existingDuplicates.map { it.first })
        }
    }
}
