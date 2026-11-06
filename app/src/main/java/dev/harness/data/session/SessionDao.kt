package dev.harness.data.session

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updated_at DESC")
    suspend fun all(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query(
        "SELECT * FROM sessions WHERE title LIKE '%' || :q || '%' OR tags LIKE '%' || :q || '%' " +
            "OR messages_json LIKE '%' || :q || '%' ORDER BY updated_at DESC LIMIT :limit"
    )
    suspend fun search(q: String, limit: Int = 25): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE (',' || tags || ',') LIKE '%,' || :tag || ',%' ORDER BY updated_at DESC")
    suspend fun byTag(tag: String): List<SessionEntity>

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
