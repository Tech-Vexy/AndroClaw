package ai.androclaw.tools.memory

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import ai.koog.serialization.TypeToken
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.agent.memory.OpenClawMemoryStore
import ai.koog.agents.longtermmemory.model.MemoryRecord
import kotlinx.serialization.Serializable

@Serializable data class MemorySaveResult(val id: String, val status: String)
@Serializable data class MemorySearchResult(val records: List<MemoryEntry>)
@Serializable data class MemoryEntry(val id: String, val content: String, val tags: List<String>, val createdAt: Long)
@Serializable data class MemoryDeleteResult(val id: String, val deleted: Boolean)

/**
 * Gives the agent explicit control over its own long-term memory store.
 * The agent can save facts, search them, and delete outdated ones.
 *
 * This is separate from Koog's built-in chat memory (short-term context window).
 * These are persistent facts that survive across sessions.
 */
class MemoryTools(private val config: OpenClawConfig) {

    // Injected via DI after construction
    var store: OpenClawMemoryStore? = null

    fun allTools(): List<Tool<*, *>> = listOf(
        SaveMemoryTool(), SearchMemoryTool(), DeleteMemoryTool(), ListMemoriesTool()
    )

    // ── Tool Argument Data Classes (Defined nested in MemoryTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class SaveMemoryArgs(
        val content: String,             // The fact to remember
        val tags: List<String> = emptyList(),  // e.g. ["contact", "john", "mpesa"]
    )

    @Serializable
    data class SearchMemoryArgs(
        val query: String,
        val limit: Int = 5,
    )

    @Serializable
    data class DeleteMemoryArgs(val id: String)

    // ── Tool 1: Save Memory ──────────────────────────────────────────────────

    inner class SaveMemoryTool : Tool<SaveMemoryArgs, MemorySaveResult>(
        argsType = typeToken<SaveMemoryArgs>(),
        resultType = typeToken<MemorySaveResult>(),
        name = "memory_save",
        description = "Save a fact or piece of information to long-term memory. Use tags to categorise."
    ) {
        override suspend fun execute(args: SaveMemoryArgs): MemorySaveResult {
            val id = java.util.UUID.randomUUID().toString()
            val record = MemoryRecord(
                id        = id,
                content   = args.content,
                metadata  = mapOf(
                    "tags" to args.tags,
                    "createdAt" to System.currentTimeMillis(),
                    "updatedAt" to System.currentTimeMillis(),
                ),
            )
            store?.save(record)
            return MemorySaveResult(id = id, status = "saved")
        }
    }

    // ── Tool 2: Search Memory ────────────────────────────────────────────────

    inner class SearchMemoryTool : Tool<SearchMemoryArgs, MemorySearchResult>(
        argsType = typeToken<SearchMemoryArgs>(),
        resultType = typeToken<MemorySearchResult>(),
        name = "memory_search",
        description = "Search long-term memory for facts matching a query. Returns most relevant records."
    ) {
        override suspend fun execute(args: SearchMemoryArgs): MemorySearchResult {
            val results = store?.search(args.query, args.limit) ?: emptyList()
            return MemorySearchResult(
                records = results.map {
                    val tagsList = (it.metadata["tags"] as? List<*>)?.mapNotNull { t -> t as? String } ?: emptyList()
                    val created = (it.metadata["createdAt"] as? Number)?.toLong() ?: 0L
                    MemoryEntry(it.id ?: "", it.content, tagsList, created)
                }
            )
        }
    }

    // ── Tool 3: Delete Memory ────────────────────────────────────────────────

    inner class DeleteMemoryTool : Tool<DeleteMemoryArgs, MemoryDeleteResult>(
        argsType = typeToken<DeleteMemoryArgs>(),
        resultType = typeToken<MemoryDeleteResult>(),
        name = "memory_delete",
        description = "Delete a specific memory record by ID. Use when a fact is outdated or incorrect."
    ) {
        override suspend fun execute(args: DeleteMemoryArgs): MemoryDeleteResult {
            store?.delete(args.id)
            return MemoryDeleteResult(id = args.id, deleted = true)
        }
    }

    // ── Tool 4: List Memories ────────────────────────────────────────────────

    inner class ListMemoriesTool : Tool<Unit, MemorySearchResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<MemorySearchResult>(),
        name = "memory_list_all",
        description = "List all facts currently stored in long-term memory"
    ) {
        override suspend fun execute(args: Unit): MemorySearchResult {
            val all = store?.listAll() ?: emptyList()
            return MemorySearchResult(
                records = all.map {
                    val tagsList = (it.metadata["tags"] as? List<*>)?.mapNotNull { t -> t as? String } ?: emptyList()
                    val created = (it.metadata["createdAt"] as? Number)?.toLong() ?: 0L
                    MemoryEntry(it.id ?: "", it.content, tagsList, created)
                }
            )
        }
    }
}
