package app.healthtrack.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EcgSessionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ecgSessionDao(): EcgSessionDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(context, AppDatabase::class.java, "healthtrack.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
