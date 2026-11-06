package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.R
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Habit streaks beyond fasting (hydration, food logging, movement, check-ins), weekly
 * challenges, and the badges they unlock. Everything is keyed by yyyy-MM-dd day keys so it can
 * be computed from aggregate queries and tested without a clock.
 */
object StreaksEngine {

    const val MOVE_MIN_PER_DAY = 30

    data class Inputs(
        val todayKey: String,
        /** Day key -> longest completed fast (hours) that started that day. */
        val completedFastHours: Map<String, Double> = emptyMap(),
        val waterGoalDays: Set<String> = emptySet(),
        val foodLoggedDays: Set<String> = emptySet(),
        val exerciseMinutes: Map<String, Int> = emptyMap(),
        val checkInDays: Set<String> = emptySet()
    ) {
        val moveDays: Set<String> get() = exerciseMinutes.filterValues { it >= MOVE_MIN_PER_DAY }.keys
    }

    data class Streak(
        val id: String,
        val title: String,
        val rule: String,
        val current: Int,
        val best: Int,
        val doneToday: Boolean,
        @param:DrawableRes val iconRes: Int,
        @param:ColorRes val colorRes: Int
    )

    data class Challenge(
        val id: String,
        val title: String,
        val description: String,
        val progress: Int,
        val target: Int,
        val unit: String
    ) {
        val completed: Boolean get() = progress >= target
        val fraction: Float get() = (progress.toFloat() / target).coerceIn(0f, 1f)
    }

    fun streaks(inputs: Inputs): List<Streak> {
        val t = inputs.todayKey
        fun make(id: String, title: String, rule: String, days: Set<String>, icon: Int, color: Int) =
            Streak(id, title, rule, current(days, t), longest(days), t in days, icon, color)
        return listOf(
            make("fasting", "Fasting", "Complete your fast", inputs.completedFastHours.keys,
                R.drawable.ic_fasting, R.color.accent_fasting),
            make("water", "Hydration", "Hit your water goal", inputs.waterGoalDays,
                R.drawable.ic_water, R.color.accent_water),
            make("logging", "Food logging", "Log at least one meal", inputs.foodLoggedDays,
                R.drawable.ic_nutrition, R.color.accent_nutrition),
            make("move", "Movement", "$MOVE_MIN_PER_DAY+ active minutes", inputs.moveDays,
                R.drawable.ic_exercise, R.color.accent_exercise),
            make("checkin", "Check-ins", "Log mood & energy", inputs.checkInDays,
                R.drawable.ic_heart, R.color.macro_protein)
        )
    }

    /** Challenges for the Monday-start week containing [dayInWeek] (defaults to today). */
    fun weeklyChallenges(inputs: Inputs, dayInWeek: String = inputs.todayKey): List<Challenge> {
        val start = weekStart(LocalDate.parse(dayInWeek))
        val week = (0L..6L).map { start.plusDays(it).toString() }.toSet()
        val fasts = inputs.completedFastHours.keys.count { it in week }
        val long = inputs.completedFastHours.count { it.key in week && it.value >= 16.0 }
        val water = inputs.waterGoalDays.count { it in week }
        val logged = inputs.foodLoggedDays.count { it in week }
        val minutes = inputs.exerciseMinutes.filterKeys { it in week }.values.sum()
        val checkIns = inputs.checkInDays.count { it in week }
        return listOf(
            Challenge("fasts5", "Five fasts", "Complete 5 fasts this week", fasts, 5, "fasts"),
            Challenge("long3", "Three 16s", "Finish three fasts of 16h or more", long, 3, "fasts"),
            Challenge("water5", "Hydration hero", "Hit your water goal on 5 days", water, 5, "days"),
            Challenge("move150", "150 club", "Get 150 active minutes", minutes.coerceAtMost(150), 150, "min"),
            Challenge("log6", "Log it all", "Log food on 6 days", logged, 6, "days"),
            Challenge("checkin5", "Check in", "Check in on 5 days", checkIns, 5, "days")
        )
    }

    /** Days left in the current week including today (Sunday = 1). */
    fun daysLeftInWeek(todayKey: String): Int {
        val today = LocalDate.parse(todayKey)
        return (ChronoUnit.DAYS.between(today, weekStart(today).plusDays(6)) + 1).toInt()
    }

    fun badges(inputs: Inputs): List<Achievement> {
        val s = streaks(inputs).associateBy { it.id }
        val pastWeeks = (0L..11L).map {
            LocalDate.parse(inputs.todayKey).minusWeeks(it).toString()
        }.map { weeklyChallenges(inputs, it) }
        val any150 = pastWeeks.any { w -> w.first { it.id == "move150" }.completed }
        val sweep = pastWeeks.any { w -> w.all { it.completed } }
        return listOf(
            Achievement("water7", "Well Watered", "7-day hydration streak", (s["water"]?.best ?: 0) >= 7),
            Achievement("log14", "Food Detective", "Log food 14 days in a row", (s["logging"]?.best ?: 0) >= 14),
            Achievement("move7", "Always Moving", "7-day movement streak", (s["move"]?.best ?: 0) >= 7),
            Achievement("checkin7", "Self-Aware", "Check in 7 days in a row", (s["checkin"]?.best ?: 0) >= 7),
            Achievement("week150", "150 Club", "150 active minutes in a week", any150),
            Achievement("sweep", "Clean Sweep", "Finish every weekly challenge", sweep)
        )
    }

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Consecutive days ending today — or yesterday, so an unlogged today doesn't break it yet. */
    fun current(days: Set<String>, todayKey: String): Int {
        if (days.isEmpty()) return 0
        var d = LocalDate.parse(todayKey)
        if (d.toString() !in days) d = d.minusDays(1)
        var n = 0
        while (d.toString() in days) {
            n++
            d = d.minusDays(1)
        }
        return n
    }

    fun longest(days: Set<String>): Int {
        if (days.isEmpty()) return 0
        val ordered = days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        var best = 1
        var run = 1
        for (i in 1 until ordered.size) {
            run = if (ChronoUnit.DAYS.between(ordered[i - 1], ordered[i]) == 1L) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }
}
