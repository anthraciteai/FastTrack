package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.util.DayKeys
import java.util.concurrent.TimeUnit

/** How a calendar day should be rendered. */
enum class DayState { NONE, COMPLETED, PARTIAL }

data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    val unlocked: Boolean
)

data class GamificationStats(
    val currentStreak: Int,
    val longestStreak: Int,
    val totalCompleted: Int,
    val totalFasts: Int,
    val completedDays: Set<String>,
    val partialDays: Set<String>,
    val achievements: List<Achievement>
)

/**
 * Turns the raw fasting history into game mechanics: daily streaks, a calendar heatmap and
 * unlockable achievements. A fast is credited to the day it ended ([FastHistory.dayOf]).
 */
object FastingGamification {

    fun compute(sessions: List<FastingSession>): GamificationStats {
        val finished = sessions.filter { it.endEpochMs != null && FastHistory.isValidSpan(it) }
        val completed = finished.filter(FastHistory::isCompleted)
        val completedDays = completed.map { FastHistory.dayOf(it) }.toSortedSet()
        val finishedDays = finished.map { FastHistory.dayOf(it) }.toSortedSet()
        val partialDays = finishedDays - completedDays

        val longestProtocolHours = completed.maxOfOrNull { FastHistory.hours(it) } ?: 0.0
        val current = currentStreak(completedDays)
        val longest = longestStreak(completedDays)
        val totalCompleted = completed.size

        return GamificationStats(
            currentStreak = current,
            longestStreak = longest,
            totalCompleted = totalCompleted,
            totalFasts = finished.size,
            completedDays = completedDays,
            partialDays = partialDays,
            achievements = achievements(totalCompleted, longest, longestProtocolHours)
        )
    }

    fun dayState(dayKey: String, stats: GamificationStats): DayState = when (dayKey) {
        in stats.completedDays -> DayState.COMPLETED
        in stats.partialDays -> DayState.PARTIAL
        else -> DayState.NONE
    }

    private fun dayKeyMinus(daysAgo: Int): String =
        DayKeys.of(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysAgo.toLong()))

    private fun currentStreak(completedDays: Set<String>): Int {
        if (completedDays.isEmpty()) return 0
        // Allow the streak to "hold" if today isn't logged yet but yesterday was.
        var offset = if (dayKeyMinus(0) in completedDays) 0 else 1
        if (offset == 1 && dayKeyMinus(1) !in completedDays) return 0
        var streak = 0
        while (dayKeyMinus(offset) in completedDays) {
            streak++
            offset++
        }
        return streak
    }

    private fun longestStreak(completedDays: Set<String>): Int {
        if (completedDays.isEmpty()) return 0
        val ordered = completedDays.sorted()
        var best = 1
        var run = 1
        for (i in 1 until ordered.size) {
            if (isNextDay(ordered[i - 1], ordered[i])) run++ else run = 1
            if (run > best) best = run
        }
        return best
    }

    private fun isNextDay(a: String, b: String): Boolean {
        // dayKeys are yyyy-MM-dd; compare by parsing to epoch day difference.
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val da = fmt.parse(a)?.time ?: return false
        val db = fmt.parse(b)?.time ?: return false
        val diffDays = Math.round((db - da) / 86_400_000.0)
        return diffDays == 1L
    }

    private fun achievements(
        totalCompleted: Int,
        longestStreak: Int,
        longestProtocolHours: Double
    ): List<Achievement> = listOf(
        Achievement("first", "First Fast", "Complete your first fast", totalCompleted >= 1),
        Achievement("streak3", "On a Roll", "3-day streak", longestStreak >= 3),
        Achievement("streak7", "Week Warrior", "7-day streak", longestStreak >= 7),
        Achievement("streak30", "Unstoppable", "30-day streak", longestStreak >= 30),
        Achievement("total10", "Committed", "10 fasts completed", totalCompleted >= 10),
        Achievement("total25", "Veteran", "25 fasts completed", totalCompleted >= 25),
        Achievement("club16", "16h Club", "Finish a 16h+ fast", longestProtocolHours >= 16),
        Achievement("warrior", "Warrior", "Finish a 20h+ fast", longestProtocolHours >= 20)
    )
}
