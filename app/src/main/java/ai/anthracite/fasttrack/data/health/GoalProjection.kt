package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Inspiring, non-prescriptive sketch of when the user's goal weight could arrive.
 * Hidden until they've been on the app for a week so the first days stay about showing up.
 */
object GoalProjection {

    const val MIN_DAYS = 7

    data class Card(
        val title: String,
        val body: String,
        /** True once a week has passed and we can talk dates. */
        val unlocked: Boolean
    )

    fun of(
        profile: UserProfile,
        unit: UnitSystem,
        firstOpenEpochMs: Long,
        baselineWeightKg: Double,
        now: Long = System.currentTimeMillis(),
        /** Signed weigh-in trend from [WeightTrend] (kg/week); preferred over baseline math. */
        trendKgPerWeek: Double? = null
    ): Card {
        val start = if (firstOpenEpochMs > 0) firstOpenEpochMs else now
        val days = ((now - start).coerceAtLeast(0L) / TimeUnit.DAYS.toMillis(1)).toInt()
        val goal = runCatching { WeightGoal.valueOf(profile.goal) }.getOrDefault(WeightGoal.LOSE)
        val current = profile.weightKg
        val target = profile.goalWeightKg
        val baseline = if (baselineWeightKg > 0) baselineWeightKg else current
        val targetLabel = Units.weightLabel(target, unit)

        if (days < MIN_DAYS) {
            val left = (MIN_DAYS - days).coerceAtLeast(1)
            return Card(
                title = "You're building the habit",
                body = "Day ${days.coerceAtLeast(1)}. $left more day${if (left == 1) "" else "s"} and we'll sketch when $targetLabel could be yours. Showing up today is the whole game.",
                unlocked = false
            )
        }

        return when (goal) {
            WeightGoal.MAINTAIN -> Card(
                title = "A week of showing up",
                body = "Holding around ${Units.weightLabel(current, unit)} is the goal — and you're doing it. Consistency like this is rare. Keep going.",
                unlocked = true
            )
            WeightGoal.LOSE -> trendCard(current, target, trendKgPerWeek, losing = true, unit, now, targetLabel)
                ?: loseCard(current, target, baseline, days, unit, now, targetLabel)
            WeightGoal.GAIN -> trendCard(current, target, trendKgPerWeek, losing = false, unit, now, targetLabel)
                ?: gainCard(current, target, baseline, days, unit, now, targetLabel)
        }
    }

    /** Projection from the measured weigh-in trend; null when there's no trend to lean on. */
    private fun trendCard(
        current: Double,
        target: Double,
        kgPerWeek: Double?,
        losing: Boolean,
        unit: UnitSystem,
        now: Long,
        targetLabel: String
    ): Card? {
        kgPerWeek ?: return null
        val remaining = if (losing) current - target else target - current
        if (remaining <= 0.4) return null
        val towardGoal = if (losing) -kgPerWeek else kgPerWeek
        val pace = Units.weightLabel(kotlin.math.abs(kgPerWeek), unit)
        if (towardGoal < 0.05) {
            return Card(
                title = "Your trend is holding steady",
                body = if (kgPerWeek == 0.0 || kotlin.math.abs(kgPerWeek) < 0.05) {
                    "Your weigh-ins have been flat lately. A couple of tighter eating windows this week usually gets the line moving toward $targetLabel again."
                } else {
                    "Your weigh-in trend is drifting about $pace a week the other way. One focused week turns it around — you've done harder things."
                },
                unlocked = true
            )
        }
        val eta = WeightTrend.etaFor(current, target, kgPerWeek, now) ?: return null
        val date = SimpleDateFormat("MMM d", Locale.US).format(Date(eta))
        return Card(
            title = "On trend for $targetLabel around $date",
            body = "Your weigh-ins are trending about $pace a week ${if (losing) "down" else "up"}. Keep this pace and it's on the calendar.",
            unlocked = true
        )
    }

    private fun loseCard(
        current: Double,
        target: Double,
        baseline: Double,
        days: Int,
        unit: UnitSystem,
        now: Long,
        targetLabel: String
    ): Card {
        val remaining = (current - target)
        if (remaining <= 0.4) {
            return Card(
                title = "You're there",
                body = "You're at $targetLabel. That took real work — own it.",
                unlocked = true
            )
        }
        val weeks = (days / 7.0).coerceAtLeast(1.0)
        val lost = (baseline - current).coerceAtLeast(0.0)
        val weekly = if (lost > 0.15) lost / weeks else 0.5
        val weeksLeft = (remaining / weekly.coerceAtLeast(0.15)).coerceIn(1.0, 156.0)
        val date = eta(now, weeksLeft)
        val pace = Units.weightLabel(weekly, unit)
        return Card(
            title = "You could hit $targetLabel around $date",
            body = "At about $pace a week, that's closer than it feels. Keep the streak — this version of you is already in motion.",
            unlocked = true
        )
    }

    private fun gainCard(
        current: Double,
        target: Double,
        baseline: Double,
        days: Int,
        unit: UnitSystem,
        now: Long,
        targetLabel: String
    ): Card {
        val remaining = (target - current)
        if (remaining <= 0.4) {
            return Card(
                title = "You're there",
                body = "You've reached $targetLabel. Strength like that doesn't happen by accident.",
                unlocked = true
            )
        }
        val weeks = (days / 7.0).coerceAtLeast(1.0)
        val gained = (current - baseline).coerceAtLeast(0.0)
        val weekly = if (gained > 0.15) gained / weeks else 0.25
        val weeksLeft = (remaining / weekly.coerceAtLeast(0.1)).coerceIn(1.0, 156.0)
        val date = eta(now, weeksLeft)
        val pace = Units.weightLabel(weekly, unit)
        return Card(
            title = "You could hit $targetLabel around $date",
            body = "At about $pace a week of solid gain, you're on a real path. Keep feeding the work.",
            unlocked = true
        )
    }

    private fun eta(now: Long, weeksLeft: Double): String {
        val ms = now + (weeksLeft * 7 * TimeUnit.DAYS.toMillis(1)).toLong()
        return SimpleDateFormat("MMM d", Locale.US).format(Date(ms))
    }

    fun restingBurnedSoFar(bmrKcal: Int, now: Long = System.currentTimeMillis()): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return ((bmrKcal * minutes) / (24.0 * 60.0)).toInt().coerceAtLeast(0)
    }
}
