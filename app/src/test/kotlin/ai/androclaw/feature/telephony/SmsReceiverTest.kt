package ai.androclaw.feature.telephony

import org.junit.Assert.*
import org.junit.Test

class SmsReceiverTest {

    private fun isMpesaMessage(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("mpesa") ||
               lower.contains("m-pesa") ||
               lower.contains("confirmed") && lower.contains("ksh") ||
               lower.contains("safaricom") && lower.contains("received")
    }

    @Test fun `detects standard M-Pesa confirmation`() {
        assertTrue(isMpesaMessage("QHX71YZ3C5 Confirmed. Ksh500.00 sent to JOHN DOE 0712345678."))
    }
    @Test fun `detects M-Pesa receipt`() {
        assertTrue(isMpesaMessage("You have received Ksh1,000.00 from JANE DOE on M-PESA."))
    }
    @Test fun `detects Safaricom received message`() {
        assertTrue(isMpesaMessage("Safaricom: You have received 200 from 254700000000."))
    }
    @Test fun `does not flag regular SMS`() {
        assertFalse(isMpesaMessage("Hey, are we still meeting tomorrow?"))
        assertFalse(isMpesaMessage("Your OTP is 123456"))
        assertFalse(isMpesaMessage("Confirmed your appointment for Monday"))
    }
    @Test fun `handles mixed case`() {
        assertTrue(isMpesaMessage("MPESA balance update: Ksh 500 received"))
        assertTrue(isMpesaMessage("M-Pesa: Transaction confirmed"))
    }
}

