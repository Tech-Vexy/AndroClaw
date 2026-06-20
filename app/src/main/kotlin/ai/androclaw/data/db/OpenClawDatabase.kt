package ai.androclaw.data.db

import androidx.room.*
import ai.androclaw.agent.memory.MemoryDao
import ai.androclaw.agent.memory.MemoryEntity
import kotlinx.coroutines.flow.Flow

// ── Memory ────────────────────────────────────────────────────────────────────

@Entity(tableName = "memory")
data class MemoryEntityRoom(
    @PrimaryKey val id: String,
    val content: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
)

fun MemoryEntityRoom.toMemoryEntity(): MemoryEntity {
    return MemoryEntity(
        id = id,
        content = content,
        tags = tags,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

@Dao
abstract class MemoryDaoRoom : MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRoom(entity: MemoryEntityRoom)

    override suspend fun insert(entity: MemoryEntity) {
        insertRoom(
            MemoryEntityRoom(
                id = entity.id,
                content = entity.content,
                tags = entity.tags,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        )
    }

    @Query("SELECT * FROM memory WHERE id = :id LIMIT 1")
    abstract suspend fun getByIdRoom(id: String): MemoryEntityRoom?

    @Query("SELECT * FROM memory WHERE content LIKE '%' || :query || '%' LIMIT :limit")
    abstract suspend fun searchRoom(query: String, limit: Int): List<MemoryEntityRoom>

    @Query("DELETE FROM memory WHERE id = :id")
    abstract suspend fun deleteByIdRoom(id: String)

    @Query("SELECT * FROM memory ORDER BY updatedAt DESC")
    abstract suspend fun getAllRoom(): List<MemoryEntityRoom>

    override suspend fun getById(id: String): MemoryEntity? {
        return getByIdRoom(id)?.toMemoryEntity()
    }

    override suspend fun search(query: String, limit: Int): List<MemoryEntity> {
        return searchRoom(query, limit).map { it.toMemoryEntity() }
    }

    override suspend fun deleteById(id: String) {
        deleteByIdRoom(id)
    }

    override suspend fun getAll(): List<MemoryEntity> {
        return getAllRoom().map { it.toMemoryEntity() }
    }
}

// ── Chat messages ─────────────────────────────────────────────────────────────

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val role: String,       // "user" | "assistant" | "system"
    val text: String,
    val timestamp: Long,
    val sessionId: String,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MessageEntity>

    @Query("DELETE FROM messages WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)
}

// ── WhatsApp message cache ────────────────────────────────────────────────────

@Entity(tableName = "wa_messages")
data class WaMessageEntity(
    @PrimaryKey val id: String,
    val from: String,
    val body: String,
    val timestamp: Long,
    val type: String,
    val isRead: Boolean = false,
)

@Dao
interface WaMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: WaMessageEntity)

    @Query("SELECT * FROM wa_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<WaMessageEntity>

    @Query("SELECT * FROM wa_messages WHERE `from` = :number ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getFromNumber(number: String, limit: Int): List<WaMessageEntity>

    @Query("UPDATE wa_messages SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: String)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities = [
        MemoryEntityRoom::class,
        MessageEntity::class,
        WaMessageEntity::class,
        OutboxMessage::class,
    ],
    version  = 2,               // bumped for outbox table
    exportSchema = true,
)
abstract class OpenClawDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDaoRoom
    abstract fun messageDao(): MessageDao
    abstract fun waMessageDao(): WaMessageDao
    abstract fun outboxDao(): OutboxDao
}

