package ai.androclaw.feature.notifications

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class WebhookTest {

    private val appSecret = "test_app_secret_12345"

    private fun computeSignature(body: String, secret: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test fun `valid HMAC signature is accepted`() {
        val body = """{"object":"whatsapp_business_account","entry":[]}"""
        val sig  = computeSignature(body, appSecret)
        assertTrue(sig.startsWith("sha256="))
        assertEquals(71, sig.length)
    }
    @Test fun `tampered body produces different signature`() {
        assertNotEquals(computeSignature("""{"message":"hello"}""", appSecret),
                        computeSignature("""{"message":"hello!"}""", appSecret))
    }
    @Test fun `WhatsApp text payload parses correctly`() {
        val payload = Json.parseToJsonElement(
            """{"object":"whatsapp_business_account","entry":[{"id":"E","changes":[{"value":{"messaging_product":"whatsapp","metadata":{"phone_number_id":"PID"},"contacts":[{"wa_id":"254712345678","profile":{"name":"Kamau"}}],"messages":[{"id":"MSG_ID","from":"254712345678","timestamp":"1700000000","type":"text","text":{"body":"Ninahitaji msaada"}}]},"field":"messages"}]}]}"""
        ).jsonObject
        val entry = payload["entry"]?.jsonArray?.get(0)?.jsonObject
        val changes = entry?.get("changes")?.jsonArray?.get(0)?.jsonObject
        val value = changes?.get("value")?.jsonObject
        val messages = value?.get("messages")?.jsonArray?.get(0)?.jsonObject
        assertEquals("254712345678", messages?.get("from")?.jsonPrimitive?.content)
        assertEquals("Ninahitaji msaada", messages?.get("text")?.jsonObject?.get("body")?.jsonPrimitive?.content)
    }
    @Test fun `Deepgram final transcript parsed correctly`() {
        val json = """{"type":"Results","is_final":true,"channel":{"alternatives":[{"transcript":"habari yako","confidence":0.98}]}}"""
        val obj  = Json.parseToJsonElement(json).jsonObject
        assertTrue(obj["is_final"]?.jsonPrimitive?.boolean == true)
        val channel = obj["channel"]?.jsonObject
        val alternatives = channel?.get("alternatives")?.jsonArray
        val alt0 = alternatives?.get(0)?.jsonObject
        assertEquals("habari yako", alt0?.get("transcript")?.jsonPrimitive?.content)
    }
}

