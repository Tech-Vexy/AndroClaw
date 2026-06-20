package ai.androclaw.tools.messaging

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import ai.koog.serialization.TypeToken
import ai.androclaw.agent.OpenClawConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class TgSendResult(val messageId: Long, val chatId: String, val status: String)
@Serializable data class TgMessage(val chatId: String, val from: String, val text: String, val date: Long)
@Serializable data class TgMessagesResult(val messages: List<TgMessage>)
@Serializable data class TgChatInfo(val id: String, val title: String, val type: String, val unreadCount: Int)
@Serializable data class TgChatsResult(val chats: List<TgChatInfo>)

class TelegramTools(private val config: OpenClawConfig) {

    private val client = HttpClient {
        install(io.ktor.client.plugins.DefaultRequest) {
            header("X-OpenClaw-Secret", config.bridgeSecret)
        }
    }
    // The MTProto bridge runs on the unified Render gateway (Python Telethon → HTTP API)
    // It exposes a REST interface wrapping the full Telegram user account.
    private val bridgeBase get() = "${config.gatewayBaseUrl}/tg"

    fun allTools(): List<Tool<*, *>> = listOf(
        SendMessageTool(), ReadMessagesTool(), ListChatsTool(), ForwardMessageTool()
    )

    // ── Tool Argument Data Classes (Defined nested in TelegramTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class SendMessageArgs(
        val chatId: String,     // Username (@handle), phone, or numeric chat ID
        val text: String,
        val parseMode: String = "markdown",     // "markdown" or "html"
    )

    @Serializable
    data class ReadMessagesArgs(
        val chatId: String,
        val limit: Int = 20,
        val offsetDate: Long = 0L,   // Unix timestamp — messages before this date
    )

    @Serializable
    data class ListChatsArgs(
        val limit: Int = 20,
        val unreadOnly: Boolean = false,
    )

    @Serializable
    data class ForwardMessageArgs(
        val fromChatId: String,
        val toChatId: String,
        val messageId: Long,
    )

    // ── Tool 1: Send message ──────────────────────────────────────────────

    inner class SendMessageTool : Tool<SendMessageArgs, TgSendResult>(
        argsType = typeToken<SendMessageArgs>(),
        resultType = typeToken<TgSendResult>(),
        name = "telegram_send_message",
        description = "Send a Telegram message to a chat, group, or user. Supports Markdown formatting."
    ) {
        override suspend fun execute(args: SendMessageArgs): TgSendResult {
            val resp = client.post("$bridgeBase/send") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "chat_id":    "${args.chatId}",
                      "text":       "${args.text}",
                      "parse_mode": "${args.parseMode}"
                    }
                """.trimIndent())
            }
            val body = resp.bodyAsText()
            val messageId = """"message_id"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return TgSendResult(messageId = messageId, chatId = args.chatId, status = "sent")
        }
    }

    // ── Tool 2: Read messages from a chat ─────────────────────────────────

    inner class ReadMessagesTool : Tool<ReadMessagesArgs, TgMessagesResult>(
        argsType = typeToken<ReadMessagesArgs>(),
        resultType = typeToken<TgMessagesResult>(),
        name = "telegram_read_messages",
        description = "Read recent messages from a Telegram chat or DM by chat ID or username"
    ) {
        override suspend fun execute(args: ReadMessagesArgs): TgMessagesResult {
            val resp = client.get("$bridgeBase/messages") {
                parameter("chat_id", args.chatId)
                parameter("limit", args.limit)
                if (args.offsetDate > 0) parameter("offset_date", args.offsetDate)
            }
            return try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                val messages = root["messages"]?.jsonArray?.map { el ->
                    val obj = el.jsonObject
                    TgMessage(
                        chatId    = args.chatId,
                        from      = obj["from"]?.jsonPrimitive?.content ?: "unknown",
                        text      = obj["text"]?.jsonPrimitive?.content ?: "",
                        date      = obj["date"]?.jsonPrimitive?.long
                                    ?: (System.currentTimeMillis() / 1000),
                    )
                } ?: emptyList()
                TgMessagesResult(messages = messages)
            } catch (e: Exception) {
                TgMessagesResult(messages = emptyList())
            }
        }
    }

    // ── Tool 3: List chats / dialogs ──────────────────────────────────────

    inner class ListChatsTool : Tool<ListChatsArgs, TgChatsResult>(
        argsType = typeToken<ListChatsArgs>(),
        resultType = typeToken<TgChatsResult>(),
        name = "telegram_list_chats",
        description = "List recent Telegram chats/dialogs, optionally filtered to unread only"
    ) {
        override suspend fun execute(args: ListChatsArgs): TgChatsResult {
            val resp = client.get("$bridgeBase/dialogs") {
                parameter("limit", args.limit)
                parameter("unread_only", args.unreadOnly)
            }
            return try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(resp.bodyAsText())
                    .jsonObject
                val chats = root["chats"]?.jsonArray?.map { el ->
                    val obj = el.jsonObject
                    TgChatInfo(
                        id          = obj["id"]?.jsonPrimitive?.content ?: "",
                        title       = obj["title"]?.jsonPrimitive?.content ?: "",
                        type        = obj["type"]?.jsonPrimitive?.content ?: "user",
                        unreadCount = obj["unread_count"]?.jsonPrimitive?.int ?: 0,
                    )
                } ?: emptyList()
                TgChatsResult(chats = chats)
            } catch (e: Exception) {
                TgChatsResult(chats = emptyList())
            }
        }
    }

    // ── Tool 4: Forward message ───────────────────────────────────────────

    inner class ForwardMessageTool : Tool<ForwardMessageArgs, TgSendResult>(
        argsType = typeToken<ForwardMessageArgs>(),
        resultType = typeToken<TgSendResult>(),
        name = "telegram_forward_message",
        description = "Forward a Telegram message from one chat to another"
    ) {
        override suspend fun execute(args: ForwardMessageArgs): TgSendResult {
            val resp = client.post("$bridgeBase/forward") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "from_chat_id": "${args.fromChatId}",
                      "to_chat_id":   "${args.toChatId}",
                      "message_id":   ${args.messageId}
                    }
                """.trimIndent())
            }
            val body = resp.bodyAsText()
            val newId = """"message_id"\s*:\s*(\d+)""".toRegex().find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            return TgSendResult(messageId = newId, chatId = args.toChatId, status = "forwarded")
        }
    }
}
