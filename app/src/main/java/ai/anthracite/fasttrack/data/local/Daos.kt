package ai.anthracite.fasttrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Per-day aggregate projections used by the dashboard's 7-day consistency strip. */
data class DayAmount(val dayKey: String, val total: Double)
data class DayInt(val dayKey: String, val total: Int)
data class DayExercise(val dayKey: String, val minutes: Int, val calories: Int)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: UserProfile)

    @Update
    suspend fun update(profile: UserProfile)
}

@Dao
interface FastingDao {
    @Query("SELECT * FROM fasting_session WHERE endEpochMs IS NULL ORDER BY startEpochMs DESC LIMIT 1")
    fun observeActive(): Flow<FastingSession?>

    @Query("SELECT * FROM fasting_session WHERE endEpochMs IS NULL ORDER BY startEpochMs DESC LIMIT 1")
    suspend fun getActive(): FastingSession?

    @Query("SELECT * FROM fasting_session ORDER BY startEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 30): Flow<List<FastingSession>>

    /** All finished sessions (newest first) — powers the calendar + streak math. */
    @Query("SELECT * FROM fasting_session WHERE endEpochMs IS NOT NULL ORDER BY startEpochMs DESC")
    fun observeAllFinished(): Flow<List<FastingSession>>

    /** The most recently ended fast — drives the widget's eating-window countdown. */
    @Query("SELECT * FROM fasting_session WHERE endEpochMs IS NOT NULL ORDER BY endEpochMs DESC LIMIT 1")
    suspend fun getLastFinished(): FastingSession?

    @Query("SELECT COUNT(*) FROM fasting_session WHERE completed = 1")
    fun observeCompletedCount(): Flow<Int>

    @Query("SELECT * FROM fasting_session ORDER BY startEpochMs ASC")
    suspend fun getAll(): List<FastingSession>

    @Query("SELECT * FROM fasting_session WHERE startEpochMs >= :fromMs AND startEpochMs <= :toMs ORDER BY startEpochMs DESC")
    suspend fun startingIn(fromMs: Long, toMs: Long): List<FastingSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<FastingSession>)

    @Insert
    suspend fun insert(session: FastingSession): Long

    @Update
    suspend fun update(session: FastingSession)

    @Query("DELETE FROM fasting_session WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workout_session WHERE endEpochMs IS NULL ORDER BY startEpochMs DESC LIMIT 1")
    fun observeActive(): Flow<WorkoutSession?>

    @Query("SELECT * FROM workout_session WHERE endEpochMs IS NULL ORDER BY startEpochMs DESC LIMIT 1")
    suspend fun getActive(): WorkoutSession?

    @Query("SELECT * FROM workout_session WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): WorkoutSession?

    @Query("SELECT * FROM workout_session ORDER BY startEpochMs ASC")
    suspend fun getAll(): List<WorkoutSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<WorkoutSession>)

    @Insert
    suspend fun insert(session: WorkoutSession): Long

    @Update
    suspend fun update(session: WorkoutSession)
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water_log WHERE dayKey = :dayKey ORDER BY epochMs DESC")
    fun observeDay(dayKey: String): Flow<List<WaterLog>>

    @Query("SELECT COALESCE(SUM(amountMl), 0) FROM water_log WHERE dayKey = :dayKey")
    fun observeDayTotal(dayKey: String): Flow<Double>

    @Query(
        "SELECT dayKey AS dayKey, COALESCE(SUM(amountMl), 0) AS total FROM water_log " +
            "WHERE dayKey >= :fromKey GROUP BY dayKey"
    )
    fun observeDailyTotalsSince(fromKey: String): Flow<List<DayAmount>>

    @Query("SELECT * FROM water_log ORDER BY epochMs ASC")
    suspend fun getAll(): List<WaterLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<WaterLog>)

    @Insert
    suspend fun insert(log: WaterLog): Long

    @Query("DELETE FROM water_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_log WHERE dayKey = :dayKey ORDER BY epochMs DESC")
    fun observeDay(dayKey: String): Flow<List<FoodLog>>

    @Query("SELECT COALESCE(SUM(calories), 0) FROM food_log WHERE dayKey = :dayKey")
    fun observeDayCalories(dayKey: String): Flow<Int>

    @Query(
        "SELECT dayKey AS dayKey, COALESCE(SUM(calories), 0) AS total FROM food_log " +
            "WHERE dayKey >= :fromKey GROUP BY dayKey"
    )
    fun observeDailyCaloriesSince(fromKey: String): Flow<List<DayInt>>

    @Query("SELECT * FROM food_log ORDER BY epochMs ASC")
    suspend fun getAll(): List<FoodLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<FoodLog>)

    @Insert
    suspend fun insert(log: FoodLog): Long

    @Query("DELETE FROM food_log WHERE id = :id")
    suspend fun delete(id: Long)

    /** Remove all flat rows mirrored from a given rich entry (used before re-mirroring on edit). */
    @Query("DELETE FROM food_log WHERE entryId = :entryId")
    suspend fun deleteByEntry(entryId: String)
}

@Dao
interface FoodLogEntryDao {
    @Query("SELECT * FROM food_log_entry WHERE dayKey = :dayKey ORDER BY loggedAt DESC")
    fun observeDay(dayKey: String): Flow<List<FoodLogEntryRow>>

    @Query("SELECT * FROM food_log_entry ORDER BY loggedAt DESC")
    fun observeAll(): Flow<List<FoodLogEntryRow>>

    @Query("SELECT * FROM food_log_entry WHERE id = :id LIMIT 1")
    suspend fun get(id: String): FoodLogEntryRow?

    @Query("SELECT * FROM food_log_entry ORDER BY loggedAt ASC")
    suspend fun getAll(): List<FoodLogEntryRow>

    @Query("SELECT * FROM food_log_entry WHERE dayKey >= :fromKey AND dayKey <= :toKey ORDER BY loggedAt ASC")
    suspend fun inRange(fromKey: String, toKey: String): List<FoodLogEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: FoodLogEntryRow)

    @Query("DELETE FROM food_log_entry WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_log ORDER BY epochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 60): Flow<List<WeightLog>>

    @Query("SELECT * FROM weight_log ORDER BY epochMs DESC LIMIT 1")
    suspend fun latest(): WeightLog?

    @Query("SELECT * FROM weight_log ORDER BY epochMs ASC")
    suspend fun getAll(): List<WeightLog>

    @Insert
    suspend fun insert(log: WeightLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<WeightLog>)

    @Query("DELETE FROM weight_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercise_log WHERE dayKey = :dayKey ORDER BY epochMs DESC")
    fun observeDay(dayKey: String): Flow<List<ExerciseLog>>

    @Query("SELECT * FROM exercise_log ORDER BY epochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<ExerciseLog>>

    @Query("SELECT COALESCE(SUM(calories), 0) FROM exercise_log WHERE dayKey = :dayKey")
    fun observeDayCalories(dayKey: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMin), 0) FROM exercise_log WHERE dayKey = :dayKey")
    fun observeDayMinutes(dayKey: String): Flow<Int>

    @Query(
        "SELECT dayKey AS dayKey, COALESCE(SUM(durationMin), 0) AS minutes, " +
            "COALESCE(SUM(calories), 0) AS calories FROM exercise_log " +
            "WHERE dayKey >= :fromKey GROUP BY dayKey"
    )
    fun observeDailyExerciseSince(fromKey: String): Flow<List<DayExercise>>

    @Query("SELECT * FROM exercise_log ORDER BY epochMs ASC")
    suspend fun getAll(): List<ExerciseLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<ExerciseLog>)

    /** IGNORE so re-importing the same source+externalId is a no-op (de-dup guard). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: ExerciseLog): Long

    @Query("SELECT * FROM exercise_log WHERE source = :source AND externalId = :externalId LIMIT 1")
    suspend fun findBySource(source: String, externalId: String): ExerciseLog?

    /** Same activity type started within [toleranceMs] of [startMs] — the de-dup match window. */
    @Query(
        "SELECT * FROM exercise_log WHERE type = :type " +
            "AND ABS(epochMs - :startMs) <= :toleranceMs"
    )
    suspend fun findSameTypeNearStart(type: String, startMs: Long, toleranceMs: Long): List<ExerciseLog>

    @Query("DELETE FROM exercise_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface DailyHealthDao {
    @Query("SELECT * FROM daily_health_metric ORDER BY dayKey ASC")
    fun observeAll(): Flow<List<DailyHealthMetric>>

    @Query("SELECT * FROM daily_health_metric WHERE dayKey >= :fromKey ORDER BY dayKey ASC")
    fun observeSince(fromKey: String): Flow<List<DailyHealthMetric>>

    @Query("SELECT * FROM daily_health_metric ORDER BY dayKey ASC")
    suspend fun getAll(): List<DailyHealthMetric>

    @Query("SELECT * FROM daily_health_metric WHERE dayKey = :dayKey LIMIT 1")
    suspend fun get(dayKey: String): DailyHealthMetric?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(metric: DailyHealthMetric)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(metrics: List<DailyHealthMetric>)
}

@Dao
interface CheckInDao {
    @Query("SELECT * FROM daily_checkin ORDER BY dayKey ASC")
    fun observeAll(): Flow<List<DailyCheckIn>>

    @Query("SELECT * FROM daily_checkin WHERE dayKey = :dayKey LIMIT 1")
    fun observeDay(dayKey: String): Flow<DailyCheckIn?>

    @Query("SELECT * FROM daily_checkin ORDER BY dayKey ASC")
    suspend fun getAll(): List<DailyCheckIn>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkIn: DailyCheckIn)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(checkIns: List<DailyCheckIn>)
}

@Dao
interface WeeklyReviewDao {
    @Query("SELECT * FROM weekly_review ORDER BY weekKey DESC")
    fun observeAll(): Flow<List<WeeklyReview>>

    @Query("SELECT * FROM weekly_review WHERE weekKey = :weekKey LIMIT 1")
    suspend fun get(weekKey: String): WeeklyReview?

    @Query("SELECT * FROM weekly_review")
    suspend fun getAll(): List<WeeklyReview>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(review: WeeklyReview)
}

@Dao
interface DailyUpdateDao {
    @Query("SELECT * FROM daily_update ORDER BY dayKey DESC")
    fun observeAll(): Flow<List<DailyUpdate>>

    @Query("SELECT * FROM daily_update WHERE dayKey = :dayKey LIMIT 1")
    suspend fun get(dayKey: String): DailyUpdate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(update: DailyUpdate)
}

@Dao
interface JournalDao {
    @Query("SELECT * FROM journal_entry ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<JournalEntry>>

    @Query("SELECT * FROM journal_entry WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): JournalEntry?

    @Query("SELECT * FROM journal_entry ORDER BY createdAt ASC")
    suspend fun getAll(): List<JournalEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<JournalEntry>)

    @Insert
    suspend fun insert(entry: JournalEntry): Long

    @Query("DELETE FROM journal_entry WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_event WHERE endEpochMs >= :fromMs ORDER BY startEpochMs ASC")
    fun observeUpcoming(fromMs: Long): Flow<List<ScheduleEvent>>

    @Query("SELECT * FROM schedule_event ORDER BY startEpochMs ASC")
    fun observeAll(): Flow<List<ScheduleEvent>>

    @Query("SELECT * FROM schedule_event ORDER BY startEpochMs ASC")
    suspend fun getAll(): List<ScheduleEvent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ScheduleEvent>)

    @Insert
    suspend fun insert(event: ScheduleEvent): Long

    @Update
    suspend fun update(event: ScheduleEvent)

    @Query("SELECT * FROM schedule_event WHERE id = :id LIMIT 1")
    suspend fun get(id: Long): ScheduleEvent?

    @Query("SELECT * FROM schedule_event WHERE calendarEventId = :remoteId LIMIT 1")
    suspend fun findByCalendarId(remoteId: String): ScheduleEvent?

    @Query("DELETE FROM schedule_event WHERE id = :id")
    suspend fun delete(id: Long)
}
