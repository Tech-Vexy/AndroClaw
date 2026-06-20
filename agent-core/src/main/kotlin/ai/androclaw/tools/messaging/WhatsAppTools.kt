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
import java.util.Base64

@Serializable data class WaSendResult(val messageId: String, val status: String)
@Serializable data class WaMessage(val from: String, val body: String, val timestamp: Long, val type: String)
@Serializable data class WaMessagesResult(val messages: List<WaMessage>)

class WhatsAppTools(private val config: OpenClawConfig) {

    private val client = HttpClient()
    private val json   = Json { ignoreUnknownKeys = true }

    // Vonage Messages API — sandbox for testing, production for live
    private val baseUrl get() = if (config.vonageMsgSandbox)
        "https://messages-sandbox.nexmo.com/v1/messages"
    else
        "https://api.nexmo.com/v1/messages"

    private val basicAuth get(): String {
        val creds = "${config.vonageMsgApiKey}:${config.vonageMsgApiSecret}"
        return "Basic ${Base64.getEncoder().encodeToString(creds.toByteArray())}"
    }

    fun allTools(): List<Tool<*, *>> = listOf(
        SendTextTool(),
        SendTemplateTool(),
        SendListMessageTool(),
        SendReplyButtonsTool(),
        ReadMessagesTool(),
    )

    // ── Tool Argument Data Classes (Defined nested in WhatsAppTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class SendTextArgs(
        val to: String,       // E.164 without '+', e.g. 254712345678
        val message: String,
    )

    @Serializable
    data class SendTemplateArgs(
        val to: String,
        val templateName: String,
        val languageCode: String = "en_US",
        val parameters: List<String> = emptyList(),
    )

    @Serializable
    data class ListSection(val title: String, val rows: List<ListRow>)

    @Serializable
    data class ListRow(val id: String, val title: String, val description: String = "")

    @Serializable
    data class SendListMessageArgs(
        val to: String,
        val bodyText: String,
        val buttonText: String,          // Label on the button that opens the list
        val sections: List<ListSection>, // Up to 10 rows total across all sections
        val headerText: String = "",
        val footerText: String = "",
    )

    @Serializable
    data class Button(val id: String, val title: String)

    @Serializable
    data class SendReplyButtonsArgs(
        val to: String,
        val bodyText: String,
        val buttons: List<Button>,   // Up to 3 buttons
        val headerText: String = "",
        val footerText: String = "",
    )

    @Serializable
    data class ReadMessagesArgs(
        val limit: Int = 20,
        val fromNumber: String = "",
    )

    // ── Tool 1: Send text message ─────────────────────────────────────────────

    inner class SendTextTool : Tool<SendTextArgs, WaSendResult>(
        argsType = typeToken<SendTextArgs>(),
        resultType = typeToken<WaSendResult>(),
        name = "whatsapp_send_text",
        description = "Send a WhatsApp text message via Vonage Messages API. to: phone number without '+' e.g. 254712345678"
    ) {
        override suspend fun execute(args: SendTextArgs): WaSendResult {
            val resp = client.post(baseUrl) {
                header(HttpHeaders.Authorization, basicAuth)
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from",         config.vonageMsgFromNumber)
                    put("to",           args.to)
                    put("message_type", "text")
                    put("text",         args.message)
                    put("channel",      "whatsapp")
                }.toString())
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val id = body["message_uuid"]?.jsonPrimitive?.content ?: "unknown"
            return WaSendResult(messageId = id, status = if (resp.status.isSuccess()) "sent" else "failed")
        }
    }

    // ── Tool 2: Send template message ────────────────────────────────────────

    inner class SendTemplateTool : Tool<SendTemplateArgs, WaSendResult>(
        argsType = typeToken<SendTemplateArgs>(),
        resultType = typeToken<WaSendResult>(),
        name = "whatsapp_send_template",
        description = "Send a pre-approved WhatsApp template message. Required for first contact or after the 24h window."
    ) {
        override suspend fun execute(args: SendTemplateArgs): WaSendResult {
            val resp = client.post(baseUrl) {
                header(HttpHeaders.Authorization, basicAuth)
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from",         config.vonageMsgFromNumber)
                    put("to",           args.to)
                    put("message_type", "custom")
                    put("channel",      "whatsapp")
                    putJsonObject("custom") {
                        put("type", "template")
                        putJsonObject("template") {
                            put("namespace", "")
                            put("name",      args.templateName)
                            putJsonObject("language") { put("code", args.languageCode) }
                            if (args.parameters.isNotEmpty()) {
                                putJsonArray("components") {
                                    addJsonObject {
                                        put("type", "body")
                                        putJsonArray("parameters") {
                                            args.parameters.forEach { p ->
                                                addJsonObject {
                                                    put("type", "text")
                                                    put("text", p)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.toString())
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val id = body["message_uuid"]?.jsonPrimitive?.content ?: "unknown"
            return WaSendResult(messageId = id, status = if (resp.status.isSuccess()) "sent" else "failed")
        }
    }

    // ── Tool 3: Send interactive list message ─────────────────────────────────

    inner class SendListMessageTool : Tool<SendListMessageArgs, WaSendResult>(
        argsType = typeToken<SendListMessageArgs>(),
        resultType = typeToken<WaSendResult>(),
        name = "whatsapp_send_list_message",
        description = "Send a WhatsApp interactive list message with up to 10 selectable options in a pop-up menu. " +
                      "Each row has an id, title, and optional description. " +
                      "User's selection is returned as a reply with the row id."
    ) {
        override suspend fun execute(args: SendListMessageArgs): WaSendResult {
            val resp = client.post(baseUrl) {
                header(HttpHeaders.Authorization, basicAuth)
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from",         config.vonageMsgFromNumber)
                    put("to",           args.to)
                    put("message_type", "custom")
                    put("channel",      "whatsapp")
                    putJsonObject("custom") {
                        put("type", "interactive")
                        putJsonObject("interactive") {
                            put("type", "list")
                            if (args.headerText.isNotBlank()) {
                                putJsonObject("header") {
                                    put("type", "text")
                                    put("text", args.headerText)
                                }
                            }
                            putJsonObject("body") { put("text", args.bodyText) }
                            if (args.footerText.isNotBlank()) {
                                putJsonObject("footer") { put("text", args.footerText) }
                            }
                            putJsonObject("action") {
                                put("button", args.buttonText)
                                putJsonArray("sections") {
                                    args.sections.forEach { section ->
                                        addJsonObject {
                                            put("title", section.title)
                                            putJsonArray("rows") {
                                                section.rows.forEach { row ->
                                                    addJsonObject {
                                                        put("id",          row.id)
                                                        put("title",       row.title)
                                                        if (row.description.isNotBlank())
                                                            put("description", row.description)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.toString())
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val id = body["message_uuid"]?.jsonPrimitive?.content ?: "unknown"
            return WaSendResult(messageId = id, status = if (resp.status.isSuccess()) "sent" else "failed")
        }
    }

    // ── Tool 4: Send reply buttons ────────────────────────────────────────────

    inner class SendReplyButtonsTool : Tool<SendReplyButtonsArgs, WaSendResult>(
        argsType = typeToken<SendReplyButtonsArgs>(),
        resultType = typeToken<WaSendResult>(),
        name = "whatsapp_send_reply_buttons",
        description = "Send a WhatsApp interactive message with up to 3 reply buttons. " +
                      "Each button has an id and title. User's tap returns the button id as a reply. " +
                      "Use for yes/no confirmations or short option sets."
    ) {
        override suspend fun execute(args: SendReplyButtonsArgs): WaSendResult {
            val resp = client.post(baseUrl) {
                header(HttpHeaders.Authorization, basicAuth)
                header(HttpHeaders.Accept, "application/json")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("from",         config.vonageMsgFromNumber)
                    put("to",           args.to)
                    put("message_type", "custom")
                    put("channel",      "whatsapp")
                    putJsonObject("custom") {
                        put("type", "interactive")
                        putJsonObject("interactive") {
                            put("type", "button")
                            if (args.headerText.isNotBlank()) {
                                putJsonObject("header") {
                                    put("type", "text")
                                    put("text", args.headerText)
                                }
                            }
                            putJsonObject("body") { put("text", args.bodyText) }
                            if (args.footerText.isNotBlank()) {
                                putJsonObject("footer") { put("text", args.footerText) }
                            }
                            putJsonObject("action") {
                                putJsonArray("buttons") {
                                    args.buttons.take(3).forEach { btn ->
                                        addJsonObject {
                                            put("type", "reply")
                                            putJsonObject("reply") {
                                                put("id",    btn.id)
                                                put("title", btn.title)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.toString())
            }
            val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val id = body["message_uuid"]?.jsonPrimitive?.content ?: "unknown"
            return WaSendResult(messageId = id, status = if (resp.status.isSuccess()) "sent" else "failed")
        }
    }

    // ── Tool 5: Read incoming messages (from webhook cache) ───────────────────

    inner class ReadMessagesTool : Tool<ReadMessagesArgs, WaMessagesResult>(
        argsType = typeToken<ReadMessagesArgs>(),
        resultType = typeToken<WaMessagesResult>(),
        name = "whatsapp_read_messages",
        description = "Read recent incoming WhatsApp messages from the local cache"
    ) {
        var messageProvider: (suspend (Int, String) -> List<WaMessage>)? = null

        override suspend fun execute(args: ReadMessagesArgs): WaMessagesResult {
            val provider = messageProvider
                ?: return WaMessagesResult(listOf(WaMessage("system", "Message cache not initialised", 0L, "text")))
            return WaMessagesResult(provider(args.limit, args.fromNumber))
        }
    }
}
