package ai.androclaw.feature.telephony

import ai.androclaw.data.db.MessageEntity
import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.core.AgentService
import ai.androclaw.tools.telephony.SmsMessage
import ai.androclaw.tools.telephony.TelephonyTools
import ai.androclaw.tools.telephony.SendSmsTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Processes inbound SMS through a three-tier pipeline:
 *
 *  Tier 1 — Instant keyword replies (no AI, <100ms)
 *            M-Pesa confirmations, STOP, HELP
 *
 *  Tier 2 — Agent processing (AI, ~2-5s)
 *            Anything that looks like a question or task for the agent
 *
 *  Tier 3 — Silent store (no reply)
 *            Notifications, OTPs, promotional messages
 *
 * Called from SmsReceiver on the IO dispatcher.
 */
class SmsAutoReplyPipeline(
    private val db: OpenClawDatabase,
    private val agentService: AgentService,
    private val telephonyTools: TelephonyTools,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun process(from: String, body: String, timestamp: Long) {
        scope.launch {
            Timber.d("SmsAutoReply: from=$from body=${body.take(60)}")
            val intent = classify(body)
            Timber.d("SmsAutoReply: intent=$intent")

            when (intent) {
                SmsIntent.MPESA_CONFIRMATION -> handleMpesa(from, body, timestamp)
                SmsIntent.STOP              -> handleStop(from)
                SmsIntent.HELP              -> handleHelp(from)
                SmsIntent.QUESTION          -> handleAgentQuery(from, body, timestamp)
                SmsIntent.OTP               -> storeOnly(from, body, timestamp, tag = "otp")
                SmsIntent.PROMOTIONAL       -> storeOnly(from, body, timestamp, tag = "promo")
                SmsIntent.UNKNOWN           -> storeOnly(from, body, timestamp, tag = "inbox")
            }
        }
    }

    // ── Classification ────────────────────────────────────────────────────────

    private fun classify(body: String): SmsIntent {
        val lower = body.lowercase().trim()

        // M-Pesa patterns
        if (isMpesa(lower)) return SmsIntent.MPESA_CONFIRMATION

        // Control keywords
        if (lower in setOf("stop", "unsubscribe", "acha", "simama", "cancel"))
            return SmsIntent.STOP
        if (lower in setOf("help", "msaada", "info", "habari"))
            return SmsIntent.HELP

        // OTP patterns
        if (lower.contains("otp") ||
            lower.contains("verification code") ||
            lower.contains("nambari ya uthibitisho") ||
            (lower.contains("code") && lower.any { it.isDigit() } && body.length < 80))
            return SmsIntent.OTP

        // Question patterns — likely wants agent help
        val questionWords = listOf("?", "how", "what", "when", "where", "why", "who",
                                   "je", "nini", "vipi", "lini", "wapi", "kwa nini")
        if (questionWords.any { lower.contains(it) }) return SmsIntent.QUESTION

        // Action requests
        val actionWords = listOf("send", "tuma", "check", "angalia", "pay", "lipa",
                                 "call", "piga", "remind", "kumbushia", "book", "weka")
        if (actionWords.any { lower.startsWith(it) || lower.contains(" $it ") })
            return SmsIntent.QUESTION

        // Promotional signals
        if (lower.contains("offer") || lower.contains("discount") ||
            lower.contains("free") || lower.contains("winner") ||
            lower.contains("congratulations") || lower.contains("promo"))
            return SmsIntent.PROMOTIONAL

        return SmsIntent.UNKNOWN
    }

    private fun isMpesa(lower: String): Boolean =
        lower.contains("mpesa") ||
        lower.contains("m-pesa") ||
        (lower.contains("confirmed") && lower.contains("ksh")) ||
        (lower.contains("safaricom") && (lower.contains("received") || lower.contains("sent"))) ||
        (lower.contains("ksh") && lower.contains("transaction"))

    // ── Handlers ──────────────────────────────────────────────────────────────

    private suspend fun handleMpesa(from: String, body: String, timestamp: Long) {
        // Parse key fields from the M-Pesa confirmation
        val amount      = extractAmount(body)
        val txnId       = extractMpesaId(body)
        val counterpart = extractCounterpart(body)

        // Store with mpesa tag so agent can find it later
        storeOnly(from, body, timestamp, tag = "mpesa")

        // Notify the agent session (injected as a system message)
        db.messageDao().insert(MessageEntity(
            id        = UUID.randomUUID().toString(),
            role      = "system",
            text      = "[MPESA_CONFIRMATION] from=$from txn=$txnId amount=$amount party=$counterpart body=$body",
            timestamp = timestamp,
            sessionId = "mpesa_inbox",
        ))

        Timber.d("M-Pesa confirmation stored: txn=$txnId amount=$amount")
        // No SMS reply for M-Pesa confirmations — they come from Safaricom short codes
    }

    private suspend fun handleStop(from: String) {
        sendReply(from, "Umefutwa kwenye OpenClaw. Tuma 'START' ili ujiunge tena.")
        storeOnly(from, "STOP", System.currentTimeMillis(), tag = "opt_out")
    }

    private suspend fun handleHelp(from: String) {
        sendReply(
            from,
            "OpenClaw AI Assistant. " +
            "Unaweza kuniuliza maswali, tuma pesa, au panga miadi. " +
            "Tuma 'STOP' kusimama."
        )
    }

    private suspend fun handleAgentQuery(from: String, body: String, timestamp: Long) {
        storeOnly(from, body, timestamp, tag = "inbox")

        // Forward to agent as a user message from phone
        val prompt = "SMS kutoka $from: $body"
        agentService.send(prompt)

        // The agent response will come through AgentService.responses flow;
        // ChatViewModel picks it up. For autonomous SMS reply, collect the
        // response and call sendReply() — enable in Settings → Auto-reply.
        Timber.d("Forwarded SMS query to agent: ${body.take(60)}")
    }

    private fun storeOnly(from: String, body: String, timestamp: Long, tag: String) {
        scope.launch {
            db.messageDao().insert(MessageEntity(
                id        = UUID.randomUUID().toString(),
                role      = "user",
                text      = "[$tag] SMS from $from: $body",
                timestamp = timestamp,
                sessionId = "sms_$tag",
            ))
        }
    }

    private suspend fun sendReply(to: String, text: String) {
        try {
            TelephonyTools.SendSmsTool().execute(
                TelephonyTools.SendSmsTool.Args(to = to, text = text)
            )
            Timber.d("Auto-replied to $to")
        } catch (e: Exception) {
            Timber.e(e, "Auto-reply failed to $to")
        }
    }

    // ── Extractors ────────────────────────────────────────────────────────────

    private fun extractAmount(body: String): String {
        val regex = Regex("""Ksh\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1) ?: "unknown"
    }

    private fun extractMpesaId(body: String): String {
        // M-Pesa transaction IDs are 10-char alphanumeric codes at the start of confirmations
        val regex = Regex("""^([A-Z0-9]{10})\s""")
        return regex.find(body.trim())?.groupValues?.get(1) ?: "unknown"
    }

    private fun extractCounterpart(body: String): String {
        // "sent to NAME NUMBER" or "received from NAME NUMBER"
        val toRegex   = Regex("""(?:sent to|received from)\s([A-Z\s]+)\s(\d+)""", RegexOption.IGNORE_CASE)
        val match     = toRegex.find(body)
        return match?.groupValues?.get(1)?.trim() ?: "unknown"
    }
}

enum class SmsIntent {
    MPESA_CONFIRMATION,
    STOP,
    HELP,
    QUESTION,
    OTP,
    PROMOTIONAL,
    UNKNOWN,
}

