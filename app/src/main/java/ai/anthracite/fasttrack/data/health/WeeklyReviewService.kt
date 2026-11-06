package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeeklyReview
import ai.anthracite.fasttrack.data.local.WeightGoal
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.util.UnitSystem
import java.time.LocalDate

/** Everything the habit engines need, loaded once from Room. */
data class HabitHistory(
    val profile: UserProfile,
    val unit: UnitSystem,
    val fasts: List<FastingSession>,
    val caloriesByDay: Map<String, Int>,
    val waterByDay: Map<String, Double>,
    val exerciseMinutesByDay: Map<String, Int>,
    val sleepMinutesByDay: Map<String, Int>,
    val weights: List<WeightTrend.Point>,
    val checkIns: List<DailyCheckIn>,
    val calorieTarget: Int,
    val waterGoalMl: Double
) {
    /** Longest completed fast (hours) per day it ended. */
    val completedFastHours: Map<String, Double>
        get() = FastHistory.completedHoursByDay(fasts)

    /** Longest finished fast (hours) per day it ended — used to correlate with check-ins. */
    val fastHoursByEndDay: Map<String, Double>
        get() = FastHistory.finishedHoursByDay(fasts)

    fun streakInputs(todayKey: String = DayKeys.today()) = StreaksEngine.Inputs(
        todayKey = todayKey,
        completedFastHours = completedFastHours,
        waterGoalDays = if (waterGoalMl > 0) waterByDay.filterValues { it >= waterGoalMl }.keys else emptySet(),
        foodLoggedDays = caloriesByDay.filterValues { it > 0 }.keys,
        exerciseMinutes = exerciseMinutesByDay,
        checkInDays = checkIns.map { it.dayKey }.toSet()
    )

    fun weeklyInputs() = WeeklyReviewEngine.Inputs(
        fasts = fasts,
        caloriesByDay = caloriesByDay,
        calorieTarget = calorieTarget,
        waterByDay = waterByDay,
        waterGoalMl = waterGoalMl,
        exerciseMinutesByDay = exerciseMinutesByDay,
        sleepMinutesByDay = sleepMinutesByDay,
        weights = weights,
        checkIns = checkIns,
        goal = runCatching { WeightGoal.valueOf(profile.goal) }.getOrDefault(WeightGoal.LOSE),
        unit = unit
    )

    companion object {
        suspend fun load(app: FastTrackApp): HabitHistory {
            val repo = app.repository
            val profile = repo.getProfile()
            val unit = runCatching { UnitSystem.valueOf(profile.unitSystem) }.getOrDefault(UnitSystem.IMPERIAL)
            val targets = GuidanceEngine.compute(profile)
            return HabitHistory(
                profile = profile,
                unit = unit,
                fasts = repo.allFasts().filter(FastHistory::isValidSpan),
                caloriesByDay = repo.allFood().groupBy { it.dayKey }.mapValues { (_, v) -> v.sumOf { it.calories } },
                waterByDay = repo.allWater().groupBy { it.dayKey }.mapValues { (_, v) -> v.sumOf { it.amountMl } },
                exerciseMinutesByDay = repo.allExerciseLogs().groupBy { it.dayKey }
                    .mapValues { (_, v) -> v.sumOf { it.durationMin } },
                sleepMinutesByDay = repo.allDailyMetrics().mapNotNull { m -> m.sleepMinutes?.let { m.dayKey to it } }.toMap(),
                weights = repo.allWeights().map { WeightTrend.Point(it.epochMs, it.weightKg) },
                checkIns = repo.allCheckIns(),
                calorieTarget = targets.calories,
                waterGoalMl = GuidanceEngine.waterGoalMl(profile.weightKg, app.prefs.waterGoalOverrideOz)
            )
        }
    }
}

/** Builds, stores and (optionally) AI-rewrites weekly reviews. */
object WeeklyReviewService {

    suspend fun build(app: FastTrackApp, weekStart: LocalDate): WeeklyReviewEngine.Report =
        WeeklyReviewEngine.build(weekStart, HabitHistory.load(app).weeklyInputs())

    /** Compute and archive the review for [weekStart], keeping any AI text already written. */
    suspend fun generate(app: FastTrackApp, weekStart: LocalDate): WeeklyReview {
        val report = build(app, weekStart)
        val existing = app.repository.getWeeklyReview(report.weekKey)
        val review = WeeklyReview(
            weekKey = report.weekKey,
            epochMs = System.currentTimeMillis(),
            headline = report.headline,
            summary = report.summary,
            focus = report.focus,
            statsJson = report.toJson().toString(),
            aiText = existing?.aiText
        )
        app.repository.saveWeeklyReview(review)
        return review
    }

    suspend fun generateCurrent(app: FastTrackApp): WeeklyReview =
        generate(app, WeeklyReviewEngine.reviewWeekFor(LocalDate.now()))

    /** Ask the on-device model for a coach-voice version. Returns the saved text, or null. */
    suspend fun writeWithAi(app: FastTrackApp, report: WeeklyReviewEngine.Report): String? {
        val text = app.onDeviceLlm.writeWeeklyReview(report.toPrompt()) ?: return null
        val existing = app.repository.getWeeklyReview(report.weekKey) ?: generate(app, LocalDate.parse(report.weekKey))
        app.repository.saveWeeklyReview(existing.copy(aiText = text))
        return text
    }
}
