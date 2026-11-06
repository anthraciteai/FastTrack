package ai.anthracite.fasttrack.session

/**
 * Decides whether a GPS fix should add to the tracked distance. Raw phone GPS is noisy: fixes
 * drift a few meters while you stand still, and an occasional bad fix "teleports" far away. This
 * keeps the running total honest without a map or external service. Pure so it can be unit-tested.
 */
object GpsDistanceFilter {

    /** Ignore fixes less accurate than this (meters). */
    const val MAX_ACCURACY_M = 30f

    /** Sub-this movement between fixes is treated as standing-still jitter. */
    const val MIN_SEGMENT_M = 2.0

    /** Faster than this (m/s, ~27 mph) means a bad fix, not a run/walk/ride. */
    const val MAX_SPEED_MPS = 12.0

    enum class Action {
        /** The new fix is too inaccurate — ignore it and keep the previous anchor. */
        SKIP_INACCURATE,
        /** Barely moved — ignore as jitter, keep the previous anchor. */
        SKIP_JITTER,
        /** Implausible jump — don't count it, but re-anchor to this fix. */
        REANCHOR_JUMP,
        /** A real segment — add [segmentMeters] and advance the anchor. */
        COUNT
    }

    fun classify(
        accuracyM: Float,
        hasAccuracy: Boolean,
        segmentMeters: Double,
        seconds: Double
    ): Action {
        if (hasAccuracy && accuracyM > MAX_ACCURACY_M) return Action.SKIP_INACCURATE
        if (segmentMeters < MIN_SEGMENT_M) return Action.SKIP_JITTER
        val speed = if (seconds > 0) segmentMeters / seconds else 0.0
        if (speed > MAX_SPEED_MPS) return Action.REANCHOR_JUMP
        return Action.COUNT
    }
}
