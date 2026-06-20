package ai.androclaw.tools.telephony

import ai.koog.agents.core.tools.Tool
import ai.koog.serialization.typeToken
import ai.androclaw.agent.OpenClawConfig
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class SmsMessage(
    val from: String,
    val text: String,
    val timestamp: String,
    val messageId: String = "",
    val isRead: Boolean = false,
    val isMpesa: Boolean = false,
)

@Serializable
data class SmsSendResult(
    val status: String,
    val messageId: String = ""
)

@Serializable
data class CallStatusResult(
    val callUuid: String,
    val status: String,
    val to: String = "",
    val from: String = "",
    val direction: String = "",
)

@Serializable
data class ReadSmsResult(
    val messages: List<SmsMessage>
)

class TelephonyTools(private val config: OpenClawConfig) {

    init {
        companionConfig = config
    }

    var smsProvider: (suspend (Int, String) -> List<SmsMessage>)?
        get() = companionSmsProvider
        set(value) {
            companionSmsProvider = value
        }

    fun allTools(): List<Tool<*, *>> = listOf(
        SendSmsTool(),
        MakeCallTool(),
        ReadSmsTool(),
    )

    companion object {
        private var companionConfig: OpenClawConfig? = null
        val config: OpenClawConfig get() = companionConfig ?: error("TelephonyTools not initialized")
        private val client = HttpClient()
        private val json   = Json { ignoreUnknownKeys = true }
        var companionSmsProvider: (suspend (Int, String) -> List<SmsMessage>)? = null
    }

    // ── Tool 1: Send SMS ──────────────────────────────────────────────────────

    class SendSmsTool : Tool<SendSmsTool.Args, SmsSendResult>(
        argsType = typeToken<SendSmsTool.Args>(),
        resultType = typeToken<SmsSendResult>(),
        name = "telephony_send_sms",
        description = "Send an SMS message to a recipient phone number"
    ) {
        @Serializable
        data class Args(
            val to: String,
            val text: String,
        )

        override suspend fun execute(args: Args): SmsSendResult {
            val cfg = config
            if (cfg.agentPhoneApiKey.isNotBlank()) {
                try {
                    val resp = client.post("https://api.agentphone.ai/v1/messages") {
                        header(HttpHeaders.Authorization, "Bearer ${cfg.agentPhoneApiKey}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("to", args.to)
                            put("message", args.text)
                        }.toString())
                    }
                    if (resp.status.isSuccess()) {
                        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                        val id = body["id"]?.jsonPrimitive?.content ?: "unknown"
                        return SmsSendResult("sent", id)
                    }
                } catch (e: Exception) {
                    // Log error and fallback
                }
            }
            return SmsSendResult("failed")
        }
    }

    // ── Tool 2: Make Call ─────────────────────────────────────────────────────

    class MakeCallTool : Tool<MakeCallTool.Args, CallStatusResult>(
        argsType = typeToken<MakeCallTool.Args>(),
        resultType = typeToken<CallStatusResult>(),
        name = "telephony_make_call",
        description = "Initiate an outbound call to a phone number"
    ) {
        @Serializable
        data class Args(
            val to: String,
            val agentId: String? = null,
        )

        override suspend fun execute(args: Args): CallStatusResult {
            val cfg = config
            if (cfg.agentPhoneApiKey.isNotBlank()) {
                try {
                    val resp = client.post("https://api.agentphone.ai/v1/calls") {
                        header(HttpHeaders.Authorization, "Bearer ${cfg.agentPhoneApiKey}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject {
                            put("to", args.to)
                            if (args.agentId != null) {
                                put("agentId", args.agentId)
                            }
                        }.toString())
                    }
                    if (resp.status.isSuccess()) {
                        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                        val id = body["id"]?.jsonPrimitive?.content ?: ""
                        val status = body["status"]?.jsonPrimitive?.content ?: "started"
                        return CallStatusResult(callUuid = id, status = status, to = args.to, direction = "outbound")
                    }
                } catch (e: Exception) {
                    // fallback
                }
            }
            return CallStatusResult(callUuid = "unknown", status = "failed", to = args.to, direction = "outbound")
        }
    }

    // ── Tool 3: Read SMS ──────────────────────────────────────────────────────

    class ReadSmsTool : Tool<ReadSmsTool.Args, ReadSmsResult>(
        argsType = typeToken<ReadSmsTool.Args>(),
        resultType = typeToken<ReadSmsResult>(),
        name = "telephony_read_sms",
        description = "Read recent incoming SMS messages"
    ) {
        @Serializable
        data class Args(
            val limit: Int,
            val fromNumber: String = "",
        )

        override suspend fun execute(args: Args): ReadSmsResult {
            val provider = companionSmsProvider
            val msgs = if (provider != null) {
                provider(args.limit, args.fromNumber)
            } else {
                emptyList()
            }
            return ReadSmsResult(msgs)
        }
    }

    // ── GetCallStatusTool (Utility tool instantiated by CallEventPoller) ────────

    class GetCallStatusTool {
        @Serializable
        data class Args(
            val callUuid: String,
        )

        suspend fun execute(args: Args): CallStatusResult {
            val cfg = config
            if (cfg.agentPhoneApiKey.isNotBlank()) {
                try {
                    val resp = client.get("https://api.agentphone.ai/v1/calls/${args.callUuid}") {
                        header(HttpHeaders.Authorization, "Bearer ${cfg.agentPhoneApiKey}")
                    }
                    if (resp.status.isSuccess()) {
                        val body = json.parseToJsonElement(resp.bodyAsText()).jsonObject
                        val id = body["id"]?.jsonPrimitive?.content ?: args.callUuid
                        val status = body["status"]?.jsonPrimitive?.content ?: "unknown"
                        val to = body["to"]?.jsonPrimitive?.content ?: ""
                        val from = body["from"]?.jsonPrimitive?.content ?: ""
                        val direction = body["direction"]?.jsonPrimitive?.content ?: "outbound"
                        return CallStatusResult(callUuid = id, status = status, to = to, from = from, direction = direction)
                    }
                } catch (e: Exception) {
                    // fallback
                }
            }
            return CallStatusResult(callUuid = args.callUuid, status = "unknown")
        }
    }
}

fun TelephonyTools.SendSmsTool() = TelephonyTools.SendSmsTool()
fun TelephonyTools.MakeCallTool() = TelephonyTools.MakeCallTool()
fun TelephonyTools.ReadSmsTool() = TelephonyTools.ReadSmsTool()
fun TelephonyTools.GetCallStatusTool() = TelephonyTools.GetCallStatusTool()
