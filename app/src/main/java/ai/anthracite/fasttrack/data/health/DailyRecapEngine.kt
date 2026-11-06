package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.util.DayKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RecapKind { SLEEP, ACTIVITY, STRESS, RESTING_HR, CALORIES }

/** GOOD = reinforce, WARN = gently flag, NEUTRAL = just informative. Drives the stat's color. */
enum class RecapTone { GOOD, WARN, NEUTRAL }

/** One headline number in the recap (sleep, activity, stress, resting HR) with optional context. */
data class RecapStat(
    val kind: RecapKind,
    val value: String,
    val label: String,
    val delta: String = "",
    val tone: RecapTone = RecapTone.NEUTRAL
)

/** A readable "how that day went" recap, built from the most recent fully-logged day. */
data class DayRecap(
    val hasData: Boolean = false,
    val dateLabel: String = "",
    val headline: String = "",
    val summary: String = "",
    val stats: List<RecapStat> = emptyList()
)

/**
 * Turns Garmin daily wellness + activities into an honest recap of how a *completed* day went —
 * not a pretend-live gauge. Numbers are framed with context (vs. your recent average) so they're
 * actually useful. Shared by the dashboard recap card and the Trends recap hero.
 */
object DailyRecapEngine {

    private val dayLabelFmt = SimpleDateFormat("EEE, MMM d", Locale.US)

    /** Sleep older than this (relative to the newest data we have) is treated as stale. */
    private const val STALE_SLEEP_DAYS = 2

    private fun daysBetween(fromKey: String, toKey: String): Long =
        (DayKeys.startMs(toKey) - DayKeys.startMs(fromKey)) / TimeUnit.DAYS.toMillis(1)

    /**
     * @param caloriesByDay calories *consumed* per dayKey (from the food log), so the recap can be
     *   honest about intake instead of judging a day on activity alone.
     * @param calorieTarget the user's daily calorie target (0 if unknown) for the "over/under" read.
     */
    fun recap(
        daily: List<DailyHealthMetric>,
        exercise: List<ExerciseLog>,
        caloriesByDay: Map<String, Int> = emptyMap(),
        calorieTarget: Int = 0
    ): DayRecap {
        val withData = daily.filter {
            it.sleepMinutes != null || it.stressAvg != null ||
                it.restingHeartRate != null || it.bodyBattery != null
        }
        val latestAny = withData.maxByOrNull { it.dayKey } ?: return DayRecap(hasData = false)
        val latestSleep = withData.filter { it.sleepMinutes != null }.maxByOrNull { it.dayKey }
        // Prefer the most recent completed night, but only if it's fresh. Otherwise the recap
        // would freeze on one old sleep value forever ("same sleep, same thing") — so if the last
        // sleep we have is stale (>2 days behind the newest data), fall back to the latest day and
        // let the recap reflect that day's activity/stress instead of claiming stale sleep.
        val target = if (latestSleep != null &&
            daysBetween(latestSleep.dayKey, latestAny.dayKey) <= STALE_SLEEP_DAYS
        ) latestSleep else latestAny

        val prior = withData.filter { it.dayKey < target.dayKey }.takeLast(7)
        val dayActivities = exercise.filter { it.dayKey == target.dayKey }

        val sleepH = target.sleepMinutes?.let { it / 60.0 }
        val stress = target.stressAvg
        val restingHr = target.restingHeartRate
        val activeMin = dayActivities.sumOf { it.durationMin }
        val activeKcal = dayActivities.sumOf { it.calories }
        val primaryType = dayActivities.maxByOrNull { it.durationMin }?.type
        val kcal = caloriesByDay[target.dayKey]?.takeIf { it > 0 }

        val stats = buildStats(sleepH, prior, activeMin, activeKcal, primaryType, stress, restingHr, kcal, calorieTarget)
        return DayRecap(
            hasData = true,
            dateLabel = relativeDayLabel(target.dayKey),
            headline = headline(sleepH, activeMin, stress, kcal, calorieTarget),
            summary = summary(sleepH, activeMin, activeKcal, primaryType, stress, kcal, calorieTarget),
            stats = stats
        )
    }

    /**
     * The daily update's recap: always the calendar day before [todayKey]. Recapping "the newest
     * day with data" repeated a stale day verbatim whenever the watch hadn't synced yet, and could
     * pick today's half-finished day.
     */
    fun yesterday(
        daily: List<DailyHealthMetric>,
        exercise: List<ExerciseLog>,
        caloriesByDay: Map<String, Int>,
        calorieTarget: Int,
        todayKey: String = DayKeys.today()
    ): DayRecap {
        val y = DayKeys.of(DayKeys.startMs(todayKey) - TimeUnit.HOURS.toMillis(12))
        return recapForDay(y, daily, exercise, caloriesByDay[y], calorieTarget)
    }

    /**
     * Recompute an honest recap for one specific [dayKey]. Used by the day-history view so a past
     * day reflects what actually happened (including heavy eating) instead of a frozen, possibly
     * nutrition-blind archived note.
     */
    fun recapForDay(
        dayKey: String,
        daily: List<DailyHealthMetric>,
        exercise: List<ExerciseLog>,
        kcal: Int?,
        calorieTarget: Int
    ): DayRecap {
        val metric = daily.firstOrNull {
            it.dayKey == dayKey && (it.sleepMinutes != null || it.stressAvg != null ||
                it.restingHeartRate != null || it.bodyBattery != null)
        }
        val dayActivities = exercise.filter { it.dayKey == dayKey }
        val prior = daily.filter {
            it.dayKey < dayKey &&
                (it.sleepMinutes != null || it.stressAvg != null ||
                    it.restingHeartRate != null || it.bodyBattery != null)
        }.takeLast(7)

        val sleepH = metric?.sleepMinutes?.let { it / 60.0 }
        val stress = metric?.stressAvg
        val restingHr = metric?.restingHeartRate
        val activeMin = dayActivities.sumOf { it.durationMin }
        val activeKcal = dayActivities.sumOf { it.calories }
        val primaryType = dayActivities.maxByOrNull { it.durationMin }?.type
        val k = kcal?.takeIf { it > 0 }

        if (metric == null && activeMin == 0 && k == null) return DayRecap(hasData = false)

        return DayRecap(
            hasData = true,
            dateLabel = relativeDayLabel(dayKey),
            headline = headline(sleepH, activeMin, stress, k, calorieTarget),
            summary = summary(sleepH, activeMin, activeKcal, primaryType, stress, k, calorieTarget),
            stats = buildStats(sleepH, prior, activeMin, activeKcal, primaryType, stress, restingHr, k, calorieTarget)
        )
    }

    // Calorie thresholds for an "honest" read of a logged day.
    private const val OVER_KCAL = 400          // meaningfully over target
    private const val WAY_OVER_FACTOR = 1.4    // 40%+ over target = a blowout day
    private const val UNDER_KCAL = 400         // comfortably under target

    private fun caloriesOver(kcal: Int?, target: Int): Boolean =
        target > 0 && kcal != null && kcal >= target + OVER_KCAL

    private fun caloriesWayOver(kcal: Int?, target: Int): Boolean =
        target > 0 && kcal != null && kcal >= (target * WAY_OVER_FACTOR).roundToInt()

    // ---- Headline + narrative ----------------------------------------------------------------

    private fun headline(sleepH: Double?, activeMin: Int, stress: Int?, kcal: Int?, calorieTarget: Int): String {
        val sleptWell = sleepH != null && sleepH >= 7
        val sleepShort = sleepH != null && sleepH < 6
        val lowStress = stress != null && stress <= 50
        val highStress = stress != null && stress >= 76
        val bigDay = activeMin >= 60
        val active = activeMin > 0
        val over = caloriesOver(kcal, calorieTarget)
        val wayOver = caloriesWayOver(kcal, calorieTarget)
        return when {
            // Be honest about intake first — a big eating day shouldn't read as "good & steady".
            wayOver -> "A heavy eating day"
            over && active -> "Active, but over on food"
            over -> "Over on calories"
            sleepShort -> "Short on sleep"
            bigDay && sleptWell -> "Strong day"
            bigDay -> "Big training day"
            sleptWell && lowStress -> "Restful day"
            highStress -> "A stressful one"
            active -> "Good, steady day"
            else -> "A quiet day"
        }
    }

    private fun summary(
        sleepH: Double?,
        activeMin: Int,
        activeKcal: Int,
        primaryType: String?,
        stress: Int?,
        kcal: Int?,
        calorieTarget: Int
    ): String {
        val sleepPhrase = sleepH?.let {
            val h = String.format(Locale.US, "%.1f", it)
            when {
                it >= 7 -> "slept a solid ${h}h"
                it < 6 -> "got only ${h}h of sleep"
                else -> "slept ${h}h"
            }
        }
        val activityPhrase = if (activeMin > 0) {
            val name = prettyType(primaryType)
            "${name} for $activeMin min (${activeKcal} kcal)"
        } else null

        val sentences = mutableListOf<String>()
        when {
            sleepPhrase != null && activityPhrase != null -> sentences += "You $sleepPhrase and $activityPhrase."
            activityPhrase != null -> sentences += "You $activityPhrase."
            sleepPhrase != null -> sentences += "You $sleepPhrase."
        }
        stress?.let {
            sentences += when {
                it <= 50 -> "Stress stayed low."
                it <= 75 -> "Stress was moderate."
                else -> "Stress ran high — worth an easy start today."
            }
        }
        // Intake — the part the old recap ignored entirely.
        kcal?.let {
            sentences += when {
                calorieTarget <= 0 -> "You logged $it kcal of food."
                caloriesWayOver(it, calorieTarget) ->
                    "You logged $it kcal — about ${it - calorieTarget} over your ${calorieTarget} target, so ease back in today."
                caloriesOver(it, calorieTarget) ->
                    "You logged $it kcal, around ${it - calorieTarget} over your ${calorieTarget} target."
                it <= calorieTarget - UNDER_KCAL ->
                    "You logged $it kcal, comfortably under your ${calorieTarget} target."
                else -> "You logged $it kcal, right around your ${calorieTarget} target."
            }
        }
        if (sentences.isEmpty()) sentences += "Here's what was recorded."
        return sentences.joinToString(" ")
    }

    private fun prettyType(type: String?): String {
        if (type.isNullOrBlank()) return "worked out"
        val first = type.substringBefore('/').trim().lowercase(Locale.US)
        return when (first) {
            "run", "running" -> "ran"
            "walk", "walking" -> "walked"
            "cycling", "bike", "biking" -> "cycled"
            "swim", "swimming" -> "swam"
            "strength", "weights", "lifting" -> "lifted"
            else -> "did $first"
        }
    }

    // ---- Stat chips --------------------------------------------------------------------------

    private fun buildStats(
        sleepH: Double?,
        prior: List<DailyHealthMetric>,
        activeMin: Int,
        activeKcal: Int,
        primaryType: String?,
        stress: Int?,
        restingHr: Int?,
        kcal: Int?,
        calorieTarget: Int
    ): List<RecapStat> {
        val stats = mutableListOf<RecapStat>()

        // Intake chip — leads the recap so the day's eating is front and center.
        kcal?.let { c ->
            val delta = if (calorieTarget > 0) "${signed(c - calorieTarget)} vs target" else "logged"
            val tone = when {
                calorieTarget <= 0 -> RecapTone.NEUTRAL
                caloriesOver(c, calorieTarget) -> RecapTone.WARN
                c <= calorieTarget + 200 -> RecapTone.GOOD
                else -> RecapTone.NEUTRAL
            }
            stats += RecapStat(RecapKind.CALORIES, "$c", "Calories", delta, tone)
        }

        sleepH?.let { h ->
            // Only compare to an average once we have at least a couple of nights; otherwise it's noise.
            val priorSleep = prior.mapNotNull { it.sleepMinutes }
            val priorAvg = priorSleep.takeIf { it.size >= 2 }?.average()?.div(60.0)
            val delta = priorAvg?.let { avg ->
                val d = h - avg
                if (abs(d) < 0.3) "about your avg" else "${signed(d, "h")} vs avg"
            } ?: "goal 7–9h"
            val tone = when {
                h < 6 -> RecapTone.WARN
                h >= 7 && (priorAvg == null || h >= priorAvg - 0.5) -> RecapTone.GOOD
                else -> RecapTone.NEUTRAL
            }
            stats += RecapStat(
                RecapKind.SLEEP, String.format(Locale.US, "%.1fh", h), "Sleep", delta, tone
            )
        }

        stats += if (activeMin > 0) {
            RecapStat(
                RecapKind.ACTIVITY, "$activeMin min", "Active",
                "$activeKcal kcal", RecapTone.GOOD
            )
        } else {
            RecapStat(RecapKind.ACTIVITY, "Rest", "Active", "no workouts", RecapTone.NEUTRAL)
        }

        stress?.let { s ->
            // Garmin bands: 0–25 resting, 26–50 low, 51–75 moderate, 76–100 high.
            val word = when {
                s <= 25 -> "resting"
                s <= 50 -> "low"
                s <= 75 -> "moderate"
                else -> "high"
            }
            val tone = when {
                s <= 50 -> RecapTone.GOOD
                s <= 75 -> RecapTone.NEUTRAL
                else -> RecapTone.WARN
            }
            stats += RecapStat(RecapKind.STRESS, "$s", "Avg stress", word, tone)
        }

        restingHr?.let { hr ->
            val priorHr = prior.mapNotNull { it.restingHeartRate }
            val priorAvg = priorHr.takeIf { it.size >= 2 }?.average()
            val delta = priorAvg?.let { avg ->
                val d = hr - avg
                if (abs(d) < 1) "about your avg" else "${signed(d.roundToInt())} vs avg"
            } ?: ""
            // Lower resting HR generally means better recovery.
            val tone = when {
                priorAvg == null -> RecapTone.NEUTRAL
                hr <= priorAvg - 1 -> RecapTone.GOOD
                hr >= priorAvg + 3 -> RecapTone.WARN
                else -> RecapTone.NEUTRAL
            }
            stats += RecapStat(RecapKind.RESTING_HR, "$hr", "Resting HR", delta, tone)
        }

        return stats
    }

    private fun signed(v: Int): String = if (v > 0) "+$v" else "$v"

    private fun signed(v: Double, unit: String): String {
        val s = String.format(Locale.US, "%.1f", abs(v))
        return (if (v > 0) "+$s" else "-$s") + unit
    }

    private fun relativeDayLabel(dayKey: String): String = when (dayKey) {
        DayKeys.today() -> "Today"
        DayKeys.of(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) -> "Yesterday"
        else -> runCatching { dayLabelFmt.format(Date(DayKeys.startMs(dayKey))) }
            .getOrDefault(dayKey)
    }
}
