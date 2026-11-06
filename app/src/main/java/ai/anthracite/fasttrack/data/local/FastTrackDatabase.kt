package ai.anthracite.fasttrack.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        UserProfile::class,
        FastingSession::class,
        WaterLog::class,
        FoodLog::class,
        WeightLog::class,
        ExerciseLog::class,
        WorkoutSession::class,
        ScheduleEvent::class,
        DailyHealthMetric::class,
        JournalEntry::class,
        DailyUpdate::class,
        FoodLogEntryRow::class,
        DailyCheckIn::class,
        WeeklyReview::class
    ],
    version = 8,
    exportSchema = false
)
abstract class FastTrackDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun fastingDao(): FastingDao
    abstract fun waterDao(): WaterDao
    abstract fun foodDao(): FoodDao
    abstract fun weightDao(): WeightDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun dailyHealthDao(): DailyHealthDao
    abstract fun journalDao(): JournalDao
    abstract fun dailyUpdateDao(): DailyUpdateDao
    abstract fun foodLogEntryDao(): FoodLogEntryDao
    abstract fun checkInDao(): CheckInDao
    abstract fun weeklyReviewDao(): WeeklyReviewDao

    companion object {
        @Volatile private var instance: FastTrackDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_health_metric` (
                        `dayKey` TEXT NOT NULL PRIMARY KEY,
                        `epochMs` INTEGER NOT NULL,
                        `stressAvg` INTEGER,
                        `restingHeartRate` INTEGER,
                        `bodyBattery` INTEGER,
                        `sleepMinutes` INTEGER,
                        `source` TEXT NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `workout_session` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startEpochMs` INTEGER NOT NULL,
                        `endEpochMs` INTEGER,
                        `type` TEXT NOT NULL,
                        `completed` INTEGER NOT NULL,
                        `scheduleEventId` INTEGER
                    )"""
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `journal_entry` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `categories` TEXT NOT NULL,
                        `transcript` TEXT NOT NULL,
                        `mood` TEXT
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_journal_entry_createdAt` ON `journal_entry` (`createdAt`)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_update` (
                        `dayKey` TEXT NOT NULL PRIMARY KEY,
                        `epochMs` INTEGER NOT NULL,
                        `dateLabel` TEXT NOT NULL,
                        `headline` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `recommendations` TEXT NOT NULL,
                        `loginStreak` INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `weight_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `epochMs` INTEGER NOT NULL,
                        `dayKey` TEXT NOT NULL,
                        `weightKg` REAL NOT NULL
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_weight_log_dayKey` ON `weight_log` (`dayKey`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `food_log` ADD COLUMN `entryId` TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_food_log_entryId` ON `food_log` (`entryId`)"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `food_log_entry` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `dayKey` TEXT NOT NULL,
                        `loggedAt` TEXT NOT NULL,
                        `mealType` TEXT NOT NULL,
                        `mealName` TEXT,
                        `calories` INTEGER NOT NULL,
                        `caloriesLow` INTEGER,
                        `caloriesHigh` INTEGER,
                        `itemCount` INTEGER NOT NULL,
                        `knownItems` INTEGER NOT NULL,
                        `schemaVersion` TEXT NOT NULL,
                        `json` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_food_log_entry_dayKey` ON `food_log_entry` (`dayKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_food_log_entry_loggedAt` ON `food_log_entry` (`loggedAt`)"
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `daily_checkin` (
                        `dayKey` TEXT NOT NULL PRIMARY KEY,
                        `epochMs` INTEGER NOT NULL,
                        `mood` INTEGER NOT NULL,
                        `energy` INTEGER NOT NULL,
                        `hunger` INTEGER NOT NULL,
                        `note` TEXT
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `weekly_review` (
                        `weekKey` TEXT NOT NULL PRIMARY KEY,
                        `epochMs` INTEGER NOT NULL,
                        `headline` TEXT NOT NULL,
                        `summary` TEXT NOT NULL,
                        `focus` TEXT NOT NULL,
                        `statsJson` TEXT NOT NULL,
                        `aiText` TEXT
                    )"""
                )
            }
        }

        fun get(context: Context): FastTrackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FastTrackDatabase::class.java,
                    "fasttrack.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
