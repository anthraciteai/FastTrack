package ai.anthracite.fasttrack.ui.dashboard

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.health.GoalProjection
import ai.anthracite.fasttrack.data.health.GuidanceEngine
import ai.anthracite.fasttrack.data.health.Recommendation
import ai.anthracite.fasttrack.data.health.RecommendationEngine
import ai.anthracite.fasttrack.data.health.DailyRecapEngine
import ai.anthracite.fasttrack.data.health.DayRecap
import ai.anthracite.fasttrack.data.health.FastingGamification
import ai.anthracite.fasttrack.data.health.TrainingAnalytics
import ai.anthracite.fasttrack.data.health.CheckInInsights
import ai.anthracite.fasttrack.data.health.RecoveryEngine
import ai.anthracite.fasttrack.data.health.WeeklyReviewEngine
import ai.anthracite.fasttrack.data.health.WeightTrend
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.WeeklyReview
import ai.anthracite.fasttrack.data.local.DailyHealthMetric
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WeightLog
import ai.anthracite.fasttrack.R
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units
import android.app.Application
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Calendar
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ai.anthracite.fasttrack.data.local.DailyUpdate
import ai.anthracite.fasttrack.util.DayKeys

data class DashboardUiState(
    val name: String = "Athlete",
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val activeFast: FastingSession? = null,
    val fastingProtocolName: String = "16:8",
    val fastingGoalHours: Double = 16.0,
    /** End time of the most recently finished fast — drives the eating-window countdown. */
    val lastFastEndMs: Long? = null,
    val waterMl: Double = 0.0,
    val waterGoalMl: Double = 2500.0,
    val calories: Int = 0,
    val calorieGoal: Int = 1800,
    val exerciseMin: Int = 0,
    val exerciseCal: Int = 0,
    val bmrKcal: Int = 1600,
    val restingBurnedToday: Int = 0,
    val streak: Int = 0,
    val insight: String = "",
    val projectionTitle: String = "",
    val projectionBody: String = "",
    val projectionUnlocked: Boolean = false,
    // Movement distance over time (all distance-bearing activity: runs, walks, sports…)
    val weeklyDistanceValues: List<Float> = emptyList(),
    val weeklyDistanceLabels: List<String> = emptyList(),
    val distanceUnit: String = "mi",
    val weekDistanceText: String = "No movement yet",
    val distanceTrendText: String = "",
    val hasDistanceData: Boolean = false,
    val movementCategories: List<MovementCategory> = emptyList(),
    // Today-scoped movement (default card view); the week view uses the fields above.
    val todayDistanceText: String = "No movement today",
    val todayCategories: List<MovementCategory> = emptyList(),
    val todayMovementSub: String = "",
    val weekMovementSub: String = "",
    val hasTodayMovement: Boolean = false,
    val recommendations: List<Recommendation> = emptyList(),
    val recap: DayRecap = DayRecap(),
    // Body-weight trend (only populated once the user logs a weigh-in)
    val currentWeightKg: Double = 0.0,
    val hasWeightData: Boolean = false,
    val weightLabel: String = "",
    val weightTrendText: String = "",
    val weightSparkValues: List<Float> = emptyList(),
    val readiness: RecoveryEngine.Readiness? = null,
    val checkInToday: DailyCheckIn? = null,
    /** Today's fast that earned a check-in (ended at ≥ half its goal), or null. */
    val checkInFast: FastingSession? = null,
    val checkInInsight: String? = null,
    /** Week key of a review worth surfacing right now (Sun–Tue), or null. */
    val weeklyReviewKey: String? = null,
    val weeklyHeadline: String = ""
)

/** A read-only summary of how a past day went, shown when the user arrows back on the dashboard. */
data class DaySnapshot(
    val fasting: String,
    val hydration: String,
    val energy: String,
    val movement: String,
    val recapHeadline: String,
    val recapSummary: String,
    // Goal "scorecard": one ring per goal plus an overall score + line.
    val score: Int = 0,
    val scoreHeadline: String = "",
    val scoreLine: String = "",
    val rings: List<RingData> = emptyList()
)

/** One completion ring on the day scorecard (fills toward [progress], stamps a ✓ when [closed]). */
data class RingData(
    val label: String,
    val valueText: String,
    val progress: Int,
    val closed: Boolean,
    @param:DrawableRes val iconRes: Int,
    @param:ColorRes val colorRes: Int
)

/** One day in the 7-day consistency strip; [score] is how many of the 4 goals were closed. */
data class DayCell(
    val dowInitial: String,
    val dayNum: String,
    val score: Int,
    val selected: Boolean
)

/** A total for one movement category (Running, Walking, Sports…), with its accent color. */
data class MovementCategory(
    val label: String,
    val distance: Float,
    val unit: String,
    @param:ColorRes val colorRes: Int,
    /** Pre-formatted value shown on the row, e.g. "3.8 mi" or "30 min" for activities w/o distance. */
    val valueText: String = ""
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = getApplication<FastTrackApp>().repository
    private val prefs = getApplication<FastTrackApp>().prefs

    private companion object {
        /** Daily active-minutes target used for the Movement ring. */
        const val MOVE_TARGET_MIN = 30
    }

    val state: StateFlow<DashboardUiState> = combine(
        listOf(
            repo.observeProfile(),
            repo.observeActiveFast(),
            repo.observeWaterTotalToday(),
            repo.observeCaloriesToday(),
            repo.observeExerciseMinutesToday(),
            repo.observeExerciseCaloriesToday(),
            repo.observeExerciseRecent(),
            repo.observeDailyMetrics(),
            repo.observeWeightHistory(),
            repo.observeFinishedFasts(),
            repo.observeCaloriesByDaySince(DayKeys.of(System.currentTimeMillis() - 8L * 86_400_000L)),
            repo.observeCheckIns(),
            repo.observeWeeklyReviews()
        )
    ) { values ->
        val profile = (values[0] as? UserProfile) ?: UserProfile()
        val active = values[1] as? FastingSession
        val waterMl = values[2] as Double
        val calories = values[3] as Int
        val exMin = values[4] as Int
        val exCal = values[5] as Int
        @Suppress("UNCHECKED_CAST")
        val exerciseLogs = values[6] as List<ExerciseLog>
        @Suppress("UNCHECKED_CAST")
        val dailyMetrics = values[7] as List<DailyHealthMetric>
        @Suppress("UNCHECKED_CAST")
        val weightLogs = values[8] as List<WeightLog>
        @Suppress("UNCHECKED_CAST")
        val finishedFasts = values[9] as List<FastingSession>
        @Suppress("UNCHECKED_CAST")
        val caloriesByDay = values[10] as Map<String, Int>
        @Suppress("UNCHECKED_CAST")
        val checkIns = values[11] as List<DailyCheckIn>
        @Suppress("UNCHECKED_CAST")
        val weeklyReviews = values[12] as List<WeeklyReview>
        // Real fasting streak: consecutive days with a completed fast (credited to the end day),
        // derived from the times — not the stored flag or a count. Drops to 0 when you stop fasting.
        val streak = FastingGamification.compute(finishedFasts).currentStreak
        val lastFastEndMs = finishedFasts.maxByOrNull { it.endEpochMs ?: it.startEpochMs }?.endEpochMs
        val todayKey = DayKeys.today()
        val now = System.currentTimeMillis()

        val targets = GuidanceEngine.compute(profile)
        val waterGoal = GuidanceEngine.waterGoalMl(profile.weightKg, prefs.waterGoalOverrideOz)
        val protocol = FastingProtocol.fromId(profile.fastingProtocol)
        val unit = runCatching { UnitSystem.valueOf(profile.unitSystem) }
            .getOrDefault(UnitSystem.IMPERIAL)

        val movement = buildMovement(exerciseLogs, unit)
        val trend = WeightTrend.compute(
            weightLogs.map { WeightTrend.Point(it.epochMs, it.weightKg) }, profile.goalWeightKg, now
        )
        val weight = buildWeight(weightLogs, profile.weightKg, unit, trend)
        val projection = GoalProjection.of(
            profile = profile,
            unit = unit,
            firstOpenEpochMs = prefs.firstOpenEpochMs,
            baselineWeightKg = prefs.baselineWeightKg,
            trendKgPerWeek = trend?.kgPerWeek
        )
        val readiness = RecoveryEngine.assess(
            dailyMetrics, todayKey, DayKeys.of(now - 86_400_000L)
        )
        val fastHoursByEndDay = ai.anthracite.fasttrack.data.health.FastHistory.finishedHoursByDay(finishedFasts)
        val checkInInsights = CheckInInsights.compute(checkIns, fastHoursByEndDay, todayKey)
        val reviewWeek = WeeklyReviewEngine.reviewWeekFor(java.time.LocalDate.now()).toString()
        val dow = java.time.LocalDate.now().dayOfWeek
        val reviewWindow = dow == java.time.DayOfWeek.SUNDAY || dow == java.time.DayOfWeek.MONDAY ||
            dow == java.time.DayOfWeek.TUESDAY
        val baseRecs = RecommendationEngine.generate(
            unit = unit,
            weeklyRunMiles = movement.runWeekValues,
            daysSinceLastRun = movement.daysSinceLastRun,
            recentPaces = movement.paces,
            targets = targets,
            limit = 3
        )
        val recs = (recoveryRecommendations(readiness, active, exMin, now) + baseRecs).take(3)

        DashboardUiState(
            name = profile.displayName,
            unitSystem = unit,
            activeFast = active,
            fastingProtocolName = protocol.displayName,
            fastingGoalHours = protocol.fastHours,
            lastFastEndMs = lastFastEndMs,
            waterMl = waterMl,
            waterGoalMl = waterGoal,
            calories = calories,
            calorieGoal = targets.calories,
            exerciseMin = exMin,
            exerciseCal = exCal,
            bmrKcal = targets.bmr,
            restingBurnedToday = GoalProjection.restingBurnedSoFar(targets.bmr),
            streak = streak,
            insight = buildInsight(waterMl, waterGoal, calories, targets.calories),
            projectionTitle = projection.title,
            projectionBody = projection.body,
            projectionUnlocked = projection.unlocked,
            weeklyDistanceValues = movement.weekValues,
            weeklyDistanceLabels = movement.weekLabels,
            distanceUnit = Units.distanceUnit(unit),
            weekDistanceText = movement.weekText,
            distanceTrendText = movement.trendText,
            hasDistanceData = movement.weekValues.isNotEmpty(),
            movementCategories = movement.categories,
            todayDistanceText = movement.todayText,
            todayCategories = movement.todayCategories,
            todayMovementSub = movement.todaySub,
            weekMovementSub = movement.weekSub,
            hasTodayMovement = movement.hasTodayMovement,
            recommendations = recs,
            recap = DailyRecapEngine.yesterday(dailyMetrics, exerciseLogs, caloriesByDay, targets.calories),
            currentWeightKg = profile.weightKg,
            hasWeightData = weight.hasData,
            weightLabel = weight.label,
            weightTrendText = weight.trendText,
            weightSparkValues = weight.sparkValues,
            readiness = readiness,
            checkInToday = checkIns.firstOrNull { it.dayKey == todayKey },
            checkInFast = finishedFasts
                .filter { ai.anthracite.fasttrack.data.health.FastHistory.qualifiesForCheckIn(it) &&
                    ai.anthracite.fasttrack.data.health.FastHistory.dayOf(it) == todayKey }
                .maxByOrNull { it.endEpochMs ?: 0L },
            checkInInsight = checkInInsights.headline,
            weeklyReviewKey = if (reviewWindow) reviewWeek else null,
            weeklyHeadline = weeklyReviews.firstOrNull { it.weekKey == reviewWeek }?.headline
                ?: "See how your week went and pick one focus for the next."
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardUiState())

    private data class Weight(
        val hasData: Boolean,
        val label: String,
        val trendText: String,
        val sparkValues: List<Float>
    )

    /**
     * Latest weigh-in + a short delta ("down 2.0 lb this week") and a sparkline of recent weigh-ins.
     * [logs] arrives newest-first; values are converted to the user's display unit.
     */
    private fun buildWeight(
        logs: List<WeightLog>,
        profileKg: Double,
        unit: UnitSystem,
        trend: WeightTrend.Result?
    ): Weight {
        if (logs.isEmpty()) return Weight(false, "", "", emptyList())
        trend?.kgPerWeek?.let { rate ->
            val perWeek = if (unit == UnitSystem.IMPERIAL) Units.kgToLb(rate) else rate
            val u = if (unit == UnitSystem.IMPERIAL) "lb" else "kg"
            val sign = if (perWeek < 0) "−" else "+"
            val eta = trend.etaEpochMs?.let {
                " · goal ~" + java.text.SimpleDateFormat("MMM d", Locale.US).format(java.util.Date(it))
            }.orEmpty()
            val spark = trend.smoothed.takeLast(12).map {
                (if (unit == UnitSystem.IMPERIAL) Units.kgToLb(it.kg) else it.kg).toFloat()
            }
            return Weight(
                true,
                Units.weightLabel(logs.first().weightKg, unit),
                String.format(Locale.US, "Trend %s%.1f %s/wk", sign, abs(perWeek), u) + eta,
                spark
            )
        }

        val latest = logs.first()
        val label = Units.weightLabel(latest.weightKg, unit)
        fun display(kg: Double) = if (unit == UnitSystem.IMPERIAL) Units.kgToLb(kg) else kg
        val unitLabel = if (unit == UnitSystem.IMPERIAL) "lb" else "kg"

        // Oldest-first, last 12 weigh-ins, for the sparkline.
        val sparkValues = logs.asReversed().takeLast(12).map { display(it.weightKg).toFloat() }

        val trendText = if (logs.size < 2) {
            "Log again to see your trend"
        } else {
            val sevenAgoMs = System.currentTimeMillis() - 7L * 86_400_000L
            val inWindow = logs.filter { it.epochMs >= sevenAgoMs }
            val baseline: WeightLog
            val qualifier: String
            if (inWindow.size >= 2) {
                baseline = inWindow.minByOrNull { it.epochMs }!!
                qualifier = "this week"
            } else {
                baseline = logs[1]
                qualifier = "since last weigh-in"
            }
            val deltaDisplay = display(latest.weightKg) - display(baseline.weightKg)
            val magnitude = String.format(Locale.US, "%.1f %s", abs(deltaDisplay), unitLabel)
            when {
                deltaDisplay <= -0.05 -> "Down $magnitude $qualifier"
                deltaDisplay >= 0.05 -> "Up $magnitude $qualifier"
                else -> "No change $qualifier"
            }
        }
        return Weight(true, label, trendText, sparkValues)
    }

    /** Records a weigh-in from the dashboard's weight card. */
    fun logWeight(weightKg: Double) = viewModelScope.launch {
        repo.addWeight(weightKg)
    }

    /** Recovery-driven nudges that outrank the generic training recommendations. */
    private fun recoveryRecommendations(
        readiness: RecoveryEngine.Readiness?,
        active: FastingSession?,
        exerciseMinToday: Int,
        now: Long
    ): List<Recommendation> {
        val out = mutableListOf<Recommendation>()
        if (readiness?.level == RecoveryEngine.Level.LOW) {
            out += Recommendation(
                id = "recovery_low",
                title = "Low recovery — keep today's fast gentle",
                detail = readiness.fastingAdvice,
                iconRes = R.drawable.ic_heart,
                accentRes = R.color.macro_protein
            )
        }
        if (active != null && exerciseMinToday >= 45) {
            val hours = (now - active.startEpochMs) / 3_600_000.0
            if (hours >= 12) {
                out += Recommendation(
                    id = "fasted_training",
                    title = "Big session on a long fast",
                    detail = String.format(
                        Locale.US,
                        "You trained %d min about %.0fh into your fast. Plan to break it within a couple of hours with 30–40 g of protein and some salt.",
                        exerciseMinToday, hours
                    ),
                    iconRes = R.drawable.ic_exercise,
                    accentRes = R.color.accent_exercise
                )
            }
        }
        return out
    }

    // ---- Previous-day review (read-only) -------------------------------------------------

    private val selectedDay = MutableStateFlow(DayKeys.today())

    val dayLabel: StateFlow<String> =
        selectedDay.map { labelFor(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), labelFor(DayKeys.today()))

    val isToday: StateFlow<Boolean> =
        selectedDay.map { it == DayKeys.today() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Earliest navigable day — the day the user joined the app. */
    private val joinDayKey = prefs.joinedDayKey()

    /** False once the user is back at the join date; there's nothing earlier to review. */
    val canGoBack: StateFlow<Boolean> =
        selectedDay.map { DayKeys.startMs(it) > DayKeys.startMs(joinDayKey) }
            .stateIn(
                viewModelScope, SharingStarted.WhileSubscribed(5000),
                DayKeys.startMs(DayKeys.today()) > DayKeys.startMs(joinDayKey)
            )

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<DaySnapshot?> = selectedDay.flatMapLatest { day ->
        if (day == DayKeys.today()) {
            flowOf(null)
        } else {
            combine(
                combine(
                    repo.observeWaterTotal(day),
                    repo.observeCalories(day),
                    repo.observeExerciseMinutes(day),
                    repo.observeExerciseCalories(day)
                ) { water, cals, exMin, exCal -> listOf(water, cals, exMin, exCal) },
                combine(
                    repo.observeExerciseDay(day),
                    repo.observeFastHistory(),
                    repo.observeProfile(),
                    repo.observeDailyUpdates(),
                    repo.observeDailyMetrics()
                ) { logs, fasts, profile, updates, metrics -> listOf(logs, fasts, profile, updates, metrics) }
            ) { nums, objs ->
                @Suppress("UNCHECKED_CAST")
                buildSnapshot(
                    day = day,
                    waterMl = nums[0] as Double,
                    calories = nums[1] as Int,
                    exMin = nums[2] as Int,
                    exCal = nums[3] as Int,
                    logs = objs[0] as List<ExerciseLog>,
                    fasts = objs[1] as List<FastingSession>,
                    profile = objs[2] as? UserProfile,
                    updates = objs[3] as List<DailyUpdate>,
                    dailyMetrics = objs[4] as List<DailyHealthMetric>
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun goPrevDay() {
        val prev = shiftDay(selectedDay.value, -1)
        if (DayKeys.startMs(prev) >= DayKeys.startMs(joinDayKey)) selectedDay.value = prev
    }

    fun goNextDay() {
        val next = shiftDay(selectedDay.value, 1)
        if (DayKeys.startMs(next) <= System.currentTimeMillis()) selectedDay.value = next
    }

    fun resetToToday() { selectedDay.value = DayKeys.today() }

    /** Representative timestamp for the day currently arrowed-to (now if today, else local noon). */
    fun selectedDayMs(): Long {
        val day = selectedDay.value
        return if (day == DayKeys.today()) System.currentTimeMillis()
        else DayKeys.startMs(day) + 12L * 60L * 60L * 1000L
    }

    private fun shiftDay(day: String, delta: Int): String {
        val cal = Calendar.getInstance().apply {
            timeInMillis = DayKeys.startMs(day)
            add(Calendar.DAY_OF_YEAR, delta)
        }
        return DayKeys.of(cal.timeInMillis)
    }

    private fun labelFor(day: String): String = when (day) {
        DayKeys.today() -> "Today"
        shiftDay(DayKeys.today(), -1) -> "Yesterday"
        else -> {
            val cal = Calendar.getInstance().apply { timeInMillis = DayKeys.startMs(day) }
            String.format(
                Locale.US, "%ta, %tb %te",
                cal, cal, cal
            )
        }
    }

    private fun buildSnapshot(
        day: String,
        waterMl: Double,
        calories: Int,
        exMin: Int,
        exCal: Int,
        logs: List<ExerciseLog>,
        fasts: List<FastingSession>,
        profile: UserProfile?,
        updates: List<DailyUpdate>,
        dailyMetrics: List<DailyHealthMetric> = emptyList()
    ): DaySnapshot {
        val p = profile ?: UserProfile()
        val targets = GuidanceEngine.compute(p)
        val unit = runCatching { UnitSystem.valueOf(p.unitSystem) }.getOrDefault(UnitSystem.IMPERIAL)
        val waterGoal = GuidanceEngine.waterGoalMl(p.weightKg, prefs.waterGoalOverrideOz)

        val hydration = Units.volumeRangeLabel(waterMl, waterGoal, unit)

        val out = targets.bmr + exCal
        val net = calories - out
        val sign = if (net < 0) "−" else if (net > 0) "+" else ""
        val energy = "Net $sign${abs(net)} kcal · $calories in · $out out"

        val distMeters = logs.sumOf { it.distanceMeters ?: 0.0 }
        val distStr = if (distMeters > 1.0) {
            " · " + String.format(Locale.US, "%.1f %s", Units.distanceValue(distMeters, unit), Units.distanceUnit(unit))
        } else ""
        val movement = if (exMin > 0 || exCal > 0 || distMeters > 0.0) {
            "$exMin min · $exCal kcal$distStr"
        } else "No movement logged"

        val session = fasts.filter { it.endEpochMs != null && ai.anthracite.fasttrack.data.health.FastHistory.dayOf(it) == day }
            .maxByOrNull { ai.anthracite.fasttrack.data.health.FastHistory.hours(it) }
        val fasting = when {
            session == null -> "No fast logged"
            else -> {
                val mins = ((session.endEpochMs!! - session.startEpochMs) / 60_000L).toInt().coerceAtLeast(0)
                val h = mins / 60
                val m = mins % 60
                val met = ai.anthracite.fasttrack.data.health.FastHistory.isCompleted(session)
                val suffix = if (met) " · goal met ✓" else " · under ${session.goalHours.toInt()}h goal"
                "Fasted ${h}h ${m}m$suffix"
            }
        }

        // ---- Scorecard rings ----
        val waterClosed = waterMl >= waterGoal - 1
        val waterPct = if (waterGoal > 0) (waterMl / waterGoal * 100).toInt().coerceIn(0, 100) else 0

        val fastedHours = session?.endEpochMs?.let { (it - session.startEpochMs) / 3_600_000.0 }
        val fastClosed = session?.completed == true ||
            (fastedHours != null && session != null && fastedHours >= session.goalHours)
        val fastPct = when {
            session == null -> 0
            fastedHours == null -> 0
            else -> (fastedHours / session.goalHours * 100).toInt().coerceIn(0, 100)
        }

        val moveClosed = exMin >= MOVE_TARGET_MIN
        val movePct = (exMin.toDouble() / MOVE_TARGET_MIN * 100).toInt().coerceIn(0, 100)

        val nutritionClosed = calories > 0 && net <= 0
        val nutritionPct =
            if (targets.calories > 0) (calories.toDouble() / targets.calories * 100).toInt().coerceIn(0, 100) else 0

        val rings = listOf(
            RingData(
                "Hydration", Units.volumeLabel(waterMl, unit), waterPct, waterClosed,
                R.drawable.ic_water, R.color.accent_water
            ),
            RingData(
                "Fasting",
                if (fastedHours != null) String.format(Locale.US, "%.1fh", fastedHours) else "—",
                fastPct, fastClosed, R.drawable.ic_fasting, R.color.accent_fasting
            ),
            RingData(
                "Movement", "$exMin min", movePct, moveClosed,
                R.drawable.ic_exercise, R.color.accent_exercise
            ),
            RingData(
                "Nutrition",
                if (calories > 0) "Net $sign${abs(net)}" else "No meals",
                nutritionPct, nutritionClosed, R.drawable.ic_nutrition, R.color.accent_nutrition
            )
        )
        val score = rings.count { it.closed }

        val update = updates.firstOrNull { it.dayKey == day }
        // Recompute an honest, nutrition-aware recap for this day. We prefer it over the archived
        // note so a heavy-eating day no longer reads as "good & steady"; fall back to the saved note
        // (or a placeholder) only when there's nothing to recompute from.
        val recomputed = DailyRecapEngine.recapForDay(day, dailyMetrics, logs, calories, targets.calories)
        val recapHeadline = when {
            recomputed.hasData -> recomputed.headline
            update != null -> update.headline
            else -> ""
        }
        val recapSummary = when {
            recomputed.hasData -> recomputed.summary
            update != null -> update.summary
            else -> "No recap saved for this day."
        }
        return DaySnapshot(
            fasting = fasting,
            hydration = hydration,
            energy = energy,
            movement = movement,
            recapHeadline = recapHeadline,
            recapSummary = recapSummary,
            score = score,
            scoreHeadline = "$score of 4 goals closed",
            scoreLine = scoreLine(score),
            rings = rings
        )
    }

    private fun scoreLine(score: Int): String = when (score) {
        4 -> "Perfect day — every ring closed. 🔥"
        3 -> "Strong, steady day — almost a clean sweep."
        2 -> "Solid — two rings closed. Momentum is building."
        1 -> "One ring closed. Every bit counts — keep stacking."
        else -> "A rest day. Tomorrow's a fresh set of rings."
    }

    // ---- 7-day consistency strip ----

    @OptIn(ExperimentalCoroutinesApi::class)
    val weekStrip: StateFlow<List<DayCell>> = selectedDay.flatMapLatest { day ->
        if (day == DayKeys.today()) {
            flowOf(emptyList())
        } else {
            val fromKey = shiftDay(day, -6)
            combine(
                repo.observeWaterTotalsSince(fromKey),
                repo.observeCaloriesSince(fromKey),
                repo.observeExerciseSince(fromKey),
                repo.observeFastHistory(),
                repo.observeProfile()
            ) { water, cals, ex, fasts, profile ->
                buildStrip(day, water, cals, ex, fasts, profile)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun buildStrip(
        endDay: String,
        water: List<ai.anthracite.fasttrack.data.local.DayAmount>,
        cals: List<ai.anthracite.fasttrack.data.local.DayInt>,
        ex: List<ai.anthracite.fasttrack.data.local.DayExercise>,
        fasts: List<FastingSession>,
        profile: UserProfile?
    ): List<DayCell> {
        val p = profile ?: UserProfile()
        val targets = GuidanceEngine.compute(p)
        val waterGoal = GuidanceEngine.waterGoalMl(p.weightKg, prefs.waterGoalOverrideOz)
        val waterBy = water.associate { it.dayKey to it.total }
        val calBy = cals.associate { it.dayKey to it.total }
        val exBy = ex.associate { it.dayKey to it }
        val fastDays = ai.anthracite.fasttrack.data.health.FastHistory.completedHoursByDay(fasts).keys

        return (0..6).map { i ->
            val d = shiftDay(endDay, -(6 - i))
            val w = waterBy[d] ?: 0.0
            val c = calBy[d] ?: 0
            val exMin = exBy[d]?.minutes ?: 0
            val exCal = exBy[d]?.calories ?: 0
            var closed = 0
            if (w >= waterGoal - 1) closed++
            if (d in fastDays) closed++
            if (exMin >= MOVE_TARGET_MIN) closed++
            if (c > 0 && c - (targets.bmr + exCal) <= 0) closed++
            val cal = Calendar.getInstance().apply { timeInMillis = DayKeys.startMs(d) }
            DayCell(
                dowInitial = String.format(Locale.US, "%ta", cal).take(1),
                dayNum = cal.get(Calendar.DAY_OF_MONTH).toString(),
                score = closed,
                selected = d == endDay
            )
        }
    }

    private data class Movement(
        val weekValues: List<Float>,
        val weekLabels: List<String>,
        val weekText: String,
        val trendText: String,
        val categories: List<MovementCategory>,
        // Today-scoped figures for the default card view.
        val todayText: String,
        val todayCategories: List<MovementCategory>,
        val todaySub: String,
        val weekSub: String,
        val hasTodayMovement: Boolean,
        // Running-only figures, kept for the recommendation engine (mileage 10% rule, pace trend).
        val runWeekValues: List<Float>,
        val daysSinceLastRun: Int?,
        val paces: List<Float>
    )

    /**
     * Distance from ALL movement — runs, walks, hikes, sports, rides — grouped into weekly totals
     * for the chart and broken down by category for this week. Running figures are also extracted
     * separately so the recommendation engine can keep its run-specific logic.
     */
    private fun buildMovement(logs: List<ExerciseLog>, unit: UnitSystem): Movement {
        val moved = logs.filter { (it.distanceMeters ?: 0.0) > 0.0 }.sortedBy { it.epochMs }
        val u = Units.distanceUnit(unit)
        val currentWeekStart = TrainingAnalytics.weekStartMs(System.currentTimeMillis())
        val todayKey = DayKeys.today()

        val runs = moved.filter { it.type.equals("Run", ignoreCase = true) }
        val runWeekly = TrainingAnalytics.weekly(
            runs.map { it.epochMs to Units.distanceValue(it.distanceMeters!!, unit).toFloat() },
            sum = true
        ).map { it.value }
        val daysSinceLastRun = runs.lastOrNull()?.let {
            ((System.currentTimeMillis() - it.epochMs) / TimeUnit.DAYS.toMillis(1)).toInt()
        }
        val paces = runs.mapNotNull {
            Units.paceMinPerUnit(it.distanceMeters!!, it.durationMin, unit)?.toFloat()
        }

        // ---- Today (all activity, not just distance-bearing) -------------------------------
        val todayLogs = logs.filter { it.dayKey == todayKey }
        val weekLogs = logs.filter { TrainingAnalytics.weekStartMs(it.epochMs) == currentWeekStart }
        val todayDist = todayLogs.sumOf { Units.distanceValue(it.distanceMeters ?: 0.0, unit) }.toFloat()
        val todayActiveMin = todayLogs.sumOf { it.durationMin }
        val todayCal = todayLogs.sumOf { it.calories }
        // Many activities (a widget-started walk, strength work) have no GPS distance. Leading the
        // headline with "No distance today" while the breakdown shows "Walking 29 min" reads as a
        // contradiction, so fall back to active minutes as the headline when there's no distance.
        val todayText = when {
            todayDist > 0.01f -> String.format(Locale.US, "%.1f %s today", todayDist, u)
            todayActiveMin > 0 -> String.format(Locale.US, "%d min active today", todayActiveMin)
            else -> "No movement today"
        }
        // Avoid printing the minutes twice: when minutes are already the headline, the sub shows
        // just calories (if any); when distance is the headline, the sub carries min + kcal.
        val todaySub = when {
            todayDist > 0.01f -> movementSub(todayActiveMin, todayCal)
            todayActiveMin > 0 -> if (todayCal > 0) String.format(Locale.US, "%,d kcal", todayCal) else ""
            else -> ""
        }
        val weekSub = movementSub(weekLogs.sumOf { it.durationMin }, weekLogs.sumOf { it.calories })
        val todayCategories = categoriesFor(todayLogs, unit, u)
        val hasTodayMovement = todayLogs.isNotEmpty()

        if (moved.isEmpty()) {
            return Movement(
                weekValues = emptyList(), weekLabels = emptyList(), weekText = "No movement yet",
                trendText = "", categories = emptyList(),
                todayText = todayText, todayCategories = todayCategories, todaySub = todaySub,
                weekSub = weekSub, hasTodayMovement = hasTodayMovement,
                runWeekValues = runWeekly, daysSinceLastRun = daysSinceLastRun, paces = paces
            )
        }

        val weekly = TrainingAnalytics.weekly(
            moved.map { it.epochMs to Units.distanceValue(it.distanceMeters!!, unit).toFloat() },
            sum = true
        ).takeLast(8)

        val thisWeek = weekly.firstOrNull { it.weekStartMs == currentWeekStart }?.value ?: 0f
        val weekText = String.format(Locale.US, "%.1f %s this week", thisWeek, u)
        val trendText = TrainingAnalytics.lastStepPercent(weekly.map { it.value })?.let { pct ->
            if (pct == 0) "even with last week"
            else "${if (pct > 0) "+" else ""}$pct% vs last week"
        } ?: ""

        // This week's distance split by category (soccer/sports, walking, running…).
        val categories = categoriesFor(weekLogs, unit, u).filter { it.distance > 0.01f }

        return Movement(
            weekValues = weekly.map { it.value },
            weekLabels = weekly.map { it.label },
            weekText = weekText,
            trendText = trendText,
            categories = categories,
            todayText = todayText,
            todayCategories = todayCategories,
            todaySub = todaySub,
            weekSub = weekSub,
            hasTodayMovement = hasTodayMovement,
            runWeekValues = runWeekly,
            daysSinceLastRun = daysSinceLastRun,
            paces = paces
        )
    }

    /** "{min} min · {cal} kcal" for a set of logs; blank when there's nothing. */
    private fun movementSub(minutes: Int, calories: Int): String =
        if (minutes <= 0 && calories <= 0) ""
        else String.format(Locale.US, "%d min · %,d kcal", minutes, calories)

    /**
     * Breaks a set of logs down by activity category. Each row shows distance when the activity
     * covers distance, otherwise its active minutes (so strength/yoga still appear). Sorted so the
     * biggest contributor leads.
     */
    private fun categoriesFor(logs: List<ExerciseLog>, unit: UnitSystem, u: String): List<MovementCategory> =
        logs.groupBy { categoryOf(it.type) }
            .map { (cat, list) ->
                val dist = list.sumOf { Units.distanceValue(it.distanceMeters ?: 0.0, unit) }.toFloat()
                val minutes = list.sumOf { it.durationMin }
                val valueText = if (dist > 0.01f) String.format(Locale.US, "%.1f %s", dist, u)
                else "$minutes min"
                MovementCategory(cat, dist, u, colorForCategory(cat), valueText)
            }
            .sortedWith(compareByDescending<MovementCategory> { it.distance }
                .thenByDescending { it.valueText })

    private fun categoryOf(type: String): String = when (type.lowercase(Locale.US)) {
        "run" -> "Running"
        "walk", "hike" -> "Walking"
        "cycling" -> "Cycling"
        "swim" -> "Swimming"
        "row" -> "Rowing"
        "soccer", "basketball", "tennis" -> "Sports"
        else -> "Other"
    }

    @ColorRes
    private fun colorForCategory(category: String): Int = when (category) {
        "Running" -> R.color.accent_exercise
        "Walking" -> R.color.accent_nutrition
        "Cycling" -> R.color.accent_water
        "Swimming" -> R.color.accent_fasting
        "Sports" -> R.color.md_secondary
        "Rowing" -> R.color.brand_ember
        else -> R.color.md_outline
    }

    private fun buildInsight(waterMl: Double, waterGoal: Double, cals: Int, calGoal: Int): String {
        val remainingWater = (waterGoal - waterMl).coerceAtLeast(0.0)
        return when {
            remainingWater > 250 -> "You're ${(remainingWater / 240).toInt()} glasses from your hydration goal — that's closer than it looks."
            cals < calGoal -> "You have ${calGoal - cals} kcal left. Showing up today is the whole game."
            else -> "Great balance today. This is what the future version of you looks like."
        }
    }

    fun startFast() = viewModelScope.launch {
        ai.anthracite.fasttrack.session.LiveSessions.startFast(getApplication())
    }

    /** Ends the running fast; [onEnded] gets it if one actually ended (for the celebration). */
    fun endFast(onEnded: (FastingSession) -> Unit) = viewModelScope.launch {
        ai.anthracite.fasttrack.session.LiveSessions.endFast(getApplication())?.let(onEnded)
    }

    /** Discards the active fast without recording it. */
    fun cancelFast() = viewModelScope.launch {
        ai.anthracite.fasttrack.session.LiveSessions.cancelFast(getApplication())
    }

    /** Persists today's daily update (recap + recommendations) so it's revisitable in Profile. */
    fun archiveToday(
        dateLabel: String,
        recap: DayRecap,
        recommendations: List<Recommendation>,
        streak: Int
    ) = viewModelScope.launch {
        val recText = recommendations.joinToString("\n") { "${it.title} — ${it.detail}" }
        repo.upsertDailyUpdate(
            ai.anthracite.fasttrack.data.local.DailyUpdate(
                dayKey = ai.anthracite.fasttrack.util.DayKeys.today(),
                epochMs = System.currentTimeMillis(),
                dateLabel = dateLabel,
                headline = recap.headline,
                summary = recap.summary,
                recommendations = recText,
                loginStreak = streak
            )
        )
    }
}
