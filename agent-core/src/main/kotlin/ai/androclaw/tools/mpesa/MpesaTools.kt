package ai.androclaw.tools.mpesa

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import ai.koog.serialization.TypeToken
import ai.androclaw.agent.OpenClawConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

@Serializable data class StkPushResult(val checkoutRequestId: String, val responseDescription: String)
@Serializable data class BalanceResult(val balance: String, val utility: String)
@Serializable data class TransactionStatusResult(val resultCode: String, val resultDesc: String)
@Serializable data class B2CResult(val conversationId: String, val originatorConversationId: String)

class MpesaTools(private val config: OpenClawConfig) {

    private val client = HttpClient()
    private val json   = Json { ignoreUnknownKeys = true }

    // Automatically switch between sandbox and production based on config.mpesaEnv
    private val darajaBase: String
        get() = if (config.mpesaEnv == "production")
            "https://api.safaricom.co.ke"
        else
            "https://sandbox.safaricom.co.ke"

    /** STK push password — Base64(shortcode + passkey + timestamp). Daraja-specific. */
    private fun stkPassword(ts: String): String =
        java.util.Base64.getEncoder().encodeToString(
            "${config.mpesaShortcode}${config.mpesaPasskey}$ts".toByteArray()
        )

    /**
     * SecurityCredential for B2C, balance, status, reversal APIs.
     * Sandbox: Base64 of plain password (Daraja sandbox accepts this).
     * Production: RSA/PKCS1 encrypted with Safaricom's public certificate.
     */
    private fun securityCredential(): String =
        MpesaSecurityCredential.generate(
            initiatorPassword = config.mpesaPasskey,
            env               = config.mpesaEnv,
        )

    fun allTools(): List<Tool<*, *>> = listOf(
        StkPushTool(), BalanceTool(), TransactionStatusTool(), B2CTool()
    )

    private suspend fun accessToken(): String {
        val credentials = Base64.getEncoder().encodeToString(
            "${config.mpesaConsumerKey}:${config.mpesaConsumerSecret}".toByteArray()
        )
        val resp = client.get("$darajaBase/oauth/v1/generate?grant_type=client_credentials") {
            header(HttpHeaders.Authorization, "Basic $credentials")
        }
        val body = resp.bodyAsText()
        val tokenRegex = """"access_token"\s*:\s*"([^"]+)"""".toRegex()
        return tokenRegex.find(body)?.groupValues?.get(1)
            ?: error("Failed to extract M-Pesa access token")
    }

    private fun timestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))

    // ── Tool Argument Data Classes (Defined nested in MpesaTools to be valid Kotlin and avoid name collisions) ──

    @Serializable
    data class StkPushArgs(
        @LLMDescription("Phone number to charge in format 2547XXXXXXXX")
        val phoneNumber: String,
        @LLMDescription("Amount in KES (integer)")
        val amount: Int,
        @LLMDescription("Short description of what the payment is for")
        val description: String,
    )

    @Serializable
    data class TransactionStatusArgs(
        @LLMDescription("M-Pesa transaction ID (e.g. QHX71YZ3C5)")
        val transactionId: String,
    )

    @Serializable
    data class B2CArgs(
        @LLMDescription("Recipient phone number in format 2547XXXXXXXX")
        val phoneNumber: String,
        @LLMDescription("Amount in KES to send")
        val amount: Int,
        @LLMDescription("Occasion or purpose of the payment")
        val occasion: String = "",
    )

    // ── Tool 1: STK Push (Lipa na M-Pesa) ────────────────────────────────

    inner class StkPushTool : Tool<StkPushArgs, StkPushResult>(
        argsType = typeToken<StkPushArgs>(),
        resultType = typeToken<StkPushResult>(),
        name = "mpesa_stk_push",
        description = "Initiate an M-Pesa STK push (Lipa na M-Pesa) payment request to a phone number"
    ) {
        override suspend fun execute(args: StkPushArgs): StkPushResult {
            val token = accessToken()
            val ts    = timestamp()
            val resp  = client.post("$darajaBase/mpesa/stkpush/v1/processrequest") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "BusinessShortCode": "${config.mpesaShortcode}",
                      "Password": "${stkPassword(ts)}",
                      "Timestamp": "$ts",
                      "TransactionType": "CustomerPayBillOnline",
                      "Amount": ${args.amount},
                      "PartyA": "${args.phoneNumber}",
                      "PartyB": "${config.mpesaShortcode}",
                      "PhoneNumber": "${args.phoneNumber}",
                      "CallBackURL": "${config.mpesaCallbackUrl}",
                      "AccountReference": "OpenClaw",
                      "TransactionDesc": "${args.description}"
                    }
                """.trimIndent())
            }
            return json.decodeFromString(resp.bodyAsText())
        }
    }

    // ── Tool 2: Account Balance ───────────────────────────────────────────

    inner class BalanceTool : Tool<Unit, BalanceResult>(
        argsType = typeToken<Unit>(),
        resultType = typeToken<BalanceResult>(),
        name = "mpesa_check_balance",
        description = "Check M-Pesa account balance for the configured shortcode"
    ) {
        override suspend fun execute(args: Unit): BalanceResult {
            val token = accessToken()
            val resp = client.post("$darajaBase/mpesa/accountbalance/v1/query") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "Initiator":          "openclaw",
                      "SecurityCredential": "${securityCredential()}",
                      "CommandID":          "AccountBalance",
                      "PartyA":             "${config.mpesaShortcode}",
                      "IdentifierType":     "4",
                      "Remarks":            "Balance check",
                      "QueueTimeOutURL":    "${config.mpesaCallbackUrl}/timeout",
                      "ResultURL":          "${config.mpesaCallbackUrl}/balance/result"
                    }
                """.trimIndent())
            }
            val body = resp.bodyAsText()
            val convId = """"ConversationID"\s*:\s*"([^"]+)"""".toRegex()
                .find(body)?.groupValues?.get(1) ?: "pending"
            val responseDesc = """"ResponseDescription"\s*:\s*"([^"]+)"""".toRegex()
                .find(body)?.groupValues?.get(1) ?: resp.status.description
            return BalanceResult(
                balance = "Async — poll callback. ConversationID=$convId",
                utility = responseDesc,
            )
        }
    }

    // ── Tool 3: Transaction Status ────────────────────────────────────────

    inner class TransactionStatusTool : Tool<TransactionStatusArgs, TransactionStatusResult>(
        argsType = typeToken<TransactionStatusArgs>(),
        resultType = typeToken<TransactionStatusResult>(),
        name = "mpesa_transaction_status",
        description = "Query the status of an M-Pesa transaction by transaction ID"
    ) {
        override suspend fun execute(args: TransactionStatusArgs): TransactionStatusResult {
            val token = accessToken()
            val ts    = timestamp()
            val resp  = client.post("$darajaBase/mpesa/transactionstatus/v1/query") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "Initiator": "openclaw",
                      "SecurityCredential": "${securityCredential()}",
                      "CommandID": "TransactionStatusQuery",
                      "TransactionID": "${args.transactionId}",
                      "PartyA": "${config.mpesaShortcode}",
                      "IdentifierType": "4",
                      "ResultURL": "${config.mpesaCallbackUrl}/status",
                      "QueueTimeOutURL": "${config.mpesaCallbackUrl}/timeout",
                      "Remarks": "status check",
                      "Occasion": ""
                    }
                """.trimIndent())
            }
            return json.decodeFromString(resp.bodyAsText())
        }
    }

    // ── Tool 4: B2C (Send money to customer) ─────────────────────────────

    inner class B2CTool : Tool<B2CArgs, B2CResult>(
        argsType = typeToken<B2CArgs>(),
        resultType = typeToken<B2CResult>(),
        name = "mpesa_b2c_send",
        description = "Send money from the business account to a customer phone number via M-Pesa B2C"
    ) {
        override suspend fun execute(args: B2CArgs): B2CResult {
            val token = accessToken()
            val ts    = timestamp()
            val resp  = client.post("$darajaBase/mpesa/b2c/v3/paymentrequest") {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody("""
                    {
                      "OriginatorConversationID": "openclaw-${System.currentTimeMillis()}",
                      "InitiatorName": "openclaw",
                      "SecurityCredential": "${securityCredential()}",
                      "CommandID": "BusinessPayment",
                      "Amount": ${args.amount},
                      "PartyA": "${config.mpesaShortcode}",
                      "PartyB": "${args.phoneNumber}",
                      "Remarks": "Sent via OpenClaw",
                      "QueueTimeOutURL": "${config.mpesaCallbackUrl}/timeout",
                      "ResultURL": "${config.mpesaCallbackUrl}/b2c",
                      "Occasion": "${args.occasion}"
                    }
                """.trimIndent())
            }
            return json.decodeFromString(resp.bodyAsText())
        }
    }
}
