package ai.anthracite.fasttrack.health

import ai.anthracite.fasttrack.data.health.ActivityDedup
import ai.anthracite.fasttrack.data.local.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityDedupTest {

    private val t0 = 1_700_000_000_000L

    @Test fun sameTypeStartedSecondsApartIsTheSameActivity() {
        assertTrue(ActivityDedup.isSameActivity("Run", t0, "Run", t0 + 8_000L))
        // Case/whitespace insensitive.
        assertTrue(ActivityDedup.isSameActivity("Run", t0, " run ", t0 + 1_000L))
    }

    @Test fun differentTypeIsNeverTheSameActivity() {
        assertFalse(ActivityDedup.isSameActivity("Run", t0, "Walk", t0))
    }

    @Test fun startsTooFarApartAreDifferentActivities() {
        assertFalse(ActivityDedup.isSameActivity("Run", t0, "Run", t0 + ActivityDedup.START_TOLERANCE_MS + 1))
    }

    @Test fun garminSupersedesAnEarlierManualAppEntry() {
        // App recorded the run (manual), then the watch imports the same run.
        val decision = ActivityDedup.decide(
            incomingSource = DataSource.GARMIN,
            existingDuplicates = listOf(42L to DataSource.MANUAL)
        )
        assertEquals(ActivityDedup.Decision.Replace(listOf(42L)), decision)
    }

    @Test fun manualDoesNotOverrideAnExistingGarminRecord() {
        // Watch already has the run; a later manual/HC copy must not double-count it.
        assertEquals(
            ActivityDedup.Decision.Skip,
            ActivityDedup.decide(DataSource.MANUAL, listOf(7L to DataSource.GARMIN))
        )
        assertEquals(
            ActivityDedup.Decision.Skip,
            ActivityDedup.decide(DataSource.HEALTH_CONNECT, listOf(7L to DataSource.GARMIN))
        )
    }

    @Test fun noExistingDuplicateMeansInsert() {
        assertEquals(
            ActivityDedup.Decision.Replace(emptyList()),
            ActivityDedup.decide(DataSource.GARMIN, emptyList())
        )
    }
}
