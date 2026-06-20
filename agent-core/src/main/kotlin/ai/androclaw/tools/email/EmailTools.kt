package ai.androclaw.tools.email

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


@Serializable data class EmailSendResult(val messageId: String, val status: String, val to: String)
@Serializable data class EmailMessage(
    val id: String,
    val from: String,
    val to: String,
    val subject: String,
    val snippet: String,
    val date: Long,
    val isRead: Boolean,
)
@Serializable data class EmailListResult(val messages: List<EmailMessage>, val totalCount: Int)
@Serializable data class EmailBody(val id: String, val body: String, val attachments: List<String>)

class EmailTools(private val config: OpenClawConfig) {

    private val client = HttpClient {
        install(io.ktor.client.plugins.DefaultRequest) {
            header("X-OpenClaw-Secret", config.bridgeSecret)
        }
    }
    private val gatewayBase get() = "${config.gatewayBaseUrl}/email"

    fun allTools(): List<Tool<*, *>> = listOf(
        ListEmailsTool(), GetEmailBodyTool(), SendEmailTool(), SearchEmailsTool()
    )

    // ── Tool Argument Data Classes (Defined nested in EmailTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class ListEmailsArgs(
        val maxResults: Int = 10,
        val labelIds: List<String> = listOf("INBOX"),
        val unreadOnly: Boolean = false,
    )

    @Serializable
    data class GetEmailBodyArgs(
        val messageId: String,
        val format: String = "plain",  // "plain" or "html"
    )

    @Serializable
    data class SendEmailArgs(
        val to: String,
        val subject: String,
        val body: String,
        val cc: String = "",
        val replyToId: String = "",   // If replying, pass the original message ID
    )

    @Serializable
    data class SearchEmailsArgs(
        val query: String,        // Gmail search syntax: "from:john subject:invoice"
        val maxResults: Int = 10,
    )

    // ── Tool 1: List emails ───────────────────────────────────────────────

    inner class ListEmailsTool : Tool<ListEmailsArgs, EmailListResult>(
        argsType = typeToken<ListEmailsArgs>(),
        resultType = typeToken<EmailListResult>(),
        name = "email_list",
        description = "List recent emails from the inbox. Can filter by label or unread status."
    ) {
        override suspend fun execute(args: ListEmailsArgs): EmailListResult {
            val resp = client.get("$gatewayBase/list") {
                parameter("max_results", args.maxResults)
                parameter("labels", args.labelIds.joinToString(","))
                parameter("unread_only", args.unreadOnly)
            }
            return parseEmailListResponse(resp.bodyAsText())
        }
    }

    // ── Tool 2: Get full email body ───────────────────────────────────────

    inner class GetEmailBodyTool : Tool<GetEmailBodyArgs, EmailBody>(
        argsType = typeToken<GetEmailBodyArgs>(),
        resultType = typeToken<EmailBody>(),
        name = "email_get_body",
        description = "Fetch the full body of an email by its ID. Returns plain text or HTML."
    ) {
        override suspend fun execute(args: GetEmailBodyArgs): EmailBody {
            val resp = client.get("$gatewayBase/message/${args.messageId}") {
                parameter("format", args.format)
            }
            return EmailBody(id = args.messageId, body = resp.bodyAsText(), attachments = emptyList())
        }
    }

    // ── Tool 3: Send email ────────────────────────────────────────────────

    inner class SendEmailTool : Tool<SendEmailArgs, EmailSendResult>(
        argsType = typeToken<SendEmailArgs>(),
        resultType = typeToken<EmailSendResult>(),
        name = "email_send",
        description = "Send an email. If replyToId is provided, sends as a reply in the same thread."
    ) {
        override suspend fun execute(args: SendEmailArgs): EmailSendResult {
            val resp = client.post("$gatewayBase/send") {
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "to":          "${args.to}",
                      "subject":     "${args.subject}",
                      "body":        "${args.body}",
                      "cc":          "${args.cc}",
                      "reply_to_id": "${args.replyToId}"
                    }
                """.trimIndent())
            }
            val id = """"id"\s*:\s*"([^"]+)"""".toRegex().find(resp.bodyAsText())?.groupValues?.get(1) ?: "unknown"
            return EmailSendResult(messageId = id, status = if (resp.status.isSuccess()) "sent" else "failed", to = args.to)
        }
    }

    // ── Tool 4: Search emails ─────────────────────────────────────────────

    inner class SearchEmailsTool : Tool<SearchEmailsArgs, EmailListResult>(
        argsType = typeToken<SearchEmailsArgs>(),
        resultType = typeToken<EmailListResult>(),
        name = "email_search",
        description = "Search emails using Gmail search syntax. E.g. 'from:bank@kcb.co.ke subject:statement'"
    ) {
        override suspend fun execute(args: SearchEmailsArgs): EmailListResult {
            val resp = client.get("$gatewayBase/search") {
                parameter("q", args.query)
                parameter("max_results", args.maxResults)
            }
            return parseEmailListResponse(resp.bodyAsText())
        }
    }
}

// ── Response parser ───────────────────────────────────────────────────────────

private fun parseEmailListResponse(body: String): EmailListResult {
    return try {
        val root     = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .parseToJsonElement(body).jsonObject
        val total    = root["total_count"]?.jsonPrimitive?.int ?: 0
        val messages = root["messages"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            EmailMessage(
                id      = obj["id"]?.jsonPrimitive?.content ?: "",
                from    = obj["from"]?.jsonPrimitive?.content ?: "",
                to      = obj["to"]?.jsonPrimitive?.content ?: "",
                subject = obj["subject"]?.jsonPrimitive?.content ?: "(no subject)",
                snippet = obj["snippet"]?.jsonPrimitive?.content ?: "",
                date    = obj["date"]?.jsonPrimitive?.long ?: 0L,
                isRead  = obj["is_read"]?.jsonPrimitive?.boolean ?: true,
            )
        } ?: emptyList()
        EmailListResult(messages = messages, totalCount = total)
    } catch (e: Exception) {
        EmailListResult(messages = emptyList(), totalCount = 0)
    }
}

private val String.jsonPrimitive get() =
    (kotlinx.serialization.json.Json.parseToJsonElement(this) as? kotlinx.serialization.json.JsonPrimitive)

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as? kotlinx.serialization.json.JsonObject ?: kotlinx.serialization.json.JsonObject(emptyMap())
private val kotlinx.serialization.json.JsonElement.jsonArray
    get() = this as? kotlinx.serialization.json.JsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as? kotlinx.serialization.json.JsonPrimitive
