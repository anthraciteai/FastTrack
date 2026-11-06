package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Builds the weekly coach review: this week (Mon–Sun) versus the week before, across fasting,
 * food, water, movement, sleep, weight and check-ins, plus a few patterns and one focus.
 * Deterministic and offline; the on-device LLM can optionally rewrite it in a warmer voice.
 */
object WeeklyReviewEngine {

    data class Inputs(
        val fasts: List<FastingSession> = emptyList(),
        val caloriesByDay: Map<String, Int> = emptyMap(),
        val calorieTarget: Int = 0,
        val waterByDay: Map<String, Double> = emptyMap(),
        val waterGoalMl: Double = 0.0,
        val exerciseMinutesByDay: Map<String, Int> = emptyMap(),
        val sleepMinutesByDay: Map<String, Int> = emptyMap(),
        val weights: List<WeightTrend.Point> = emptyList(),
        val checkIns: List<DailyCheckIn> = emptyList(),
        val goal: WeightGoal = WeightGoal.LOSE,
        val unit: UnitSystem = UnitSystem.IMPERIAL,
        val zone: ZoneId = ZoneId.systemDefault()
    )

    data class WeekStats(
        val fastsStarted: Int,
        val fastsCompleted: Int,
        val avgFastHours: Double?,
        val longestFastHours: Double?,
        val daysLogged: Int,
        val avgCalories: Int?,
        val daysOverTarget: Int,
        val waterGoalDays: Int,
        val activeMinutes: Int,
        val avgSleepHours: Double?,
        /** Change in smoothed trend weight across the week (kg, signed). */
        val weightChangeKg: Double?,
        val avgMood: Double?,
        val avgEnergy: Double?
    )

    data class Report(
        val weekKey: String,
        val rangeLabel: String,
        val stats: WeekStats,
        val previous: WeekStats,
        val headline: String,
        val wins: List<String>,
        val watchOuts: List<String>,
        val patterns: List<String>,
        val focus: String
    ) {
        val summary: String get() = (wins + watchOuts).joinToString(" ")

        /** Fact sheet handed to the LLM; it may only use what's in here. */
        fun toPrompt(): String = buildString {
            appendLine("Week: $rangeLabel")
            appendLine("Headline: $headline")
            if (wins.isNotEmpty()) appendLine("Wins: ${wins.joinToString(" ")}")
            if (watchOuts.isNotEmpty()) appendLine("Watch-outs: ${watchOuts.joinToString(" ")}")
            if (patterns.isNotEmpty()) appendLine("Patterns: ${patterns.joinToString(" ")}")
            append("Focus for next week: $focus")
        }

        fun toJson(): JSONObject = JSONObject()
            .put("weekKey", weekKey)
            .put("range", rangeLabel)
            .put("stats", stats.toJson())
            .put("previous", previous.toJson())
            .put("wins", JSONArray(wins))
            .put("watchOuts", JSONArray(watchOuts))
            .put("patterns", JSONArray(patterns))
    }

    private fun WeekStats.toJson(): JSONObject = JSONObject()
        .put("fastsStarted", fastsStarted)
        .put("fastsCompleted", fastsCompleted)
        .put("avgFastHours", avgFastHours ?: JSONObject.NULL)
        .put("longestFastHours", longestFastHours ?: JSONObject.NULL)
        .put("daysLogged", daysLogged)
        .put("avgCalories", avgCalories ?: JSONObject.NULL)
        .put("daysOverTarget", daysOverTarget)
        .put("waterGoalDays", waterGoalDays)
        .put("activeMinutes", activeMinutes)
        .put("avgSleepHours", avgSleepHours ?: JSONObject.NULL)
        .put("weightChangeKg", weightChangeKg ?: JSONObject.NULL)
        .put("avgMood", avgMood ?: JSONObject.NULL)
        .put("avgEnergy", avgEnergy ?: JSONObject.NULL)

    /** Week that a review should cover on [today]: this week on Sunday, otherwise last week. */
    fun reviewWeekFor(today: LocalDate): LocalDate {
        val start = StreaksEngine.weekStart(today)
        return if (today.dayOfWeek == DayOfWeek.SUNDAY) start else start.minusWeeks(1)
    }

    fun build(weekStart: LocalDate, inputs: Inputs): Report {
        val stats = statsFor(weekStart, inputs)
        val prev = statsFor(weekStart.minusWeeks(1), inputs)
        val wins = wins(stats, prev, inputs)
        val watch = watchOuts(stats, prev, inputs)
        val patterns = patterns(weekStart, inputs)
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return Report(
            weekKey = weekStart.toString(),
            rangeLabel = "${weekStart.format(fmt)} – ${weekStart.plusDays(6).format(fmt)}",
            stats = stats,
            previous = prev,
            headline = headline(stats, prev, inputs),
            wins = wins,
            watchOuts = watch,
            patterns = patterns,
            focus = focus(stats, inputs, patterns)
        )
    }

    fun statsFor(weekStart: LocalDate, inputs: Inputs): WeekStats {
        val days = (0L..6L).map { weekStart.plusDays(it).toString() }
        val daySet = days.toSet()
        val weekFasts = inputs.fasts.filter { it.endEpochMs != null && FastHistory.dayOf(it, inputs.zone) in daySet }
        val hours = weekFasts.map { FastHistory.hours(it) }
        val cals = days.mapNotNull { d -> inputs.caloriesByDay[d]?.takeIf { it > 0 } }
        val sleep = days.mapNotNull { inputs.sleepMinutesByDay[it] }
        val checks = inputs.checkIns.filter { it.dayKey in daySet }

        val startMs = weekStart.atStartOfDay(inputs.zone).toInstant().toEpochMilli()
        val endMs = weekStart.plusDays(7).atStartOfDay(inputs.zone).toInstant().toEpochMilli()
        val trend = WeightTrend.compute(inputs.weights, null, endMs)?.smoothed.orEmpty()
        val before = trend.lastOrNull { it.epochMs < startMs }
        val inWeek = trend.filter { it.epochMs in startMs until endMs }
        val weightChange = when {
            inWeek.isEmpty() -> null
            before != null -> inWeek.last().kg - before.kg
            inWeek.size >= 2 -> inWeek.last().kg - inWeek.first().kg
            else -> null
        }

        return WeekStats(
            fastsStarted = weekFasts.size,
            fastsCompleted = weekFasts.count(FastHistory::isCompleted),
            avgFastHours = hours.takeIf { it.isNotEmpty() }?.average(),
            longestFastHours = hours.maxOrNull(),
            daysLogged = cals.size,
            avgCalories = cals.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            daysOverTarget = if (inputs.calorieTarget > 0) cals.count { it > inputs.calorieTarget * 1.1 } else 0,
            waterGoalDays = if (inputs.waterGoalMl > 0) days.count { (inputs.waterByDay[it] ?: 0.0) >= inputs.waterGoalMl } else 0,
            activeMinutes = days.sumOf { inputs.exerciseMinutesByDay[it] ?: 0 },
            avgSleepHours = sleep.takeIf { it.isNotEmpty() }?.average()?.div(60.0),
            weightChangeKg = weightChange,
            avgMood = checks.takeIf { it.isNotEmpty() }?.map { it.mood }?.average(),
            avgEnergy = checks.takeIf { it.isNotEmpty() }?.map { it.energy }?.average()
        )
    }

    private fun headline(s: WeekStats, p: WeekStats, inputs: Inputs): String {
        val parts = mutableListOf<String>()
        parts += "${s.fastsCompleted} fast${if (s.fastsCompleted == 1) "" else "s"} completed"
        s.weightChangeKg?.takeIf { abs(it) >= 0.1 }?.let {
            parts += "${Units.weightLabel(abs(it), inputs.unit)} ${if (it < 0) "down" else "up"}"
        }
        if (s.activeMinutes > 0) parts += "${s.activeMinutes} active min"
        val tone = when {
            s.fastsCompleted >= 5 && s.fastsCompleted >= p.fastsCompleted -> " — a strong week"
            s.fastsCompleted > p.fastsCompleted -> " — trending up"
            s.fastsCompleted == 0 && s.daysLogged == 0 -> " — a quiet week"
            else -> ""
        }
        return parts.joinToString(" · ") + tone
    }

    private fun wins(s: WeekStats, p: WeekStats, inputs: Inputs): List<String> {
        val out = mutableListOf<String>()
        if (s.fastsCompleted > 0 && s.fastsCompleted > p.fastsCompleted) {
            out += "You completed ${s.fastsCompleted} fasts, up from ${p.fastsCompleted} the week before."
        } else if (s.fastsCompleted >= 5) {
            out += "You completed ${s.fastsCompleted} fasts — that's real consistency."
        }
        s.longestFastHours?.takeIf { it >= 16 }?.let {
            out += "Your longest fast was ${fmt1(it)}h."
        }
        val towardGoal = s.weightChangeKg?.let {
            when (inputs.goal) {
                WeightGoal.LOSE -> it <= -0.1
                WeightGoal.GAIN -> it >= 0.1
                WeightGoal.MAINTAIN -> abs(it) < 0.3
            }
        } ?: false
        if (towardGoal && s.weightChangeKg != null) {
            out += if (inputs.goal == WeightGoal.MAINTAIN) "Your weight held steady."
            else "Your trend weight moved ${Units.weightLabel(abs(s.weightChangeKg), inputs.unit)} toward your goal."
        }
        if (s.waterGoalDays >= 5) out += "You hit your water goal on ${s.waterGoalDays} days."
        if (s.activeMinutes >= 150) out += "${s.activeMinutes} active minutes — you cleared the 150-minute mark."
        if (s.daysLogged >= 6) out += "You logged food on ${s.daysLogged} of 7 days."
        return out.take(4)
    }

    private fun watchOuts(s: WeekStats, p: WeekStats, inputs: Inputs): List<String> {
        val out = mutableListOf<String>()
        if (s.daysOverTarget >= 3) out += "Calories ran over target on ${s.daysOverTarget} days."
        if (p.fastsCompleted >= 3 && s.fastsCompleted < p.fastsCompleted - 1) {
            out += "Fasts dropped to ${s.fastsCompleted} from ${p.fastsCompleted}."
        }
        val broken = s.fastsStarted - s.fastsCompleted
        if (broken >= 2) out += "$broken fasts ended before the goal."
        s.avgSleepHours?.takeIf { it < 6.5 }?.let { out += "Sleep averaged ${fmt1(it)}h — under the 7h mark." }
        if (inputs.waterGoalMl > 0 && s.waterGoalDays <= 2) out += "Water goal only hit on ${s.waterGoalDays} days."
        val awayFromGoal = s.weightChangeKg?.let {
            (inputs.goal == WeightGoal.LOSE && it >= 0.3) || (inputs.goal == WeightGoal.GAIN && it <= -0.3)
        } ?: false
        if (awayFromGoal && s.weightChangeKg != null) {
            out += "Trend weight drifted ${Units.weightLabel(abs(s.weightChangeKg), inputs.unit)} the other way."
        }
        return out.take(3)
    }

    /** Repeating patterns over the last four weeks ending with [weekStart]'s week. */
    fun patterns(weekStart: LocalDate, inputs: Inputs): List<String> {
        val out = mutableListOf<String>()
        val from = weekStart.minusWeeks(3)
        val to = weekStart.plusDays(6)
        val window = inputs.fasts.filter { f ->
            f.endEpochMs != null && LocalDate.parse(FastHistory.dayOf(f, inputs.zone)).let { it in from..to }
        }
        val brokenByDow = window.filterNot(FastHistory::isCompleted)
            .groupingBy { LocalDate.parse(FastHistory.dayOf(it, inputs.zone)).dayOfWeek }.eachCount()
        brokenByDow.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { (dow, n) ->
            out += "Your fasts most often end early on ${plural(dow)} ($n times in 4 weeks)."
        }

        val days = (0L..6L).map { weekStart.plusDays(it) }
        val weekend = days.filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
            .mapNotNull { inputs.caloriesByDay[it.toString()]?.takeIf { c -> c > 0 } }
        val weekday = days.filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .mapNotNull { inputs.caloriesByDay[it.toString()]?.takeIf { c -> c > 0 } }
        if (weekend.isNotEmpty() && weekday.size >= 3) {
            val we = weekend.average()
            val wd = weekday.average()
            if (wd > 0 && we > wd * 1.2) {
                out += "Weekend calories ran ${((we / wd - 1) * 100).roundToInt()}% higher than weekdays."
            }
        }

        val sleepEnergy = inputs.checkIns.mapNotNull { c ->
            inputs.sleepMinutesByDay[c.dayKey]?.let { it to c.energy }
        }
        val shortNights = sleepEnergy.filter { it.first < 360 }.map { it.second }
        val goodNights = sleepEnergy.filter { it.first >= 420 }.map { it.second }
        if (shortNights.size >= 2 && goodNights.size >= 2 && goodNights.average() - shortNights.average() >= 0.8) {
            out += "On mornings after under 6h of sleep your energy check-in averages " +
                "${fmt1(shortNights.average())} vs ${fmt1(goodNights.average())} after 7h+."
        }
        return out
    }

    private fun focus(s: WeekStats, inputs: Inputs, patterns: List<String>): String = when {
        s.avgSleepHours != null && s.avgSleepHours < 6.5 ->
            "Protect sleep: lights out 30 minutes earlier on at least 4 nights."
        patterns.any { it.startsWith("Your fasts most often end early") } -> {
            val day = patterns.first { it.startsWith("Your fasts most often end early") }
                .substringAfter("early on ").substringBefore(" (")
            "Plan $day ahead — shorten that day's fast or eat a bigger last meal so it doesn't break early."
        }
        s.fastsCompleted < 4 -> "Complete ${(s.fastsCompleted + 2).coerceAtMost(7)} fasts — two more than this week."
        inputs.waterGoalMl > 0 && s.waterGoalDays < 4 -> "Hit your water goal on 5 days. Start each morning with a full glass."
        s.daysOverTarget >= 3 -> "Keep at least 5 days within your calorie target — pre-log dinner on busy days."
        s.activeMinutes < 150 -> "Reach 150 active minutes — three 20-minute walks get you most of the way."
        else -> "Repeat this week. Same windows, same habits — consistency compounds."
    }

    private fun plural(dow: DayOfWeek): String = dow.getDisplayName(TextStyle.FULL, Locale.US) + "s"

    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
