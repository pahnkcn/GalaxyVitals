package app.healthtrack.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface EcgSessionDao {
    @Query("SELECT * FROM ecg_sessions ORDER BY tsStartMs DESC")
    fun observeAll(): Flow<List<EcgSessionEntity>>

    @Query("SELECT * FROM ecg_sessions ORDER BY tsStartMs DESC LIMIT 1")
    fun observeLatest(): Flow<EcgSessionEntity?>

    @Query("SELECT * FROM ecg_sessions WHERE sessionId = :id LIMIT 1")
    suspend fun get(id: String): EcgSessionEntity?

    @Query(
        """SELECT * FROM ecg_sessions WHERE captureSource = 'DEMO' OR
            (source = 'IMPORT' AND watchInfo = 'demo' AND tsStartMs = 1700000000000 AND
             srHz = 500 AND nSamples = 4000 AND (sessionId = 'demo' OR sessionId LIKE 'demo-%'))""",
    )
    suspend fun getDemoCleanupCandidates(): List<EcgSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EcgSessionEntity)

    @Query("DELETE FROM ecg_sessions WHERE sessionId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM ecg_sessions")
    fun observeCount(): Flow<Int>
}
