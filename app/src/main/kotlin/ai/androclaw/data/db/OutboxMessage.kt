package ai.androclaw.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ── Outbox entity ─────────────────────────────────────────────────────────────

@Entity(tableName = "outbox")
data class OutboxMessage(
    @PrimaryKey val id: String,
    val channel: String,        // "sms" | "whatsapp" | "telegram" | "email"
    val to: String,             // recipient (phone, WA number, TG username, email)
    val body: String,           // message text
    val createdAt: Long,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long = 0L,
    val status: String = "pending",  // "pending" | "sent" | "failed"
    val errorMessage: String = "",
)

// ── Outbox DAO ────────────────────────────────────────────────────────────────

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: OutboxMessage)

    @Query("SELECT * FROM outbox WHERE status = 'pending' ORDER BY createdAt ASC")
    suspend fun getPending(): List<OutboxMessage>

    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<OutboxMessage>>

    @Query("UPDATE outbox SET status = :status, lastAttemptAt = :now, " +
           "attemptCount = attemptCount + 1, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, now: Long, error: String = "")

    @Query("DELETE FROM outbox WHERE status = 'sent' AND createdAt < :beforeMs")
    suspend fun clearSent(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM outbox WHERE status = 'pending'")
    fun pendingCount(): Flow<Int>
}

