package ai.anthracite.fasttrack.session

import ai.anthracite.fasttrack.session.GpsDistanceFilter.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDistanceFilterTest {

    @Test fun countsANormalWalkingSegment() {
        // ~5 m in 3 s (1.7 m/s) with a good fix → counts.
        assertEquals(Action.COUNT, GpsDistanceFilter.classify(8f, true, 5.0, 3.0))
    }

    @Test fun countsAFastButPlausibleRunSegment() {
        // ~18 m in 3 s (6 m/s, ~13 mph) → still a real run.
        assertEquals(Action.COUNT, GpsDistanceFilter.classify(10f, true, 18.0, 3.0))
    }

    @Test fun dropsInaccurateFixes() {
        assertEquals(Action.SKIP_INACCURATE, GpsDistanceFilter.classify(45f, true, 20.0, 3.0))
    }

    @Test fun ignoresStandingStillJitter() {
        assertEquals(Action.SKIP_JITTER, GpsDistanceFilter.classify(6f, true, 1.2, 3.0))
    }

    @Test fun reanchorsOnAnImplausibleJump() {
        // 500 m in 3 s (167 m/s) is a bad fix, not movement — re-anchor, don't count.
        assertEquals(Action.REANCHOR_JUMP, GpsDistanceFilter.classify(10f, true, 500.0, 3.0))
    }

    @Test fun missingAccuracyIsNotTreatedAsInaccurate() {
        // Some providers report no accuracy; don't discard the segment just for that.
        assertEquals(Action.COUNT, GpsDistanceFilter.classify(999f, false, 5.0, 3.0))
    }
}
