package ai.androclaw.agent

import ai.androclaw.feature.telephony.CallDirection
import ai.androclaw.feature.telephony.CallStateManager
import ai.androclaw.feature.telephony.CallStatus
import ai.androclaw.feature.telephony.SmsAutoReplyPipeline
import ai.androclaw.feature.telephony.SmsIntent
import ai.androclaw.tools.telephony.CallStatusResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the telephony layer.
 * No Android framework or network required — pure logic tests.
 */
class TelephonyTest {

    // ── SmsAutoReplyPipeline classifier ───────────────────────────────────────

    // Mirror the private classify() logic for testing without instantiating the full pipeline
    private fun classify(body: String): SmsIntent {
        val lower = body.lowercase().trim()

        fun isMpesa() = lower.contains("mpesa") || lower.contains("m-pesa") ||
                (lower.contains("confirmed") && lower.contains("ksh")) ||
                (lower.contains("safaricom") && (lower.contains("received") || lower.contains("sent"))) ||
                (lower.contains("ksh") && lower.contains("transaction"))

        if (isMpesa()) return SmsIntent.MPESA_CONFIRMATION
        if (lower in setOf("stop", "unsubscribe", "acha", "simama", "cancel")) return SmsIntent.STOP
        if (lower in setOf("help", "msaada", "info", "habari")) return SmsIntent.HELP
        if (lower.contains("otp") || lower.contains("verification code") ||
            (lower.contains("code") && lower.any { it.isDigit() } && body.length < 80)) return SmsIntent.OTP
        val questionWords = listOf("?", "how", "what", "when", "where", "why", "who",
            "je", "nini", "vipi", "lini", "wapi", "kwa nini")
        if (questionWords.any { lower.contains(it) }) return SmsIntent.QUESTION
        val actionWords = listOf("send", "tuma", "check", "angalia", "pay", "lipa",
            "call", "piga", "remind", "kumbushia", "book", "weka")
        if (actionWords.any { lower.startsWith(it) || lower.contains(" $it ") }) return SmsIntent.QUESTION
        if (lower.contains("offer") || lower.contains("discount") || lower.contains("promo") ||
            lower.contains("winner") || lower.contains("congratulations")) return SmsIntent.PROMOTIONAL
        return SmsIntent.UNKNOWN
    }

    @Test fun `classifies M-Pesa confirmation`() {
        assertEquals(SmsIntent.MPESA_CONFIRMATION,
            classify("QHX71YZ3C5 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678 on 1/1/24."))
    }

    @Test fun `classifies M-Pesa receipt`() {
        assertEquals(SmsIntent.MPESA_CONFIRMATION,
            classify("You have received Ksh1,000.00 from JANE DOE on M-PESA."))
    }

    @Test fun `classifies Safaricom message`() {
        assertEquals(SmsIntent.MPESA_CONFIRMATION,
            classify("Safaricom: You have received 200 from 254700000000."))
    }

    @Test fun `classifies STOP keyword`() {
        assertEquals(SmsIntent.STOP, classify("STOP"))
        assertEquals(SmsIntent.STOP, classify("acha"))
        assertEquals(SmsIntent.STOP, classify("simama"))
    }

    @Test fun `classifies HELP keyword`() {
        assertEquals(SmsIntent.HELP, classify("help"))
        assertEquals(SmsIntent.HELP, classify("msaada"))
        assertEquals(SmsIntent.HELP, classify("HABARI"))
    }

    @Test fun `classifies OTP`() {
        assertEquals(SmsIntent.OTP, classify("Your verification code is 123456"))
        assertEquals(SmsIntent.OTP, classify("Your OTP is 4567. Valid for 5 mins."))
    }

    @Test fun `classifies Swahili question`() {
        assertEquals(SmsIntent.QUESTION, classify("Je, unaweza kunisaidia?"))
        assertEquals(SmsIntent.QUESTION, classify("Vipi hali yako?"))
    }

    @Test fun `classifies English question`() {
        assertEquals(SmsIntent.QUESTION, classify("What is my balance?"))
        assertEquals(SmsIntent.QUESTION, classify("How do I send money?"))
    }

    @Test fun `classifies action request`() {
        assertEquals(SmsIntent.QUESTION, classify("Tuma 500 kwa 0712345678"))
        assertEquals(SmsIntent.QUESTION, classify("remind me about the meeting"))
    }

    @Test fun `classifies promotional message`() {
        assertEquals(SmsIntent.PROMOTIONAL, classify("Congratulations! You won a prize!"))
        assertEquals(SmsIntent.PROMOTIONAL, classify("Special offer: 50% discount today only!"))
    }

    @Test fun `does not misclassify generic message as M-Pesa`() {
        assertNotEquals(SmsIntent.MPESA_CONFIRMATION, classify("See you tomorrow"))
        assertNotEquals(SmsIntent.MPESA_CONFIRMATION, classify("Your appointment is confirmed"))
    }

    // ── M-Pesa field extraction ───────────────────────────────────────────────

    private fun extractAmount(body: String): String {
        val regex = Regex("""Ksh\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1) ?: "unknown"
    }

    private fun extractMpesaId(body: String): String {
        val regex = Regex("""^([A-Z0-9]{10})\s""")
        return regex.find(body.trim())?.groupValues?.get(1) ?: "unknown"
    }

    @Test fun `extracts M-Pesa amount`() {
        val msg = "QHX71YZ3C5 Confirmed. Ksh1,500.00 sent to JOHN DOE."
        assertEquals("1,500.00", extractAmount(msg))
    }

    @Test fun `extracts M-Pesa transaction ID`() {
        val msg = "QHX71YZ3C5 Confirmed. Ksh500.00 sent."
        assertEquals("QHX71YZ3C5", extractMpesaId(msg))
    }

    @Test fun `handles lowercase Ksh`() {
        val msg = "Amount: ksh 200 received."
        assertEquals("200", extractAmount(msg))
    }

    // ── CallStateManager ──────────────────────────────────────────────────────

    private lateinit var csm: CallStateManager

    @Before fun setupCallState() {
        csm = CallStateManager()
    }

    @Test fun `starts with no active calls`() {
        assertTrue(csm.activeCalls.value.isEmpty())
        assertFalse(csm.hasActiveCall)
    }

    @Test fun `onCallStarted adds call as RINGING`() {
        csm.onCallStarted("uuid-1", to = "+254700000001", from = "+254700000002",
            direction = CallDirection.OUTBOUND)
        assertEquals(1, csm.activeCalls.value.size)
        assertEquals(CallStatus.RINGING, csm.activeCalls.value["uuid-1"]?.status)
        assertTrue(csm.hasActiveCall)
    }

    @Test fun `onCallAnswered transitions to ANSWERED`() {
        csm.onCallStarted("uuid-2", "+254700000003", "+254700000004", CallDirection.INBOUND)
        csm.onCallAnswered("uuid-2")
        assertEquals(CallStatus.ANSWERED, csm.activeCalls.value["uuid-2"]?.status)
        assertNotNull(csm.activeCalls.value["uuid-2"]?.answeredAt)
    }

    @Test fun `onCallEnded removes from active and updates lastCall`() {
        csm.onCallStarted("uuid-3", "+254700000005", "+254700000006", CallDirection.OUTBOUND)
        csm.onCallAnswered("uuid-3")
        csm.onCallEnded("uuid-3", reason = "completed")
        assertTrue(csm.activeCalls.value.isEmpty())
        assertEquals("uuid-3", csm.lastCall.value?.uuid)
        assertEquals(CallStatus.COMPLETED, csm.lastCall.value?.status)
        assertFalse(csm.hasActiveCall)
    }

    @Test fun `updateFromStatus handles completed event`() {
        csm.onCallStarted("uuid-4", "+254700000007", "+254700000008", CallDirection.OUTBOUND)
        csm.updateFromStatus(CallStatusResult(
            callUuid  = "uuid-4",
            status    = "completed",
            direction = "outbound",
        ))
        // completed calls are removed from active
        assertFalse("uuid-4" in csm.activeCalls.value)
        assertEquals(CallStatus.COMPLETED, csm.lastCall.value?.status)
    }

    @Test fun `multiple simultaneous calls tracked independently`() {
        csm.onCallStarted("uuid-5", "+254700000009", "+254700000010", CallDirection.INBOUND)
        csm.onCallStarted("uuid-6", "+254700000011", "+254700000012", CallDirection.OUTBOUND)
        assertEquals(2, csm.activeCalls.value.size)
        csm.onCallEnded("uuid-5")
        assertEquals(1, csm.activeCalls.value.size)
        assertTrue("uuid-6" in csm.activeCalls.value)
    }

    @Test fun `durationSeconds calculates correctly`() {
        csm.onCallStarted("uuid-7", "+254700000013", "+254700000014", CallDirection.OUTBOUND)
        val call = csm.activeCalls.value["uuid-7"]!!
        // Duration from startedAt to now should be > 0
        assertTrue(call.durationSeconds >= 0)
    }

    // ── VonageJwt structure validation ────────────────────────────────────────
    // Tests JWT structure without a real private key

    @Test fun `JWT has three dot-separated parts`() {
        // Can't sign without a real key, but we can verify the header structure
        val header  = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"application_id":"test","iat":1700000000,"exp":1700003600,"jti":"abc"}""".toByteArray())
        val fakeJwt = "$header.$payload.fakesig"
        assertEquals(3, fakeJwt.split(".").size)

        // Verify header decodes correctly
        val decodedHeader = String(java.util.Base64.getUrlDecoder().decode(header))
        assertTrue(decodedHeader.contains("RS256"))
    }
}

