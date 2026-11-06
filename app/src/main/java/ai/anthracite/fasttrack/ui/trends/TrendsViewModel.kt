package ai.anthracite.fasttrack.ui.trends

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.health.Recommendation
import ai.anthracite.fasttrack.data.health.RecommendationEngine
import ai.anthracite.fasttrack.data.health.DailyRecapEngine
import ai.anthracite.fasttrack.data.health.DayRecap
import ai.anthracite.fasttrack.data.health.TrainingAnalytics
import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeightLog
import ai.anthracite.fasttrack.data.health.WeightTrend
import ai.anthracite.fasttrack.data.local.WorkoutSession
import ai.anthracite.fasttrack.session.LiveSessions
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ChartMode { LINE, BAR }

enum class GroupBy { SESSION, WEEK }

enum class TrendMetric(val label: String) {
    DISTANCE("Distance"),
    PACE("Pace"),
    DURATION("Duration"),
    CALORIES("Calories"),
    AVG_HR("Avg HR"),
    BODY_BATTERY("Body Battery"),
    RESTING_HR("Resting HR"),
    STRESS("Stress"),
    SLEEP("Sleep"),
    WEIGHT("Weight");

    /** Metrics backed by Garmin daily wellness snapshots rather than per-activity logs. */
    val isDaily: Boolean
        get() = this == BODY_BATTERY || this == RESTING_HR || this == STRESS || this == SLEEP

    /** Only a watch can produce these; sleep can also be logged by hand. */
    val needsWatch: Boolean
        get() = this == BODY_BATTERY || this == RESTING_HR || this == STRESS
}

data class TrendsUiState(
    val unit: UnitSystem = UnitSystem.IMPERIAL,
    val metric: TrendMetric = TrendMetric.DISTANCE,
    val activity: String = ALL,
    val activities: List<String> = listOf(ALL),
    val showActivityFilter: Boolean = true,
    val values: List<Float> = emptyList(),
    val labels: List<String> = emptyList(),
    val mode: ChartMode = ChartMode.BAR,
    val accentColorRes: Int = R.color.accent_exercise,
    val caption: String = "",
    val summaryBest: String = "–",
    val summaryBestLabel: String = "Best",
    val summaryAvg: String = "–",
    val trendPct: Int = 0,
    val trendImproved: Boolean = true,
    val hasTrend: Boolean = false,
    val emptyMessage: String? = null,
    val groupBy: GroupBy = GroupBy.SESSION,
    val showGroupToggle: Boolean = true,
    val recommendations: List<Recommendation> = emptyList(),
    val recap: DayRecap = DayRecap()
) {
    companion object { const val ALL = "All" }
}

class TrendsViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as FastTrackApp
    private val repo = application.repository
    private val dateFmt = SimpleDateFormat("M/d", Locale.US)

    /** The live workout (if any) so the Movement tab can show a started walk/run immediately. */
    val activeWorkout: StateFlow<WorkoutSession?> =
        repo.observeActiveWorkout()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Finish the live workout, which writes the ExerciseLog that feeds these charts. */
    fun finishActiveWorkout(onFinished: (ExerciseLog) -> Unit) = viewModelScope.launch {
        val log = LiveSessions.stopWorkout(application) ?: return@launch
        onFinished(log)
    }

    /** Watch-only metrics that have at least one day of data. */
    val watchMetricsWithData: StateFlow<Set<TrendMetric>> = repo.observeDailyMetrics()
        .map { daily ->
            TrendMetric.entries.filter { m -> m.needsWatch && daily.any { dailyValue(m, it) != null } }.toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val selectedMetric = MutableStateFlow(TrendMetric.DISTANCE)
    private val selectedActivity = MutableStateFlow(TrendsUiState.ALL)
    private val selectedGroupBy = MutableStateFlow(GroupBy.SESSION)

    private companion object { const val WINDOW = 20 }

    private data class Selections(
        val metric: TrendMetric,
        val activity: String,
        val groupBy: GroupBy
    )

    val state: StateFlow<TrendsUiState> = combine(
        repo.observeProfile(),
        repo.observeExerciseRecent(),
        repo.observeDailyMetrics(),
        repo.observeAllWeights(),
        combine(selectedMetric, selectedActivity, selectedGroupBy) { m, a, g -> Selections(m, a, g) }
    ) { profile, exercise, daily, weights, sel ->
        build(profile ?: UserProfile(), exercise, daily, weights, sel.metric, sel.activity, sel.groupBy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TrendsUiState())

    fun selectMetric(metric: TrendMetric) { selectedMetric.value = metric }
    fun selectActivity(activity: String) { selectedActivity.value = activity }
    fun selectGroupBy(groupBy: GroupBy) { selectedGroupBy.value = groupBy }

    private fun build(
        profile: UserProfile,
        exercise: List<ExerciseLog>,
        daily: List<DailyHealthMetric>,
        weights: List<WeightLog>,
        metric: TrendMetric,
        rawActivity: String,
        groupBy: GroupBy
    ): TrendsUiState {
        val unit = runCatching { UnitSystem.valueOf(profile.unitSystem) }
            .getOrDefault(UnitSystem.IMPERIAL)

        val activities = listOf(TrendsUiState.ALL) +
            exercise.map { it.type }.distinct().sorted()
        val activity = if (rawActivity in activities) rawActivity else TrendsUiState.ALL

        val accentRes = when (metric) {
            TrendMetric.AVG_HR, TrendMetric.RESTING_HR -> R.color.macro_protein
            TrendMetric.STRESS -> R.color.accent_fasting
            TrendMetric.BODY_BATTERY -> R.color.accent_nutrition
            TrendMetric.SLEEP -> R.color.md_tertiary
            TrendMetric.WEIGHT -> R.color.accent_fasting
            else -> R.color.accent_exercise
        }

        val recommendations = buildRecommendations(profile, exercise, unit)
        val recap = DailyRecapEngine.recap(daily, exercise)
        val sumMetric = metric == TrendMetric.DISTANCE ||
            metric == TrendMetric.DURATION || metric == TrendMetric.CALORIES

        // ---- Body weight comes from weigh-ins; caption carries the regression trend + ETA ----
        if (metric == TrendMetric.WEIGHT) {
            fun display(kg: Double) = if (unit == UnitSystem.IMPERIAL) Units.kgToLb(kg) else kg
            val points = weights.sortedBy { it.epochMs }.map { it.epochMs to display(it.weightKg).toFloat() }
            if (points.isEmpty()) {
                return TrendsUiState(
                    unit = unit, metric = metric, activity = activity, activities = activities,
                    showActivityFilter = false, showGroupToggle = false, groupBy = groupBy,
                    mode = ChartMode.LINE, accentColorRes = accentRes,
                    emptyMessage = "Log a weigh-in from the dashboard to start your weight trend.",
                    recommendations = recommendations, recap = recap
                )
            }
            val trend = WeightTrend.compute(
                weights.map { WeightTrend.Point(it.epochMs, it.weightKg) }, profile.goalWeightKg
            )
            val u = if (unit == UnitSystem.IMPERIAL) "lb" else "kg"
            val caption = trend?.kgPerWeek?.let { rate ->
                val eta = trend.etaEpochMs?.let { " · goal ~${SimpleDateFormat("MMM d", Locale.US).format(it)}" }.orEmpty()
                String.format(Locale.US, "Trend %+.1f %s/week", display(rate), u) + eta
            } ?: "Weigh in a few times over a week to see your trend"
            val losing = profile.goal == ai.anthracite.fasttrack.data.local.WeightGoal.LOSE.name
            val (values, labels) = seriesFor(points, groupBy, sum = false)
            return finishSeries(
                unit, metric, activity, activities, showActivityFilter = false, groupBy = groupBy,
                showGroupToggle = true, values = values, labels = labels, mode = ChartMode.LINE,
                accentRes = accentRes, lowerIsBetter = losing, caption = caption,
                formatter = { String.format(Locale.US, "%.1f %s", it, u) },
                bestLabel = if (losing) "Lowest" else "Highest",
                recommendations = recommendations, recap = recap
            )
        }

        // ---- Wellness metrics come from Garmin daily snapshots, not per-activity logs ----
        if (metric.isDaily) {
            val points = daily.mapNotNull { m -> dailyValue(metric, m)?.let { m.epochMs to it } }
            if (points.isEmpty()) {
                return TrendsUiState(
                    unit = unit, metric = metric, activity = activity, activities = activities,
                    showActivityFilter = false, showGroupToggle = false, groupBy = groupBy,
                    mode = modeFor(metric), accentColorRes = accentRes,
                    emptyMessage = if (metric == TrendMetric.SLEEP) {
                        "Turn on the morning sleep check in Settings to log sleep, or connect a watch."
                    } else {
                        "Connect your Garmin watch to track your ${metric.label.lowercase(Locale.US)}."
                    },
                    recommendations = recommendations, recap = recap
                )
            }
            val lowerIsBetter = metric == TrendMetric.STRESS || metric == TrendMetric.RESTING_HR
            val (values, labels) = seriesFor(points, groupBy, sum = false)
            return finishSeries(
                unit, metric, activity, activities, showActivityFilter = false, groupBy = groupBy,
                showGroupToggle = true, values = values, labels = labels, mode = modeFor(metric),
                accentRes = accentRes, lowerIsBetter = lowerIsBetter,
                caption = captionFor(groupBy, activity, values.size, isDays = true),
                formatter = { formatDaily(metric, it) },
                bestLabel = if (lowerIsBetter) "Lowest" else "Best",
                recommendations = recommendations, recap = recap
            )
        }

        // ---- Exercise-based metrics ----
        val filtered = exercise.sortedBy { it.epochMs }
            .filter { activity == TrendsUiState.ALL || it.type == activity }
            .filter { metricApplies(metric, it) }

        if (filtered.isEmpty()) {
            return TrendsUiState(
                unit = unit, metric = metric, activity = activity, activities = activities,
                mode = modeFor(metric), accentColorRes = accentRes, groupBy = groupBy,
                emptyMessage = emptyMessageFor(metric, activity),
                recommendations = recommendations, recap = recap
            )
        }

        val rawPoints = filtered.mapNotNull { log ->
            valueFor(metric, log, unit)?.let { log.epochMs to it }
        }
        val (values, labels) = seriesFor(rawPoints, groupBy, sum = sumMetric)
        val lowerIsBetter = metric == TrendMetric.PACE || metric == TrendMetric.AVG_HR

        return finishSeries(
            unit, metric, activity, activities, showActivityFilter = true, groupBy = groupBy,
            showGroupToggle = true, values = values, labels = labels, mode = modeFor(metric),
            accentRes = accentRes, lowerIsBetter = lowerIsBetter,
            caption = captionFor(groupBy, activity, values.size, isDays = false),
            formatter = { formatValue(metric, it, unit) }, bestLabel = "Best",
            recommendations = recommendations, recap = recap
        )
    }

    /** Applies the session/week grouping and clamps to the display window. */
    private fun seriesFor(
        points: List<Pair<Long, Float>>,
        groupBy: GroupBy,
        sum: Boolean
    ): Pair<List<Float>, List<String>> {
        return if (groupBy == GroupBy.WEEK) {
            val weekly = TrainingAnalytics.weekly(points, sum = sum).takeLast(12)
            weekly.map { it.value } to weekly.map { it.label }
        } else {
            val last = points.takeLast(WINDOW)
            last.map { it.second } to last.map { dateFmt.format(it.first) }
        }
    }

    private fun captionFor(groupBy: GroupBy, activity: String, count: Int, isDays: Boolean): String {
        val scope = if (activity == TrendsUiState.ALL) "" else " · $activity"
        return when {
            groupBy == GroupBy.WEEK -> "Last $count weeks$scope"
            isDays -> "Last $count days"
            else -> "Last $count activities$scope"
        }
    }

    private fun buildRecommendations(
        profile: UserProfile,
        exercise: List<ExerciseLog>,
        unit: UnitSystem
    ): List<Recommendation> {
        val runs = exercise.filter { it.type.equals("Run", ignoreCase = true) }
            .filter { (it.distanceMeters ?: 0.0) > 0.0 }
            .sortedBy { it.epochMs }
        val weekly = TrainingAnalytics.weekly(
            runs.map { it.epochMs to Units.distanceValue(it.distanceMeters!!, unit).toFloat() },
            sum = true
        ).map { it.value }
        val daysSince = runs.lastOrNull()?.let {
            ((System.currentTimeMillis() - it.epochMs) / TimeUnit.DAYS.toMillis(1)).toInt()
        }
        val paces = runs.mapNotNull {
            Units.paceMinPerUnit(it.distanceMeters!!, it.durationMin, unit)?.toFloat()
        }
        return RecommendationEngine.generate(
            unit = unit,
            weeklyRunMiles = weekly,
            daysSinceLastRun = daysSince,
            recentPaces = paces,
            targets = GuidanceEngine.compute(profile),
            limit = 5
        )
    }

    private fun finishSeries(
        unit: UnitSystem,
        metric: TrendMetric,
        activity: String,
        activities: List<String>,
        showActivityFilter: Boolean,
        groupBy: GroupBy,
        showGroupToggle: Boolean,
        values: List<Float>,
        labels: List<String>,
        mode: ChartMode,
        accentRes: Int,
        lowerIsBetter: Boolean,
        caption: String,
        formatter: (Float) -> String,
        bestLabel: String,
        recommendations: List<Recommendation>,
        recap: DayRecap
    ): TrendsUiState {
        if (values.isEmpty()) {
            return TrendsUiState(
                unit = unit, metric = metric, activity = activity, activities = activities,
                showActivityFilter = showActivityFilter, showGroupToggle = showGroupToggle,
                groupBy = groupBy, mode = mode, accentColorRes = accentRes,
                emptyMessage = emptyMessageFor(metric, activity),
                recommendations = recommendations, recap = recap
            )
        }
        val best = if (lowerIsBetter) values.min() else values.max()
        val avg = values.average().toFloat()

        // Trend: compare the average of the recent half vs the earlier half.
        val hasTrend = values.size >= 4
        var trendPct = 0
        var improved = true
        if (hasTrend) {
            val mid = values.size / 2
            val firstAvg = values.subList(0, mid).average()
            val secondAvg = values.subList(mid, values.size).average()
            if (firstAvg != 0.0) {
                trendPct = (((secondAvg - firstAvg) / abs(firstAvg)) * 100).roundToInt()
                improved = if (lowerIsBetter) secondAvg < firstAvg else secondAvg > firstAvg
            }
        }

        return TrendsUiState(
            unit = unit, metric = metric, activity = activity, activities = activities,
            showActivityFilter = showActivityFilter, showGroupToggle = showGroupToggle,
            groupBy = groupBy, values = values, labels = labels, mode = mode,
            accentColorRes = accentRes, caption = caption,
            summaryBest = formatter(best), summaryBestLabel = bestLabel,
            summaryAvg = formatter(avg),
            trendPct = abs(trendPct), trendImproved = improved, hasTrend = hasTrend,
            emptyMessage = null, recommendations = recommendations, recap = recap
        )
    }

    private fun modeFor(metric: TrendMetric): ChartMode = when (metric) {
        TrendMetric.PACE, TrendMetric.AVG_HR, TrendMetric.STRESS,
        TrendMetric.RESTING_HR, TrendMetric.BODY_BATTERY -> ChartMode.LINE
        else -> ChartMode.BAR
    }

    private fun metricApplies(metric: TrendMetric, log: ExerciseLog): Boolean = when (metric) {
        TrendMetric.DISTANCE, TrendMetric.PACE -> (log.distanceMeters ?: 0.0) > 0.0 && log.durationMin > 0
        TrendMetric.AVG_HR -> log.avgHeartRate != null && log.avgHeartRate > 0
        TrendMetric.DURATION -> log.durationMin > 0
        TrendMetric.CALORIES -> log.calories > 0
        else -> false
    }

    private fun valueFor(metric: TrendMetric, log: ExerciseLog, unit: UnitSystem): Float? = when (metric) {
        TrendMetric.DISTANCE -> Units.distanceValue(log.distanceMeters ?: 0.0, unit).toFloat()
        TrendMetric.PACE -> Units.paceMinPerUnit(log.distanceMeters ?: 0.0, log.durationMin, unit)?.toFloat()
        TrendMetric.DURATION -> log.durationMin.toFloat()
        TrendMetric.CALORIES -> log.calories.toFloat()
        TrendMetric.AVG_HR -> log.avgHeartRate?.toFloat()
        else -> null
    }

    /** Pulls the right field (in display units) out of a daily snapshot for the given metric. */
    private fun dailyValue(metric: TrendMetric, m: DailyHealthMetric): Float? = when (metric) {
        TrendMetric.STRESS -> m.stressAvg?.toFloat()
        TrendMetric.BODY_BATTERY -> m.bodyBattery?.toFloat()
        TrendMetric.RESTING_HR -> m.restingHeartRate?.toFloat()
        TrendMetric.SLEEP -> m.sleepMinutes?.let { it / 60f }
        else -> null
    }

    private fun formatDaily(metric: TrendMetric, v: Float): String = when (metric) {
        TrendMetric.SLEEP -> String.format(Locale.US, "%.1f h", v)
        TrendMetric.RESTING_HR -> "${v.roundToInt()} bpm"
        else -> "${v.roundToInt()}"
    }

    private fun formatValue(metric: TrendMetric, v: Float, unit: UnitSystem): String = when (metric) {
        TrendMetric.DISTANCE -> String.format(Locale.US, "%.2f %s", v, Units.distanceUnit(unit))
        TrendMetric.PACE -> Units.paceLabel(v.toDouble(), unit)
        TrendMetric.DURATION -> "${v.roundToInt()} min"
        TrendMetric.CALORIES -> "${v.roundToInt()} kcal"
        TrendMetric.AVG_HR -> "${v.roundToInt()} bpm"
        else -> formatDaily(metric, v)
    }


    private fun emptyMessageFor(metric: TrendMetric, activity: String): String {
        val scope = if (activity == TrendsUiState.ALL) "activities" else "$activity sessions"
        return when (metric) {
            TrendMetric.DISTANCE, TrendMetric.PACE ->
                "Log $scope with a distance to see this chart."
            TrendMetric.AVG_HR ->
                "No heart-rate data yet — connect a watch or add it manually."
            else -> "Log a few $scope to see your trend."
        }
    }
}
