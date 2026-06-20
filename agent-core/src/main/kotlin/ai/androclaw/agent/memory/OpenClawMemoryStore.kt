package ai.androclaw.agent.memory

import ai.koog.agents.longtermmemory.model.MemoryRecord
import ai.koog.rag.base.TextDocument
import ai.koog.rag.base.storage.DeletionStorage
import ai.koog.rag.base.storage.LookupStorage
import ai.koog.rag.base.storage.SearchStorage
import ai.koog.rag.base.storage.WriteStorage
import ai.koog.rag.base.storage.search.HasTextQuery
import ai.koog.rag.base.storage.search.Score
import ai.koog.rag.base.storage.search.ScoreMetric
import ai.koog.rag.base.storage.search.SearchRequest
import ai.koog.rag.base.storage.search.SearchResult
import kotlinx.serialization.json.buildJsonObject

/**
 * Koog MemoryStore backed by Android Room / SQLite.
 *
 * Stores long-term memory records (facts the agent has learned about the user,
 * their contacts, preferences, and past interactions).
 *
 * Implements standard Koog RAG storage interfaces so it can plug directly
 * into the LongTermMemory feature.
 */
class OpenClawMemoryStore(
    private val dbPath: String,
) : SearchStorage<TextDocument, SearchRequest>,
    WriteStorage<TextDocument>,
    LookupStorage<TextDocument>,
    DeletionStorage {

    // Injected by the Android app module via DI (Koin)
    var dao: MemoryDao? = null

    // Fallback when Room is not yet initialised (e.g. unit tests)
    private val inMemoryFallback = mutableMapOf<String, MemoryRecord>()

    suspend fun save(record: MemoryRecord) {
        dao?.insert(record.toEntity()) ?: inMemoryFallback.put(record.id ?: "", record)
    }

    suspend fun load(id: String): MemoryRecord? {
        return dao?.getById(id)?.toRecord() ?: inMemoryFallback[id]
    }

    suspend fun search(query: String, limit: Int): List<MemoryRecord> {
        return dao?.search(query, limit)?.map { it.toRecord() }
            ?: inMemoryFallback.values
                .filter { it.content.contains(query, ignoreCase = true) }
                .take(limit)
    }

    suspend fun delete(id: String) {
        dao?.deleteById(id) ?: inMemoryFallback.remove(id)
    }

    suspend fun listAll(): List<MemoryRecord> {
        return dao?.getAll()?.map { it.toRecord() } ?: inMemoryFallback.values.toList()
    }

    // ── SearchStorage ────────────────────────────────────────────────────────

    override suspend fun search(
        request: SearchRequest,
        namespace: String?
    ): List<SearchResult<TextDocument>> {
        val query = (request as? HasTextQuery)?.queryText ?: ""
        val limit = request.limit
        val results = search(query, limit)
        return results.map { record ->
            SearchResult(
                document = record,
                score = Score(1.0, ScoreMetric.CUSTOM),
                id = record.id ?: "",
                metadata = buildJsonObject {},
                namespace = namespace ?: ""
            )
        }
    }

    // ── WriteStorage ─────────────────────────────────────────────────────────

    override suspend fun add(
        documents: List<TextDocument>,
        namespace: String?
    ): List<String> {
        val records = documents.mapNotNull { it as? MemoryRecord }
        records.forEach { save(it) }
        return records.map { it.id ?: "" }
    }

    override suspend fun update(
        documents: Map<String, TextDocument>,
        namespace: String?
    ): List<String> {
        val records = documents.values.mapNotNull { it as? MemoryRecord }
        records.forEach { save(it) }
        return records.map { it.id ?: "" }
    }

    // ── LookupStorage ────────────────────────────────────────────────────────

    override suspend fun get(
        ids: List<String>,
        namespace: String?
    ): List<TextDocument> {
        return ids.mapNotNull { load(it) }
    }

    // ── DeletionStorage ──────────────────────────────────────────────────────

    override suspend fun delete(
        ids: List<String>,
        namespace: String?
    ): List<String> {
        ids.forEach { delete(it) }
        return ids
    }
}

// ── DAO interface (implemented in :app module with Room annotations) ──────────

interface MemoryDao {
    suspend fun insert(entity: MemoryEntity)
    suspend fun getById(id: String): MemoryEntity?
    suspend fun search(query: String, limit: Int): List<MemoryEntity>
    suspend fun deleteById(id: String)
    suspend fun getAll(): List<MemoryEntity>
}

data class MemoryEntity(
    val id: String,
    val content: String,
    val tags: String,           // JSON array of tags
    val createdAt: Long,
    val updatedAt: Long,
)

// Extension mappers

fun MemoryRecord.toEntity(): MemoryEntity {
    val tagsList = (metadata["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    val created = (metadata["createdAt"] as? Number)?.toLong() ?: 0L
    val updated = (metadata["updatedAt"] as? Number)?.toLong() ?: 0L
    return MemoryEntity(
        id        = id ?: "",
        content   = content,
        tags      = tagsList.joinToString(","),
        createdAt = created,
        updatedAt = updated,
    )
}

fun MemoryEntity.toRecord() = MemoryRecord(
    id        = id,
    content   = content,
    metadata  = mapOf(
        "tags" to tags.split(",").filter { it.isNotBlank() },
        "createdAt" to createdAt,
        "updatedAt" to updatedAt,
    )
)
