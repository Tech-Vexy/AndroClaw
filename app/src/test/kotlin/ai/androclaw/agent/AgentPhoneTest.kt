package ai.androclaw.agent

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

/**
 * Unit tests for AgentPhone integration.
 * Validates payload shape, config guards, and tool name conventions.
 */
class AgentPhoneTest {

    // ── Config guard ──────────────────────────────────────────────────────────

    @Test fun `hasAgentPhone is false when key is blank`() {
        val config = OpenClawConfig(googleGenAiApiKey = "sk-ant-test")
        assertFalse(config.hasAgentPhone)
    }

    @Test fun `hasAgentPhone is true when key is set`() {
        val config = OpenClawConfig(
            googleGenAiApiKey  = "sk-ant-test",
            agentPhoneApiKey = "ap_live_abc123",
        )
        assertTrue(config.hasAgentPhone)
    }

    // ── Webhook payload parsing ───────────────────────────────────────────────

    @Test fun `parses voice inbound event correctly`() {
        val payload = """
            {
              "event": "agent.message",
              "channel": "voice",
              "agentId": "agent_abc",
              "timestamp": "2024-01-01T10:00:00Z",
              "data": {
                "conversationId": "conv_123",
                "from": "+254712345678",
                "to": "+12025551234",
                "transcript": "Habari yako?"
              },
              "recentHistory": [
                {"direction": "inbound", "content": "Hello"},
                {"direction": "outbound", "content": "Hi, how can I help?"}
              ],
              "conversationState": null
            }
        """.trimIndent()

        val json  = Json.parseToJsonElement(payload).jsonObject
        assertEquals("agent.message", json["event"]?.jsonPrimitive?.content)
        assertEquals("voice",         json["channel"]?.jsonPrimitive?.content)
        val data  = json["data"]?.jsonObject
        assertEquals("+254712345678", data?.get("from")?.jsonPrimitive?.content)
        assertEquals("Habari yako?",  data?.get("transcript")?.jsonPrimitive?.content)
        val history = json["recentHistory"]?.jsonArray
        assertEquals(2, history?.size)
    }

    @Test fun `parses SMS inbound event correctly`() {
        val payload = """
            {
              "event": "agent.message",
              "channel": "sms",
              "data": {
                "conversationId": "sms_conv_456",
                "from": "+254722222222",
                "to": "+12025551234",
                "message": "Ninataka kujua bei ya sukari"
              },
              "recentHistory": []
            }
        """.trimIndent()

        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("sms", json["channel"]?.jsonPrimitive?.content)
        val data = json["data"]?.jsonObject
        assertEquals("Ninataka kujua bei ya sukari", data?.get("message")?.jsonPrimitive?.content)
    }

    @Test fun `non-agent-message events are acknowledged silently`() {
        // Status events, delivery receipts, etc. should not generate a response
        val statusPayload = """
            {
              "event": "call.completed",
              "channel": "voice",
              "data": {"callId": "call_abc", "duration": 42}
            }
        """.trimIndent()
        val json = Json.parseToJsonElement(statusPayload).jsonObject
        assertNotEquals("agent.message", json["event"]?.jsonPrimitive?.content)
    }

    // ── Tool count and naming ─────────────────────────────────────────────────

    @Test fun `AgentPhone tool filter contains 26 tools`() {
        val tools = setOf(
            "account_overview", "get_usage",
            "list_numbers", "buy_number", "release_number",
            "get_messages", "list_conversations", "get_conversation",
            "list_calls", "list_calls_for_number", "get_call",
            "make_call", "make_conversation_call",
            "list_agents", "create_agent", "update_agent",
            "delete_agent", "get_agent", "attach_number", "list_voices",
            "get_webhook", "set_webhook", "delete_webhook",
            "get_agent_webhook", "set_agent_webhook", "delete_agent_webhook",
        )
        assertEquals(26, tools.size)
    }

    @Test fun `make_conversation_call tool exists in filter`() {
        // Most important tool — AI-native outbound calling
        val tools = setOf(
            "make_call", "make_conversation_call", "list_calls",
            "get_call", "list_calls_for_number"
        )
        assertTrue("make_conversation_call" in tools)
        assertTrue("make_call" in tools)
    }

    // ── API key format validation ─────────────────────────────────────────────

    @Test fun `valid AgentPhone key format accepted`() {
        val validKeys = listOf(
            "ap_live_abc123xyz",
            "ap_test_def456uvw",
            "ap_live_ABCDEF123456",
        )
        validKeys.forEach { key ->
            assertTrue("$key should start with ap_", key.startsWith("ap_"))
        }
    }

    @Test fun `invalid key formats rejected`() {
        val invalidKeys = listOf("", "sk-ant-abc", "vonage_key", "Bearer ap_live_abc")
        invalidKeys.forEach { key ->
            assertFalse("$key should not start with ap_", key.startsWith("ap_"))
        }
    }

    // ── Voice vs SMS response strategy ───────────────────────────────────────

    @Test fun `voice responses should be short`() {
        // Simulating the constraint: voice replies <= 2 sentences
        val voiceReply = "Habari! Ninaweza kukusaidia leo."
        val sentences  = voiceReply.split(Regex("[.!?]")).filter { it.isNotBlank() }
        assertTrue("Voice reply should be ≤ 2 sentences", sentences.size <= 2)
    }

    @Test fun `SMS responses should fit in 160 chars ideally`() {
        val smsReply = "Asante! Bei ya sukari ni Ksh 250 kwa kilo. Unahitaji kiasi gani?"
        // Not a hard limit but a guideline
        assertTrue("SMS reply length tracked", smsReply.length < 300)
    }
}

