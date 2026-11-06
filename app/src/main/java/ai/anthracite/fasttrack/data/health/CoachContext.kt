package ai.anthracite.fasttrack.data.health

import ai.anthracite.fasttrack.FastTrackApp
import ai.anthracite.fasttrack.util.DayKeys
import ai.anthracite.fasttrack.util.UnitSystem
import ai.anthracite.fasttrack.util.Units

/**
 * Builds the live, data-grounded context + instructions shared by every on-device-AI surface:
 * the Coach chat, the Coach's proactive greeting, and the AI "daily update" popup. Keeping this in
 * one place means advice is always grounded in the user's *actual* state — fasting, recovery
 * (Garmin sleep / stress / resting HR / body battery), today's movement, hydration, and calories.
 */
object CoachContext {

    /**
     * A compact snapshot of the user's day for the model's system prompt. Everything is best-effort;
     * missing data is simply omitted so the model never invents numbers.
     */
    suspend fun snapshot(app: FastTrackApp): String {
        val repo = app.repository
        val prefs = app.prefs
        val profile = repo.getProfile()
        val targets = GuidanceEngine.compute(profile)
        val unit = runCatching { UnitSystem.valueOf(profile.unitSystem) }.getOrDefault(UnitSystem.IMPERIAL)
        val protocol = FastingProtocol.fromId(profile.fastingProtocol)
        val todayKey = DayKeys.today()
        val now = System.currentTimeMillis()

        val json = repo.exportSnapshot(prefs)

        // ---- Recent meals + today's calories ----
        val food = json.optJSONArray("food")
        val meals = StringBuilder()
        var caloriesToday = 0
        if (food != null) {
            val start = (food.length() - 6).coerceAtLeast(0)
            for (i in 0 until food.length()) {
                val row = food.optJSONObject(i) ?: continue
                if (row.optString("dayKey") == todayKey) caloriesToday += row.optInt("calories")
                if (i >= start) {
                    if (meals.isNotEmpty()) meals.append(", ")
                    meals.append(row.optString("name")).append(" (").append(row.optInt("calories")).append(" kcal)")
                }
            }
        }
        if (meals.isEmpty()) meals.append("none yet")

        // ---- Hydration today ----
        val water = json.optJSONArray("water")
        var waterMlToday = 0.0
        if (water != null) {
            for (i in 0 until water.length()) {
                val row = water.optJSONObject(i) ?: continue
                if (row.optString("dayKey") == todayKey) waterMlToday += row.optDouble("amountMl", 0.0)
            }
        }
        val waterGoalMl = GuidanceEngine.waterGoalMl(profile.weightKg, prefs.waterGoalOverrideOz)

        // ---- Today's movement ----
        val todayExercise = repo.allExerciseLogs().filter { it.dayKey == todayKey }
        val activeMin = todayExercise.sumOf { it.durationMin }
        val activeKcal = todayExercise.sumOf { it.calories }
        val activities = todayExercise.map { it.type }.distinct().joinToString(", ")

        // ---- Last night's recovery (most recent day that actually has any Garmin wellness) ----
        val metric = repo.allDailyMetrics()
            .filter { it.sleepMinutes != null || it.stressAvg != null || it.restingHeartRate != null || it.bodyBattery != null }
            .maxByOrNull { it.dayKey }

        val readiness = RecoveryEngine.assess(repo.allDailyMetrics(), todayKey, DayKeys.of(now - 86_400_000L))
        val weightTrend = WeightTrend.compute(
            repo.allWeights().map { WeightTrend.Point(it.epochMs, it.weightKg) }, profile.goalWeightKg, now
        )
        val checkIns = repo.allCheckIns()
        val checkIn = checkIns.firstOrNull { it.dayKey == todayKey }
        val checkInInsights = CheckInInsights.compute(
            checkIns,
            repo.allFasts().filter { it.endEpochMs != null }.groupBy { DayKeys.of(it.endEpochMs!!) }
                .mapValues { (_, f) -> f.maxOf { (it.endEpochMs!! - it.startEpochMs) / 3_600_000.0 } },
            todayKey
        )

        // ---- Fasting / eating-window state ----
        val activeFast = repo.getActiveFast()
        val fastLine = if (activeFast != null) {
            val hrs = (now - activeFast.startEpochMs) / 3_600_000.0
            "Fasting: %.1fh into a %.0fh fast".format(hrs, activeFast.goalHours)
        } else {
            val eatingWindowMs = ((24.0 - protocol.fastHours) * 3_600_000L).toLong()
            val nextStart = repo.getLastFinishedFast()?.endEpochMs?.let { it + eatingWindowMs }
            if (nextStart != null && eatingWindowMs > 0L && now < nextStart) {
                "Fasting: in the eating window, next fast in %.1fh".format((nextStart - now) / 3_600_000.0)
            } else "Fasting: not currently fasting"
        }

        return buildString {
            appendLine("Name: ${profile.displayName.ifBlank { "the user" }}")
            appendLine("Goal: ${profile.goal} toward ${profile.goalWeightKg} kg from ${profile.weightKg} kg")
            appendLine("Protocol: ${protocol.displayName}, diet ${profile.dietType}")
            if (profile.dietaryRestrictions.isNotBlank()) appendLine("Dietary restrictions: ${profile.dietaryRestrictions}")
            appendLine()
            appendLine("TODAY so far:")
            appendLine("- $fastLine")
            appendLine("- Calories: $caloriesToday / ${targets.calories} kcal")
            appendLine("- Water: ${Units.volumeLabel(waterMlToday, unit)} / ${Units.volumeLabel(waterGoalMl, unit)}")
            if (activeMin > 0) {
                appendLine("- Movement: $activeMin min active, $activeKcal kcal${if (activities.isNotBlank()) " ($activities)" else ""}")
            } else {
                appendLine("- Movement: nothing logged yet")
            }
            if (metric != null) {
                appendLine()
                val whenLabel = when (metric.dayKey) {
                    todayKey -> "today"
                    DayKeys.of(now - 86_400_000L) -> "last night"
                    else -> metric.dayKey
                }
                appendLine("RECOVERY ($whenLabel, from Garmin):")
                metric.sleepMinutes?.let { appendLine("- Sleep: %.1fh".format(it / 60.0)) }
                metric.stressAvg?.let {
                    val band = when { it <= 25 -> "resting"; it <= 50 -> "low"; it <= 75 -> "moderate"; else -> "high" }
                    appendLine("- Avg stress: $it ($band)")
                }
                metric.restingHeartRate?.let { appendLine("- Resting HR: $it bpm") }
                metric.bodyBattery?.let { appendLine("- Body Battery: $it/100") }
            }
            readiness?.let { r ->
                appendLine("- Readiness: ${r.score}/100 (${r.level.name.lowercase()}). Fasting advice: ${r.fastingAdvice}")
            }
            weightTrend?.kgPerWeek?.let { rate ->
                appendLine()
                appendLine("WEIGHT TREND: %+.2f kg/week over recent weigh-ins (trend weight %.1f kg)".format(rate, weightTrend.latestTrendKg))
            }
            if (checkIn != null || checkInInsights.headline != null) {
                appendLine()
                appendLine("CHECK-IN:")
                checkIn?.let {
                    appendLine(
                        "- Today: mood ${CheckInInsights.MOOD_LABELS[it.mood - 1]}, energy ${CheckInInsights.ENERGY_LABELS[it.energy - 1]}, " +
                            "hunger ${CheckInInsights.HUNGER_LABELS[it.hunger - 1]}" + (it.note?.let { n -> " — \"$n\"" } ?: "")
                    )
                }
                checkInInsights.headline?.takeIf { !it.startsWith("Check in") && !it.startsWith("Keep checking") }
                    ?.let { appendLine("- Pattern: $it") }
            }
            appendLine()
            append("Recent meals: $meals")
        }
    }

    /** A short, friendly contextual check-in (used for the Coach's proactive greeting). */
    fun greetingInstruction(): String =
        "Greet me by name and give a short, friendly check-in based on my data — mention sleep, fasting, " +
            "or movement only if notable, then suggest ONE thing to focus on right now. 2–3 sentences, " +
            "warm, no lists, no speaker label."

    /** The full once-a-day update: recap of the recent day + goal-aware recommendations for today. */
    private suspend fun dailyUpdateInstruction(app: FastTrackApp): String {
        val profile = app.repository.getProfile()
        val target = GuidanceEngine.compute(profile).calories
        val recap = DailyRecapEngine.yesterday(
            app.repository.allDailyMetrics(),
            app.repository.allExerciseLogs(),
            app.repository.caloriesByDay(),
            target
        )
        return buildString {
            append("Write my DAILY UPDATE as my personal coach. Greet me by name. ")
            if (recap.hasData) {
                append("Here is an honest read of YESTERDAY (use it, don't quote it verbatim): ")
                append("${recap.headline} — ${recap.summary} ")
            }
            append("Keep my goal in mind (${profile.goal} toward ${profile.goalWeightKg} kg) and today's data above. ")
            append("Be truthful: if I ate well over my calorie target or slept poorly, gently name it — ")
            append("don't call a heavy day 'steady' or 'solid'. Only say I'm fasting if the Fasting line above ")
            append("says I'm into a fast. Open with one warm, honest sentence about how ")
            append("yesterday actually went, then give me 2–3 specific, doable recommendations for TODAY ")
            append("(fasting, food, movement, hydration, recovery) as a short bullet list. ")
            append("Encouraging and kind, never preachy. No medical advice. No speaker label.")
        }
    }

    /**
     * Generate the AI daily update text on-device, or null if the model isn't ready / failed. Uses a
     * dedicated one-shot session so it never disturbs an in-progress Coach chat.
     */
    suspend fun generateDailyUpdate(app: FastTrackApp): String? {
        if (!app.prefs.aiFeaturesEnabled || !app.onDeviceLlm.isModelPresent) return null
        val turn = app.onDeviceLlm.coachReply(
            sessionId = "daily-update-${DayKeys.today()}",
            history = listOf("user" to dailyUpdateInstruction(app)),
            context = snapshot(app)
        )
        return turn.rawPreview.ifBlank { null }
    }
}
