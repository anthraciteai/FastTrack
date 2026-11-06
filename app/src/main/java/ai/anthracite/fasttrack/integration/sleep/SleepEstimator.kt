package ai.anthracite.fasttrack.integration.sleep

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Estimates last night's sleep from phone-usage gaps for the (common) case where the user forgot
 * to wear their Garmin watch, so no sleep was recorded.
 *
 * The idea: everyone puts their phone down to sleep and picks it up / unlocks it first thing in
 * the morning. We scan the phone's usage events over the last ~30 hours, keep only the events
 * that mean a human was actually present (screen on/off, unlock, foreground app, touch) and throw
 * away the background noise (notifications posted while you sleep, standby-bucket changes, etc.)
 * that would otherwise fill the night with fake "activity." Then we find the longest stretch with
 * no presence that *straddles night into morning* and treat the last touch before it as a bedtime
 * proxy and the first touch after it as a wake proxy. It's only an estimate the user confirms —
 * never silently trusted.
 *
 * Requires the one-time "Usage access" special permission (granted from system Settings).
 */
object SleepEstimator {

    private const val TAG = "SleepEstimator"

    /** A single night's estimated sleep window. */
    data class SleepEstimate(val bedtimeMs: Long, val wakeMs: Long) {
        val minutes: Int get() = ((wakeMs - bedtimeMs) / 60_000L).toInt().coerceAtLeast(0)
    }

    private val MIN_GAP_MS = TimeUnit.HOURS.toMillis(3)
    private val MAX_GAP_MS = TimeUnit.HOURS.toMillis(14)
    private val LOOKBACK_MS = TimeUnit.HOURS.toMillis(30)

    /**
     * Event types that mean a person was actually interacting with the phone. Everything else
     * (notifications, standby-bucket changes, background syncs) can fire while you're asleep and
     * must be ignored or they erase the overnight gap. All of these constants exist on API 29+.
     */
    private val PRESENCE_EVENTS = setOf(
        UsageEvents.Event.ACTIVITY_RESUMED,      // app brought to foreground
        UsageEvents.Event.ACTIVITY_PAUSED,       // app left the foreground
        UsageEvents.Event.SCREEN_INTERACTIVE,    // screen turned on (pickup)
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,// screen turned off (put down)
        UsageEvents.Event.KEYGUARD_HIDDEN,       // device unlocked
        UsageEvents.Event.USER_INTERACTION       // direct user touch
    )

    /** True once the user has granted Usage Access to FastTrack. */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** System screen where the user flips the Usage Access switch for FastTrack. */
    fun usageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Estimate the night that just ended, or null if we can't (no access, too few events, or no
     * plausible overnight gap). [now] is injectable for testing.
     */
    fun estimateLastNight(context: Context, now: Long = System.currentTimeMillis()): SleepEstimate? {
        if (!hasUsageAccess(context)) {
            Log.d(TAG, "no usage access")
            return null
        }
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val events = runCatching { usm.queryEvents(now - LOOKBACK_MS, now) }.getOrNull() ?: return null

        val presence = ArrayList<Long>(256)
        val all = ArrayList<Long>(256)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            all.add(event.timeStamp)
            if (event.eventType in PRESENCE_EVENTS) presence.add(event.timeStamp)
        }

        // Prefer the clean presence stream; fall back to all events on devices that don't surface
        // screen/keyguard events so we still have something to work with.
        val times = (if (presence.size >= 2) presence else all).also { it.sort() }
        Log.d(TAG, "events: all=${all.size} presence=${presence.size} using=${times.size}")
        if (times.size < 2) return null

        // Collect every gap that straddles night -> morning, then take the longest. Picking the
        // single biggest gap first (as before) failed whenever a longer daytime gap existed,
        // because it would be chosen and then rejected by the night/morning sanity check.
        var bedtime = 0L
        var wake = 0L
        var bestGap = 0L
        for (i in 1 until times.size) {
            val gap = times[i] - times[i - 1]
            if (gap < MIN_GAP_MS || gap > MAX_GAP_MS) continue
            if (hourOf(times[i - 1]) !in NIGHT_HOURS) continue
            if (hourOf(times[i]) !in MORNING_HOURS) continue
            if (gap > bestGap) {
                bestGap = gap
                bedtime = times[i - 1]
                wake = times[i]
            }
        }
        if (bestGap <= 0L) {
            Log.d(TAG, "no plausible overnight gap found")
            return null
        }
        Log.d(TAG, "estimate: bed=${hourOf(bedtime)}h wake=${hourOf(wake)}h gap=${bestGap / 3_600_000.0}h")
        return SleepEstimate(bedtime, wake)
    }

    private val NIGHT_HOURS = (19..23).toSet() + (0..4).toSet()
    private val MORNING_HOURS = (3..12).toSet()

    private fun hourOf(ms: Long): Int =
        Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY)
}
