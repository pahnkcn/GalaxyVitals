package app.healthtrack.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [EcgSessionEntity::class],
    version = 2,
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

        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "healthtrack.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
