package app.galaxyvitals.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EcgSessionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ecgSessionDao(): EcgSessionDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ecg_sessions " +
                        "ADD COLUMN analysisStatus TEXT NOT NULL DEFAULT 'NONE'",
                )
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN naoLabel TEXT")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN naoConfidence REAL")
                db.execSQL(
                    "ALTER TABLE ecg_sessions " +
                        "ADD COLUMN findings TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE ecg_sessions " +
                        "ADD COLUMN analysisNote TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN inputSchemaVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN timingTrust TEXT NOT NULL DEFAULT 'ASSUMED'")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN qualityStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN cleanCoveragePct REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN qualityFlagsJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN ecgHrMedian REAL")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN analysisBundleId TEXT")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN payloadSha256 TEXT")
                db.execSQL("ALTER TABLE ecg_sessions ADD COLUMN captureSource TEXT NOT NULL DEFAULT 'LEGACY'")
            }
        }

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "healthtrack.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
