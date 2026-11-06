package ai.anthracite.fasttrack.data.repository

import ai.anthracite.fasttrack.data.health.FastHistory
import ai.anthracite.fasttrack.data.health.FastingProtocol
import ai.anthracite.fasttrack.data.health.WorkoutCalories
import ai.anthracite.fasttrack.data.local.DailyCheckIn
import ai.anthracite.fasttrack.data.local.DataSource
import ai.anthracite.fasttrack.data.local.WeeklyReview
import ai.anthracite.fasttrack.data.local.EventType
import ai.anthracite.fasttrack.data.local.ExerciseLog
import ai.anthracite.fasttrack.data.local.FastTrackDatabase
import ai.anthracite.fasttrack.data.local.FastingSession
import ai.anthracite.fasttrack.data.local.FoodLog
import ai.anthracite.fasttrack.data.local.FoodLogEntryRow
import ai.anthracite.fasttrack.data.local.MealType
import ai.anthracite.fasttrack.nutrition.NutritionCalculator
import ai.anthracite.fasttrack.nutrition.export.ExportRange
import ai.anthracite.fasttrack.nutrition.export.FoodExport
import ai.anthracite.fasttrack.nutrition.model.FoodLogEntry
import ai.anthracite.fasttrack.nutrition.model.Iso8601
import ai.anthracite.fasttrack.nutrition.model.MealSlot
import ai.anthracite.fasttrack.data.local.ScheduleEvent
import ai.anthracite.fasttrack.data.local.UserProfile
import ai.anthracite.fasttrack.data.local.WaterLog
import ai.anthracite.fasttrack.data.local.WeightLog
import ai.anthracite.fasttrack.data.local.WorkoutSession
import ai.anthracite.fasttrack.util.DayKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Remembers fasts removed on this device (keyed by start time) so a sync merge skips them. */
interface FastTombstones {
    fun all(): Set<Long>
    fun add(startEpochMs: Long)

    object None : FastTombstones {
        override fun all(): Set<Long> = emptySet()
        override fun add(startEpochMs: Long) = Unit
    }
}

/**
 * Single entry point the UI uses for all data. Keeps DAOs, day-key bucketing and a few domain
 * actions (start/stop fast, log water, etc.) in one place.
 */
class FastTrackRepository(
    private val db: FastTrackDatabase,
    private val tombstones: FastTombstones = FastTombstones.None
) {

    // ---- Profile ----
    fun observeProfile(): Flow<UserProfile?> = db.profileDao().observe()
    suspend fun getProfileOrNull(): UserProfile? = db.profileDao().get()
    suspend fun getProfile(): UserProfile = db.profileDao().get() ?: UserProfile().also {
        db.profileDao().upsert(it)
    }
    suspend fun saveProfile(profile: UserProfile) = db.profileDao().upsert(profile)

    // ---- Weight ----
    fun observeWeightHistory() = db.weightDao().observeRecent()
    fun observeAllWeights(): Flow<List<WeightLog>> = db.weightDao().observeRecent(365)
    suspend fun latestWeightKg(): Double? = db.weightDao().latest()?.weightKg

    /** Records a weigh-in and updates the profile's current weight so projections stay accurate. */
    suspend fun addWeight(weightKg: Double) {
        db.weightDao().insert(
            WeightLog(epochMs = System.currentTimeMillis(), dayKey = DayKeys.today(), weightKg = weightKg)
        )
        db.profileDao().get()?.let { db.profileDao().update(it.copy(weightKg = weightKg)) }
    }

    // ---- Fasting ----
    fun observeActiveFast(): Flow<FastingSession?> = db.fastingDao().observeActive()
    fun observeFastHistory() = db.fastingDao().observeRecent()
    fun observeFinishedFasts() = db.fastingDao().observeAllFinished()
    fun observeCompletedFasts() = db.fastingDao().observeCompletedCount()

    suspend fun getActiveFast(): FastingSession? = db.fastingDao().getActive()

    suspend fun getLastFinishedFast(): FastingSession? = db.fastingDao().getLastFinished()

    /**
     * Every fasting write goes through this lock: start/end/edit are check-then-write, and the
     * widget, notification and app can fire at the same moment.
     */
    private val fastLock = Mutex()

    /** Starts a fast. No-op (returns false) if one is already running — safe to double-tap. */
    suspend fun startFast(protocol: FastingProtocol, startEpochMs: Long = System.currentTimeMillis()): Boolean =
        fastLock.withLock {
            if (db.fastingDao().getActive() != null) return@withLock false
            db.fastingDao().insert(
                FastingSession(startEpochMs = startEpochMs, goalHours = protocol.fastHours, protocol = protocol.id)
            )
            true
        }

    /**
     * Ends the running fast and returns it, or null if none was running — safe to double-tap.
     * A fast ended within a minute of starting was a mis-tap and is discarded.
     */
    suspend fun endFast(nowMs: Long = System.currentTimeMillis()): FastingSession? = fastLock.withLock {
        val active = db.fastingDao().getActive() ?: return@withLock null
        if (nowMs - active.startEpochMs < FastHistory.MIN_DURATION_MS) {
            removeFast(active)
            return@withLock null
        }
        val ended = active.copy(endEpochMs = nowMs)
        db.fastingDao().update(ended.copy(completed = FastHistory.isCompleted(ended)))
        ended
    }

    /** Discards the in-progress fast entirely (no record kept). Used by "Cancel fast". */
    suspend fun cancelActiveFast() = fastLock.withLock {
        db.fastingDao().getActive()?.let { removeFast(it) }
    }

    suspend fun deleteFast(id: Long) = fastLock.withLock {
        db.fastingDao().getAll().firstOrNull { it.id == id }?.let { removeFast(it) }
    }

    /** Caller holds [fastLock]. */
    private suspend fun removeFast(s: FastingSession) {
        tombstones.add(s.startEpochMs)
        db.fastingDao().delete(s.id)
    }

    /** Switching protocol mid-fast retargets the running fast instead of leaving the old goal. */
    suspend fun retargetActiveFast(protocol: FastingProtocol) = fastLock.withLock {
        val active = db.fastingDao().getActive() ?: return@withLock
        if (active.protocol == protocol.id && active.goalHours == protocol.fastHours) return@withLock
        db.fastingDao().update(active.copy(goalHours = protocol.fastHours, protocol = protocol.id))
    }

    /**
     * The fast the editor should open for a calendar day: the running fast (on the day it started
     * or later), else the fast credited to that day — the same one the calendar colors.
     */
    suspend fun fastForDay(dayKey: String): FastingSession? {
        val all = db.fastingDao().getAll()
        all.firstOrNull { it.endEpochMs == null }
            ?.takeIf { dayKey >= DayKeys.of(it.startEpochMs) }
            ?.let { return it }
        return all.filter { it.endEpochMs != null && FastHistory.dayOf(it) == dayKey }
            .maxByOrNull { FastHistory.hours(it) }
    }

    /** Caller holds [fastLock]. Remote rows are matched to local ones by start time. */
    private suspend fun mergeRemoteFasts(remote: List<org.json.JSONObject>) {
        val localFasts = db.fastingDao().getAll().toMutableList()
        val fastingByStart = localFasts.associateBy { it.startEpochMs }
        val removed = tombstones.all()
        remote.forEach { s ->
            val start = s.optLong("startEpochMs")
            if (start == 0L || start in removed) return@forEach
            val end = if (s.isNull("endEpochMs")) null else s.optLong("endEpochMs")
            val existing = fastingByStart[start]
            if (existing == null) {
                val incoming = FastingSession(
                    startEpochMs = start,
                    endEpochMs = end,
                    goalHours = s.optDouble("goalHours"),
                    protocol = s.optString("protocol")
                ).let { it.copy(completed = FastHistory.isCompleted(it)) }
                // A fast whose start was edited elsewhere arrives under a new start key; inserting
                // it would stack a second, overlapping fast on top of the local one.
                if (FastHistory.conflicts(incoming, localFasts)) return@forEach
                val id = db.fastingDao().insert(incoming)
                localFasts += incoming.copy(id = id)
            } else if (existing.endEpochMs == null && end != null) {
                val ended = existing.copy(endEpochMs = end)
                if (FastHistory.isValidSpan(ended)) {
                    db.fastingDao().update(ended.copy(completed = FastHistory.isCompleted(ended)))
                }
            }
        }
    }

    /** Deletes impossible fasting rows (see [FastHistory]); returns how many were removed. */
    suspend fun repairFastHistory(): Int = fastLock.withLock {
        val all = db.fastingDao().getAll()
        val bad = FastHistory.invalidIds(all)
        all.filter { it.id in bad }.forEach { removeFast(it) }
        bad.size
    }

    enum class SaveFastResult { SAVED, OVERLAPS, TOO_SHORT, IN_FUTURE }

    /** Saves edited fast times, refusing anything that couldn't really have happened. */
    suspend fun saveFastTimes(session: FastingSession, nowMs: Long = System.currentTimeMillis()): SaveFastResult =
        fastLock.withLock {
            val end = session.endEpochMs
            if (session.startEpochMs > nowMs || (end != null && end > nowMs + 60_000L)) {
                return@withLock SaveFastResult.IN_FUTURE
            }
            if (!FastHistory.isValidSpan(session)) return@withLock SaveFastResult.TOO_SHORT
            val all = db.fastingDao().getAll()
            if (FastHistory.conflicts(session, all)) return@withLock SaveFastResult.OVERLAPS
            // Sync matches fasts by start time, so a re-timed fast's old start must not come back.
            all.firstOrNull { it.id == session.id && session.id != 0L }
                ?.takeIf { it.startEpochMs != session.startEpochMs }
                ?.let { tombstones.add(it.startEpochMs) }
            val saved = session.copy(completed = FastHistory.isCompleted(session))
            if (saved.id == 0L) db.fastingDao().insert(saved) else db.fastingDao().update(saved)
            SaveFastResult.SAVED
        }

    // ---- Water ----
    fun observeWaterToday(): Flow<List<WaterLog>> = db.waterDao().observeDay(DayKeys.today())
    fun observeWaterDay(dayKey: String): Flow<List<WaterLog>> = db.waterDao().observeDay(dayKey)
    fun observeWaterTotalToday(): Flow<Double> = db.waterDao().observeDayTotal(DayKeys.today())
    fun observeWaterTotal(dayKey: String): Flow<Double> = db.waterDao().observeDayTotal(dayKey)
    suspend fun addWater(amountMl: Double) = addWaterOn(DayKeys.today(), amountMl)

    /** Log water on a specific day. Back-dated days land at noon so the log ordering stays sane. */
    suspend fun addWaterOn(dayKey: String, amountMl: Double) {
        val epoch = if (dayKey == DayKeys.today()) System.currentTimeMillis()
            else DayKeys.startMs(dayKey) + 12L * 60L * 60L * 1000L
        db.waterDao().insert(WaterLog(epochMs = epoch, dayKey = dayKey, amountMl = amountMl))
    }
    suspend fun deleteWater(id: Long) = db.waterDao().delete(id)

    // ---- Nutrition ----
    fun observeFoodToday(): Flow<List<FoodLog>> = db.foodDao().observeDay(DayKeys.today())
    fun observeFood(dayKey: String): Flow<List<FoodLog>> = db.foodDao().observeDay(dayKey)
    fun observeCaloriesToday(): Flow<Int> = db.foodDao().observeDayCalories(DayKeys.today())
    fun observeCalories(dayKey: String): Flow<Int> = db.foodDao().observeDayCalories(dayKey)

    /** Calories consumed per dayKey since [fromKey], for the nutrition-aware daily recap. */
    fun observeCaloriesByDaySince(fromKey: String): Flow<Map<String, Int>> =
        db.foodDao().observeDailyCaloriesSince(fromKey).map { rows -> rows.associate { it.dayKey to it.total } }

    /** One-shot calories-per-day map (used by the AI daily update). */
    suspend fun caloriesByDay(): Map<String, Int> =
        db.foodDao().getAll().groupBy { it.dayKey }.mapValues { (_, rows) -> rows.sumOf { it.calories } }
    suspend fun addFood(
        name: String,
        meal: MealType,
        calories: Int,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        dayKey: String = DayKeys.today()
    ) {
        // Today logs at "now"; back-dated entries land at noon of that day so ordering stays sane.
        val epoch = if (dayKey == DayKeys.today()) System.currentTimeMillis()
            else DayKeys.startMs(dayKey) + 12L * 60L * 60L * 1000L
        db.foodDao().insert(
            FoodLog(
                epochMs = epoch,
                dayKey = dayKey,
                name = name,
                mealType = meal.name,
                calories = calories,
                proteinG = proteinG,
                carbsG = carbsG,
                fatG = fatG
            )
        )
    }
    suspend fun deleteFood(id: Long) = db.foodDao().delete(id)

    // ---- Rich food log (multi-item meals) ----
    fun observeMealEntries(dayKey: String): Flow<List<FoodLogEntry>> =
        db.foodLogEntryDao().observeDay(dayKey).map { rows -> rows.mapNotNull { it.toEntryOrNull() } }

    fun observeAllMealEntries(): Flow<List<FoodLogEntry>> =
        db.foodLogEntryDao().observeAll().map { rows -> rows.mapNotNull { it.toEntryOrNull() } }

    suspend fun getMealEntry(id: String): FoodLogEntry? =
        db.foodLogEntryDao().get(id)?.toEntryOrNull()

    suspend fun allMealEntries(): List<FoodLogEntry> =
        db.foodLogEntryDao().getAll().mapNotNull { it.toEntryOrNull() }

    suspend fun mealEntriesInRange(fromKey: String, toKey: String): List<FoodLogEntry> =
        db.foodLogEntryDao().inRange(fromKey, toKey).mapNotNull { it.toEntryOrNull() }

    /**
     * Persist (insert or update) a rich meal entry and mirror each of its items into the flat
     * [FoodLog] table so existing dashboards, trends, recap, and web sync keep working unchanged.
     * Only the per-item calories/macros are mirrored (never a parent total) so nothing is
     * double-counted. Re-mirroring on edit first clears the entry's previous flat rows.
     */
    suspend fun saveMealEntry(entry: FoodLogEntry, dayKey: String = DayKeys.today()) {
        val now = System.currentTimeMillis()
        val cal = NutritionCalculator.mealCalories(entry)
        val stored = db.foodLogEntryDao().get(entry.id)
        val normalized = entry.copy(updatedAt = now)
        db.foodLogEntryDao().upsert(
            FoodLogEntryRow(
                id = normalized.id,
                dayKey = dayKey,
                loggedAt = normalized.loggedAt,
                mealType = normalized.mealType.id,
                mealName = normalized.mealName,
                calories = NutritionCalculator.round0(cal.estimate) ?: 0,
                caloriesLow = NutritionCalculator.round0(cal.low),
                caloriesHigh = NutritionCalculator.round0(cal.high),
                itemCount = cal.totalItems,
                knownItems = cal.knownItems,
                schemaVersion = normalized.schemaVersion,
                json = normalized.toJsonString(),
                createdAt = stored?.createdAt ?: now,
                updatedAt = now
            )
        )

        // Re-mirror flat rows.
        db.foodDao().deleteByEntry(normalized.id)
        val epoch = Iso8601.parseOrNull(normalized.loggedAt)
            ?: if (dayKey == DayKeys.today()) now
            else DayKeys.startMs(dayKey) + 12L * 60L * 60L * 1000L
        val meal = mapSlotToMealType(normalized.mealType)
        val mirrored = normalized.items.map { item ->
            FoodLog(
                epochMs = epoch,
                dayKey = dayKey,
                name = item.name,
                mealType = meal.name,
                calories = NutritionCalculator.round0(NutritionCalculator.itemCalories(item).estimate) ?: 0,
                proteinG = NutritionCalculator.itemValue(item, NutritionCalculator.PROTEIN).estimate ?: 0.0,
                carbsG = NutritionCalculator.itemValue(item, NutritionCalculator.CARBS).estimate ?: 0.0,
                fatG = NutritionCalculator.itemValue(item, NutritionCalculator.FAT).estimate ?: 0.0,
                entryId = normalized.id
            )
        }
        if (mirrored.isNotEmpty()) db.foodDao().insertAll(mirrored)
    }

    suspend fun deleteMealEntry(id: String) {
        db.foodDao().deleteByEntry(id)
        db.foodLogEntryDao().delete(id)
    }

    /**
     * Build a self-describing JSON export of the food log for the given [range] (metadata +
     * analytics summary + full entries). Designed to be re-imported / analyzed by the web app.
     */
    suspend fun exportFoodJson(range: ExportRange): String {
        val now = System.currentTimeMillis()
        val resolved = range.resolve(now)
        val entries = if (resolved.isAllTime) allMealEntries()
        else mealEntriesInRange(resolved.fromKey!!, resolved.toKey!!)
        return FoodExport.build(entries, resolved, now)
    }

    /** Suggested share file name for an export of [range]. */
    fun exportFoodFileName(range: ExportRange): String {
        val now = System.currentTimeMillis()
        return FoodExport.fileName(range.resolve(now), now)
    }

    private fun FoodLogEntryRow.toEntryOrNull(): FoodLogEntry? =
        runCatching { FoodLogEntry.fromJsonString(json) }.getOrNull()

    private fun mapSlotToMealType(slot: MealSlot): MealType = when (slot) {
        MealSlot.BREAKFAST -> MealType.BREAKFAST
        MealSlot.LUNCH -> MealType.LUNCH
        MealSlot.DINNER -> MealType.DINNER
        MealSlot.SNACK, MealSlot.UNSPECIFIED -> MealType.SNACK
    }

    // ---- Exercise ----
    fun observeExerciseToday(): Flow<List<ExerciseLog>> = db.exerciseDao().observeDay(DayKeys.today())
    fun observeExerciseDay(dayKey: String): Flow<List<ExerciseLog>> = db.exerciseDao().observeDay(dayKey)
    fun observeExerciseRecent() = db.exerciseDao().observeRecent()
    fun observeExerciseCaloriesToday(): Flow<Int> = db.exerciseDao().observeDayCalories(DayKeys.today())
    fun observeExerciseCalories(dayKey: String): Flow<Int> = db.exerciseDao().observeDayCalories(dayKey)
    fun observeExerciseMinutesToday(): Flow<Int> = db.exerciseDao().observeDayMinutes(DayKeys.today())
    fun observeExerciseMinutes(dayKey: String): Flow<Int> = db.exerciseDao().observeDayMinutes(dayKey)

    // Per-day aggregates for the dashboard 7-day consistency strip.
    fun observeWaterTotalsSince(fromKey: String) = db.waterDao().observeDailyTotalsSince(fromKey)
    fun observeCaloriesSince(fromKey: String) = db.foodDao().observeDailyCaloriesSince(fromKey)
    fun observeExerciseSince(fromKey: String) = db.exerciseDao().observeDailyExerciseSince(fromKey)
    suspend fun addExercise(log: ExerciseLog) = db.exerciseDao().insert(log)
    suspend fun deleteExercise(id: Long) = db.exerciseDao().delete(id)

    fun observeActiveWorkout(): Flow<WorkoutSession?> = db.workoutDao().observeActive()
    suspend fun getActiveWorkout(): WorkoutSession? = db.workoutDao().getActive()

    /** Start a live workout + a schedule event. No-op if one is already running. */
    suspend fun startWorkout(type: String): WorkoutSession {
        getActiveWorkout()?.let { return it }
        val now = System.currentTimeMillis()
        val eventId = db.scheduleDao().insert(
            ScheduleEvent(
                title = type,
                startEpochMs = now,
                endEpochMs = now + 30 * 60_000L,
                type = EventType.WORKOUT.name,
                notes = "In progress"
            )
        )
        val sessionId = db.workoutDao().insert(
            WorkoutSession(
                startEpochMs = now,
                type = type,
                scheduleEventId = eventId
            )
        )
        return db.workoutDao().get(sessionId) ?: WorkoutSession(
            id = sessionId,
            startEpochMs = now,
            type = type,
            scheduleEventId = eventId
        )
    }

    /** Stop the live workout, log minutes/calories, and close the schedule event. */
    suspend fun endWorkout(distanceMeters: Double? = null): FinishedWorkout? {
        val active = getActiveWorkout() ?: return null
        val now = System.currentTimeMillis()
        val durationMin = ((now - active.startEpochMs) / 60_000L).toInt().coerceAtLeast(1)
        val calories = WorkoutCalories.estimate(active.type, durationMin, getProfile().weightKg)
        db.workoutDao().update(active.copy(endEpochMs = now, completed = true))
        val log = ExerciseLog(
            epochMs = active.startEpochMs,
            dayKey = DayKeys.of(active.startEpochMs),
            type = active.type,
            durationMin = durationMin,
            calories = calories,
            distanceMeters = distanceMeters?.takeIf { it > 0 },
            source = DataSource.MANUAL.name
        )
        addExercise(log)
        val event = active.scheduleEventId?.let { db.scheduleDao().get(it) }?.let { existing ->
            val updated = existing.copy(
                endEpochMs = now,
                notes = "$durationMin min · $calories kcal"
            )
            db.scheduleDao().update(updated)
            updated
        }
        return FinishedWorkout(log, event)
    }

    suspend fun scheduleEventForWorkout(session: WorkoutSession): ScheduleEvent? {
        val id = session.scheduleEventId ?: return null
        return db.scheduleDao().get(id)
    }

    // ---- Daily health metrics (Garmin wellness: stress, resting HR, etc.) ----
    fun observeDailyMetrics(): Flow<List<ai.anthracite.fasttrack.data.local.DailyHealthMetric>> =
        db.dailyHealthDao().observeAll()
    suspend fun upsertDailyMetric(metric: ai.anthracite.fasttrack.data.local.DailyHealthMetric) =
        db.dailyHealthDao().upsert(metric)
    suspend fun upsertDailyMetrics(metrics: List<ai.anthracite.fasttrack.data.local.DailyHealthMetric>) =
        db.dailyHealthDao().upsertAll(metrics)

    /** Sleep minutes already recorded for [dayKey] (from any source), if any. */
    suspend fun sleepMinutesFor(dayKey: String): Int? =
        db.dailyHealthDao().get(dayKey)?.sleepMinutes

    /**
     * Save a user-confirmed sleep duration for [dayKey], preserving any other wellness fields
     * already stored for that day (e.g. stress/resting HR from Garmin). Marks the row MANUAL.
     */
    suspend fun setSleepMinutes(dayKey: String, minutes: Int, epochMs: Long) {
        val existing = db.dailyHealthDao().get(dayKey)
        val row = existing?.copy(
            sleepMinutes = minutes,
            source = ai.anthracite.fasttrack.data.local.DataSource.MANUAL.name
        ) ?: ai.anthracite.fasttrack.data.local.DailyHealthMetric(
            dayKey = dayKey,
            epochMs = epochMs,
            sleepMinutes = minutes,
            source = ai.anthracite.fasttrack.data.local.DataSource.MANUAL.name
        )
        db.dailyHealthDao().upsert(row)
    }

    // ---- Schedule ----
    fun observeUpcoming(): Flow<List<ScheduleEvent>> =
        db.scheduleDao().observeUpcoming(System.currentTimeMillis() - 3_600_000L)
    suspend fun addEvent(event: ScheduleEvent): Long = db.scheduleDao().insert(event)
    suspend fun updateEvent(event: ScheduleEvent) = db.scheduleDao().update(event)
    suspend fun deleteEvent(id: Long) = db.scheduleDao().delete(id)
    suspend fun allEvents(): List<ScheduleEvent> = db.scheduleDao().getAll()

    /** Import Google Calendar events, skipping ones we already stored. */
    suspend fun importCalendarEvents(events: List<ScheduleEvent>): Int {
        var imported = 0
        for (event in events) {
            val remoteId = event.calendarEventId
            if (remoteId != null && db.scheduleDao().findByCalendarId(remoteId) != null) continue
            db.scheduleDao().insert(event)
            imported++
        }
        return imported
    }

    // ---- Daily update archive ----
    suspend fun allExerciseLogs(): List<ExerciseLog> = db.exerciseDao().getAll()
    suspend fun allDailyMetrics(): List<ai.anthracite.fasttrack.data.local.DailyHealthMetric> =
        db.dailyHealthDao().getAll()
    fun observeDailyUpdates(): Flow<List<ai.anthracite.fasttrack.data.local.DailyUpdate>> =
        db.dailyUpdateDao().observeAll()
    suspend fun getDailyUpdate(dayKey: String): ai.anthracite.fasttrack.data.local.DailyUpdate? =
        db.dailyUpdateDao().get(dayKey)
    suspend fun upsertDailyUpdate(update: ai.anthracite.fasttrack.data.local.DailyUpdate) =
        db.dailyUpdateDao().upsert(update)

    // ---- Daily check-ins ----
    fun observeCheckIns(): Flow<List<DailyCheckIn>> = db.checkInDao().observeAll()
    fun observeCheckIn(dayKey: String): Flow<DailyCheckIn?> = db.checkInDao().observeDay(dayKey)
    suspend fun allCheckIns(): List<DailyCheckIn> = db.checkInDao().getAll()
    suspend fun saveCheckIn(checkIn: DailyCheckIn) = db.checkInDao().upsert(checkIn)

    // ---- Weekly reviews ----
    fun observeWeeklyReviews(): Flow<List<WeeklyReview>> = db.weeklyReviewDao().observeAll()
    suspend fun getWeeklyReview(weekKey: String): WeeklyReview? = db.weeklyReviewDao().get(weekKey)
    suspend fun saveWeeklyReview(review: WeeklyReview) = db.weeklyReviewDao().upsert(review)
    suspend fun allWeeklyReviews(): List<WeeklyReview> = db.weeklyReviewDao().getAll()

    // ---- Bulk reads for the weekly review / streak engines ----
    suspend fun allWeights(): List<WeightLog> = db.weightDao().getAll()
    suspend fun allFasts(): List<FastingSession> = db.fastingDao().getAll()
    suspend fun allWater(): List<ai.anthracite.fasttrack.data.local.WaterLog> = db.waterDao().getAll()
    suspend fun allFood(): List<ai.anthracite.fasttrack.data.local.FoodLog> = db.foodDao().getAll()

    // ---- Journal ----
    fun observeJournalEntries(): Flow<List<ai.anthracite.fasttrack.data.local.JournalEntry>> =
        db.journalDao().observeAll()
    suspend fun addJournalEntry(entry: ai.anthracite.fasttrack.data.local.JournalEntry): Long =
        db.journalDao().insert(entry)
    suspend fun deleteJournalEntry(id: Long) = db.journalDao().delete(id)

    suspend fun exportSnapshot(prefs: ai.anthracite.fasttrack.util.Prefs): org.json.JSONObject {
        prefs.snapshotUpdatedAt = maxOf(prefs.snapshotUpdatedAt, System.currentTimeMillis())
        val root = org.json.JSONObject()
            .put("version", 1)
            .put("updatedAt", prefs.snapshotUpdatedAt)
        db.profileDao().get()?.let { p ->
            root.put("profile", org.json.JSONObject()
                .put("displayName", p.displayName)
                .put("sex", p.sex)
                .put("birthYear", p.birthYear)
                .put("heightCm", p.heightCm)
                .put("weightKg", p.weightKg)
                .put("goalWeightKg", p.goalWeightKg)
                .put("activityLevel", p.activityLevel)
                .put("goal", p.goal)
                .put("fastingProtocol", p.fastingProtocol)
                .put("unitSystem", p.unitSystem)
                .put("dietType", p.dietType)
                .put("dietaryRestrictions", p.dietaryRestrictions)
            )
        }
        root.put("prefs", prefs.toJson())
        root.put("fasting", org.json.JSONArray().also { arr ->
            db.fastingDao().getAll().forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", s.id)
                        .put("startEpochMs", s.startEpochMs)
                        .put("endEpochMs", s.endEpochMs ?: org.json.JSONObject.NULL)
                        .put("goalHours", s.goalHours)
                        .put("protocol", s.protocol)
                        .put("completed", s.completed)
                )
            }
        })
        root.put("workouts", org.json.JSONArray().also { arr ->
            db.workoutDao().getAll().forEach { s ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", s.id)
                        .put("startEpochMs", s.startEpochMs)
                        .put("endEpochMs", s.endEpochMs ?: org.json.JSONObject.NULL)
                        .put("type", s.type)
                        .put("completed", s.completed)
                        .put("scheduleEventId", s.scheduleEventId ?: org.json.JSONObject.NULL)
                )
            }
        })
        root.put("water", org.json.JSONArray().also { arr ->
            db.waterDao().getAll().forEach { w ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", w.id)
                        .put("epochMs", w.epochMs)
                        .put("dayKey", w.dayKey)
                        .put("amountMl", w.amountMl)
                )
            }
        })
        root.put("food", org.json.JSONArray().also { arr ->
            db.foodDao().getAll().forEach { f ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", f.id)
                        .put("epochMs", f.epochMs)
                        .put("dayKey", f.dayKey)
                        .put("name", f.name)
                        .put("mealType", f.mealType)
                        .put("calories", f.calories)
                        .put("proteinG", f.proteinG)
                        .put("carbsG", f.carbsG)
                        .put("fatG", f.fatG)
                )
            }
        })
        root.put("exercise", org.json.JSONArray().also { arr ->
            db.exerciseDao().getAll().forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", e.id)
                        .put("epochMs", e.epochMs)
                        .put("dayKey", e.dayKey)
                        .put("type", e.type)
                        .put("durationMin", e.durationMin)
                        .put("calories", e.calories)
                        .put("distanceMeters", e.distanceMeters ?: org.json.JSONObject.NULL)
                        .put("avgHeartRate", e.avgHeartRate ?: org.json.JSONObject.NULL)
                        .put("source", e.source)
                        .put("externalId", e.externalId ?: org.json.JSONObject.NULL)
                )
            }
        })
        root.put("schedule", org.json.JSONArray().also { arr ->
            db.scheduleDao().getAll().forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("startEpochMs", e.startEpochMs)
                        .put("endEpochMs", e.endEpochMs)
                        .put("type", e.type)
                        .put("notes", e.notes ?: org.json.JSONObject.NULL)
                        .put("calendarEventId", e.calendarEventId ?: org.json.JSONObject.NULL)
                        .put("synced", e.synced)
                )
            }
        })
        root.put("health", org.json.JSONArray().also { arr ->
            db.dailyHealthDao().getAll().forEach { h ->
                arr.put(
                    org.json.JSONObject()
                        .put("dayKey", h.dayKey)
                        .put("epochMs", h.epochMs)
                        .put("stressAvg", h.stressAvg ?: org.json.JSONObject.NULL)
                        .put("restingHeartRate", h.restingHeartRate ?: org.json.JSONObject.NULL)
                        .put("bodyBattery", h.bodyBattery ?: org.json.JSONObject.NULL)
                        .put("sleepMinutes", h.sleepMinutes ?: org.json.JSONObject.NULL)
                        .put("source", h.source)
                )
            }
        })
        root.put("journal", org.json.JSONArray().also { arr ->
            db.journalDao().getAll().forEach { j ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", j.id)
                        .put("createdAt", j.createdAt)
                        .put("title", j.title)
                        .put("summary", j.summary)
                        .put("categories", j.categories)
                        .put("transcript", j.transcript)
                        .put("mood", j.mood ?: org.json.JSONObject.NULL)
                )
            }
        })
        root.put("weight", org.json.JSONArray().also { arr ->
            db.weightDao().getAll().forEach { w ->
                arr.put(
                    org.json.JSONObject()
                        .put("epochMs", w.epochMs)
                        .put("dayKey", w.dayKey)
                        .put("weightKg", w.weightKg)
                )
            }
        })
        root.put("checkins", org.json.JSONArray().also { arr ->
            db.checkInDao().getAll().forEach { c ->
                arr.put(
                    org.json.JSONObject()
                        .put("dayKey", c.dayKey)
                        .put("epochMs", c.epochMs)
                        .put("mood", c.mood)
                        .put("energy", c.energy)
                        .put("hunger", c.hunger)
                        .put("note", c.note ?: org.json.JSONObject.NULL)
                )
            }
        })
        return root
    }

    suspend fun importSnapshot(root: org.json.JSONObject, prefs: ai.anthracite.fasttrack.util.Prefs) {
        root.optJSONObject("prefs")?.let { prefs.applyJson(it) }
        root.optJSONObject("profile")?.let { p ->
            saveProfile(
                UserProfile(
                    displayName = p.optString("displayName", "Athlete"),
                    sex = p.optString("sex", "OTHER"),
                    birthYear = p.optInt("birthYear", 1995),
                    heightCm = p.optDouble("heightCm", 175.0),
                    weightKg = p.optDouble("weightKg", 80.0),
                    goalWeightKg = p.optDouble("goalWeightKg", 72.0),
                    activityLevel = p.optString("activityLevel", "MODERATE"),
                    goal = p.optString("goal", "LOSE"),
                    fastingProtocol = p.optString("fastingProtocol", "SIXTEEN_EIGHT"),
                    unitSystem = p.optString("unitSystem", "IMPERIAL"),
                    dietType = p.optString("dietType", "BALANCED"),
                    dietaryRestrictions = p.optString("dietaryRestrictions", "")
                )
            )
        }
        fun org.json.JSONArray.objects(): List<org.json.JSONObject> =
            (0 until length()).mapNotNull { i -> optJSONObject(i) }

        root.optJSONArray("fasting")?.objects()?.map { s ->
            FastingSession(
                id = s.optLong("id"),
                startEpochMs = s.optLong("startEpochMs"),
                endEpochMs = if (s.isNull("endEpochMs")) null else s.optLong("endEpochMs"),
                goalHours = s.optDouble("goalHours"),
                protocol = s.optString("protocol"),
                completed = s.optBoolean("completed")
            )
        }?.let { all -> FastHistory.invalidIds(all).let { bad -> all.filterNot { it.id in bad } } }
            ?.takeIf { it.isNotEmpty() }?.let { db.fastingDao().insertAll(it) }

        root.optJSONArray("workouts")?.objects()?.map { s ->
            WorkoutSession(
                id = s.optLong("id"),
                startEpochMs = s.optLong("startEpochMs"),
                endEpochMs = if (s.isNull("endEpochMs")) null else s.optLong("endEpochMs"),
                type = s.optString("type"),
                completed = s.optBoolean("completed"),
                scheduleEventId = if (s.isNull("scheduleEventId")) null else s.optLong("scheduleEventId")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.workoutDao().insertAll(it) }

        root.optJSONArray("water")?.objects()?.map { w ->
            WaterLog(
                id = w.optLong("id"),
                epochMs = w.optLong("epochMs"),
                dayKey = w.optString("dayKey"),
                amountMl = w.optDouble("amountMl")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.waterDao().insertAll(it) }

        root.optJSONArray("food")?.objects()?.map { f ->
            FoodLog(
                id = f.optLong("id"),
                epochMs = f.optLong("epochMs"),
                dayKey = f.optString("dayKey"),
                name = f.optString("name"),
                mealType = f.optString("mealType", MealType.SNACK.name),
                calories = f.optInt("calories"),
                proteinG = f.optDouble("proteinG"),
                carbsG = f.optDouble("carbsG"),
                fatG = f.optDouble("fatG")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.foodDao().insertAll(it) }

        root.optJSONArray("exercise")?.objects()?.map { e ->
            ExerciseLog(
                id = e.optLong("id"),
                epochMs = e.optLong("epochMs"),
                dayKey = e.optString("dayKey"),
                type = e.optString("type"),
                durationMin = e.optInt("durationMin"),
                calories = e.optInt("calories"),
                distanceMeters = if (e.isNull("distanceMeters")) null else e.optDouble("distanceMeters"),
                avgHeartRate = if (e.isNull("avgHeartRate")) null else e.optInt("avgHeartRate"),
                source = e.optString("source", DataSource.MANUAL.name),
                externalId = e.optString("externalId").ifBlank { null }
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.exerciseDao().insertAll(it) }

        root.optJSONArray("schedule")?.objects()?.map { e ->
            ScheduleEvent(
                id = e.optLong("id"),
                title = e.optString("title"),
                startEpochMs = e.optLong("startEpochMs"),
                endEpochMs = e.optLong("endEpochMs"),
                type = e.optString("type"),
                notes = e.optString("notes").ifBlank { null },
                calendarEventId = e.optString("calendarEventId").ifBlank { null },
                synced = e.optBoolean("synced")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.scheduleDao().insertAll(it) }

        root.optJSONArray("health")?.objects()?.map { h ->
            ai.anthracite.fasttrack.data.local.DailyHealthMetric(
                dayKey = h.optString("dayKey"),
                epochMs = h.optLong("epochMs"),
                stressAvg = if (h.isNull("stressAvg")) null else h.optInt("stressAvg"),
                restingHeartRate = if (h.isNull("restingHeartRate")) null else h.optInt("restingHeartRate"),
                bodyBattery = if (h.isNull("bodyBattery")) null else h.optInt("bodyBattery"),
                sleepMinutes = if (h.isNull("sleepMinutes")) null else h.optInt("sleepMinutes"),
                source = h.optString("source")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.dailyHealthDao().upsertAll(it) }

        root.optJSONArray("journal")?.objects()?.map { j ->
            ai.anthracite.fasttrack.data.local.JournalEntry(
                id = j.optLong("id"),
                createdAt = j.optLong("createdAt"),
                title = j.optString("title"),
                summary = j.optString("summary"),
                categories = j.optString("categories"),
                transcript = j.optString("transcript"),
                mood = j.optString("mood").ifBlank { null }
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.journalDao().insertAll(it) }

        parseWeights(root).takeIf { it.isNotEmpty() }?.let { db.weightDao().insertAll(it) }
        parseCheckIns(root).takeIf { it.isNotEmpty() }?.let { db.checkInDao().upsertAll(it) }
    }

    /** Two-way sync: keep local ids, union remote rows by stable keys. */
    suspend fun mergeRemote(root: org.json.JSONObject, prefs: ai.anthracite.fasttrack.util.Prefs) {
        fun org.json.JSONArray.objects(): List<org.json.JSONObject> =
            (0 until length()).mapNotNull { i -> optJSONObject(i) }

        val remoteUpdated = root.optLong("updatedAt", 0L)
        root.optJSONObject("prefs")?.let { remote ->
            val first = remote.optLong("firstOpenEpochMs")
            if (first > 0L && (prefs.firstOpenEpochMs == 0L || first < prefs.firstOpenEpochMs)) {
                prefs.firstOpenEpochMs = first
            }
            if (prefs.baselineWeightKg == 0.0) {
                val baseline = remote.optDouble("baselineWeightKg")
                if (baseline > 0.0) prefs.baselineWeightKg = baseline
            }
            val last = remote.optString("lastWorkoutType")
            if (last.isNotBlank() && prefs.lastWorkoutType.isBlank()) prefs.lastWorkoutType = last
        }

        val localProfile = db.profileDao().get()
        if (root.has("profile") && (localProfile == null || remoteUpdated >= prefs.snapshotUpdatedAt)) {
            root.optJSONObject("profile")?.let { p ->
                saveProfile(
                    UserProfile(
                        displayName = p.optString("displayName", localProfile?.displayName ?: "Athlete"),
                        sex = p.optString("sex", localProfile?.sex ?: "OTHER"),
                        birthYear = p.optInt("birthYear", localProfile?.birthYear ?: 1995),
                        heightCm = p.optDouble("heightCm", localProfile?.heightCm ?: 175.0),
                        weightKg = p.optDouble("weightKg", localProfile?.weightKg ?: 80.0),
                        goalWeightKg = p.optDouble("goalWeightKg", localProfile?.goalWeightKg ?: 72.0),
                        activityLevel = p.optString("activityLevel", localProfile?.activityLevel ?: "MODERATE"),
                        goal = p.optString("goal", localProfile?.goal ?: "LOSE"),
                        fastingProtocol = p.optString("fastingProtocol", localProfile?.fastingProtocol ?: "SIXTEEN_EIGHT"),
                        unitSystem = p.optString("unitSystem", localProfile?.unitSystem ?: "IMPERIAL"),
                        dietType = p.optString("dietType", localProfile?.dietType ?: "BALANCED"),
                        dietaryRestrictions = p.optString("dietaryRestrictions", localProfile?.dietaryRestrictions ?: "")
                    )
                )
            }
        }

        fastLock.withLock { mergeRemoteFasts(root.optJSONArray("fasting")?.objects().orEmpty()) }

        val workoutsByStart = db.workoutDao().getAll().associateBy { it.startEpochMs }
        root.optJSONArray("workouts")?.objects()?.forEach { s ->
            val start = s.optLong("startEpochMs")
            if (start == 0L) return@forEach
            val end = if (s.isNull("endEpochMs")) null else s.optLong("endEpochMs")
            val existing = workoutsByStart[start]
            if (existing == null) {
                db.workoutDao().insert(
                    WorkoutSession(
                        startEpochMs = start,
                        endEpochMs = end,
                        type = s.optString("type"),
                        completed = s.optBoolean("completed"),
                        scheduleEventId = if (s.isNull("scheduleEventId")) null else s.optLong("scheduleEventId")
                    )
                )
            } else if (existing.endEpochMs == null && end != null) {
                db.workoutDao().update(existing.copy(endEpochMs = end, completed = s.optBoolean("completed")))
            }
        }

        val waterByTime = db.waterDao().getAll().associateBy { it.epochMs }
        root.optJSONArray("water")?.objects()?.forEach { w ->
            val at = w.optLong("epochMs")
            if (at == 0L || waterByTime.containsKey(at)) return@forEach
            db.waterDao().insert(
                WaterLog(
                    epochMs = at,
                    dayKey = w.optString("dayKey"),
                    amountMl = w.optDouble("amountMl")
                )
            )
        }

        val foodKeys = db.foodDao().getAll().map { "${it.epochMs}|${it.name}" }.toHashSet()
        root.optJSONArray("food")?.objects()?.forEach { f ->
            val key = "${f.optLong("epochMs")}|${f.optString("name")}"
            if (foodKeys.contains(key)) return@forEach
            db.foodDao().insert(
                FoodLog(
                    epochMs = f.optLong("epochMs"),
                    dayKey = f.optString("dayKey"),
                    name = f.optString("name"),
                    mealType = f.optString("mealType", MealType.SNACK.name),
                    calories = f.optInt("calories"),
                    proteinG = f.optDouble("proteinG"),
                    carbsG = f.optDouble("carbsG"),
                    fatG = f.optDouble("fatG")
                )
            )
        }

        val exerciseKeys = db.exerciseDao().getAll()
            .map { "${it.epochMs}|${it.type}|${it.durationMin}" }
            .toHashSet()
        root.optJSONArray("exercise")?.objects()?.forEach { e ->
            val key = "${e.optLong("epochMs")}|${e.optString("type")}|${e.optInt("durationMin")}"
            if (exerciseKeys.contains(key)) return@forEach
            db.exerciseDao().insert(
                ExerciseLog(
                    epochMs = e.optLong("epochMs"),
                    dayKey = e.optString("dayKey"),
                    type = e.optString("type"),
                    durationMin = e.optInt("durationMin"),
                    calories = e.optInt("calories"),
                    distanceMeters = if (e.isNull("distanceMeters")) null else e.optDouble("distanceMeters"),
                    avgHeartRate = if (e.isNull("avgHeartRate")) null else e.optInt("avgHeartRate"),
                    source = e.optString("source", DataSource.MANUAL.name),
                    externalId = e.optString("externalId").ifBlank { null }
                )
            )
        }

        val scheduleByKey = db.scheduleDao().getAll().associateBy { "${it.startEpochMs}|${it.title}" }
        root.optJSONArray("schedule")?.objects()?.forEach { e ->
            val key = "${e.optLong("startEpochMs")}|${e.optString("title")}"
            val existing = scheduleByKey[key]
            if (existing == null) {
                db.scheduleDao().insert(
                    ScheduleEvent(
                        title = e.optString("title"),
                        startEpochMs = e.optLong("startEpochMs"),
                        endEpochMs = e.optLong("endEpochMs"),
                        type = e.optString("type"),
                        notes = e.optString("notes").ifBlank { null },
                        calendarEventId = e.optString("calendarEventId").ifBlank { null },
                        synced = e.optBoolean("synced")
                    )
                )
            } else {
                val calId = e.optString("calendarEventId").ifBlank { null }
                val end = e.optLong("endEpochMs")
                val notes = e.optString("notes").ifBlank { existing.notes }
                if ((existing.calendarEventId == null && calId != null) || end > existing.endEpochMs) {
                    db.scheduleDao().update(
                        existing.copy(
                            endEpochMs = maxOf(existing.endEpochMs, end),
                            notes = notes,
                            calendarEventId = existing.calendarEventId ?: calId,
                            synced = existing.synced || e.optBoolean("synced")
                        )
                    )
                }
            }
        }

        root.optJSONArray("health")?.objects()?.map { h ->
            ai.anthracite.fasttrack.data.local.DailyHealthMetric(
                dayKey = h.optString("dayKey"),
                epochMs = h.optLong("epochMs"),
                stressAvg = if (h.isNull("stressAvg")) null else h.optInt("stressAvg"),
                restingHeartRate = if (h.isNull("restingHeartRate")) null else h.optInt("restingHeartRate"),
                bodyBattery = if (h.isNull("bodyBattery")) null else h.optInt("bodyBattery"),
                sleepMinutes = if (h.isNull("sleepMinutes")) null else h.optInt("sleepMinutes"),
                source = h.optString("source")
            )
        }?.takeIf { it.isNotEmpty() }?.let { db.dailyHealthDao().upsertAll(it) }

        val journalByTime = db.journalDao().getAll().map { it.createdAt }.toHashSet()
        root.optJSONArray("journal")?.objects()?.forEach { j ->
            val at = j.optLong("createdAt")
            if (at == 0L || journalByTime.contains(at)) return@forEach
            db.journalDao().insert(
                ai.anthracite.fasttrack.data.local.JournalEntry(
                    createdAt = at,
                    title = j.optString("title"),
                    summary = j.optString("summary"),
                    categories = j.optString("categories"),
                    transcript = j.optString("transcript"),
                    mood = j.optString("mood").ifBlank { null }
                )
            )
        }

        val weightTimes = db.weightDao().getAll().map { it.epochMs }.toHashSet()
        parseWeights(root).filter { it.epochMs !in weightTimes }
            .takeIf { it.isNotEmpty() }?.let { db.weightDao().insertAll(it) }

        val localCheckIns = db.checkInDao().getAll().associateBy { it.dayKey }
        parseCheckIns(root).filter { remote ->
            val local = localCheckIns[remote.dayKey]
            local == null || remote.epochMs > local.epochMs
        }.takeIf { it.isNotEmpty() }?.let { db.checkInDao().upsertAll(it) }

        prefs.snapshotUpdatedAt = maxOf(prefs.snapshotUpdatedAt, remoteUpdated)
    }

    private fun parseWeights(root: org.json.JSONObject): List<WeightLog> {
        val arr = root.optJSONArray("weight") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val w = arr.optJSONObject(i) ?: return@mapNotNull null
            val at = w.optLong("epochMs")
            val kg = w.optDouble("weightKg", 0.0)
            if (at == 0L || kg <= 0.0) null
            else WeightLog(epochMs = at, dayKey = w.optString("dayKey").ifBlank { DayKeys.of(at) }, weightKg = kg)
        }
    }

    private fun parseCheckIns(root: org.json.JSONObject): List<DailyCheckIn> {
        val arr = root.optJSONArray("checkins") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val day = c.optString("dayKey")
            if (day.isBlank()) null
            else DailyCheckIn(
                dayKey = day,
                epochMs = c.optLong("epochMs"),
                mood = c.optInt("mood", 3).coerceIn(1, 5),
                energy = c.optInt("energy", 3).coerceIn(1, 5),
                hunger = c.optInt("hunger", 3).coerceIn(1, 5),
                note = if (c.isNull("note")) null else c.optString("note").ifBlank { null }
            )
        }
    }
}

data class FinishedWorkout(
    val log: ExerciseLog,
    val event: ScheduleEvent?
)
