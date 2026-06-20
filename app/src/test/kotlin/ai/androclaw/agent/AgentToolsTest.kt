package ai.androclaw.agent

import ai.androclaw.tools.mpesa.MpesaTools
import ai.androclaw.tools.telephony.TelephonyTools
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.messaging.TelegramTools
import ai.androclaw.tools.memory.MemoryTools
import ai.androclaw.agent.memory.OpenClawMemoryStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for each tool group.
 * Uses the sandbox config — no real money or messages sent.
 *
 * Run with: ./gradlew :agent-core:test
 */
class AgentToolsTest {

    private lateinit var config: OpenClawConfig

    @Before
    fun setup() {
        config = OpenClawConfig(
            googleGenAiApiKey    = "sk-ant-test",
            vonageMsgApiKey      = System.getenv("VONAGE_MSG_API_KEY")     ?: "test_key",
            vonageMsgApiSecret   = System.getenv("VONAGE_MSG_API_SECRET")  ?: "test_secret",
            vonageMsgFromNumber  = System.getenv("VONAGE_MSG_FROM_NUMBER") ?: "+254700000000",
            mpesaConsumerKey     = System.getenv("MPESA_CONSUMER_KEY")     ?: "test_key",
            mpesaConsumerSecret  = System.getenv("MPESA_CONSUMER_SECRET")  ?: "test_secret",
            mpesaShortcode       = System.getenv("MPESA_SHORTCODE")        ?: "174379",
            mpesaPasskey         = System.getenv("MPESA_PASSKEY")          ?: "test_passkey",
            mpesaCallbackUrl     = "https://example.com/mpesa/callback",
            gatewayBaseUrl       = System.getenv("GATEWAY_BASE_URL")       ?: "https://test-gateway.example.com",
        )
    }

    // ── Tool registration ──────────────────────────────────────────────────────

    @Test
    fun `MpesaTools registers 4 tools`() {
        val tools = MpesaTools(config).allTools()
        assertEquals(4, tools.size)
        val names = tools.map { it.descriptor.name }.toSet()
        assertTrue("mpesa_stk_push" in names)
        assertTrue("mpesa_check_balance" in names)
        assertTrue("mpesa_transaction_status" in names)
        assertTrue("mpesa_b2c_send" in names)
    }

    @Test
    fun `TelephonyTools registers 3 tools`() {
        val tools = TelephonyTools(config).allTools()
        assertEquals(3, tools.size)
        val names = tools.map { it.descriptor.name }.toSet()
        assertTrue("telephony_send_sms" in names)
        assertTrue("telephony_make_call" in names)
        assertTrue("telephony_read_sms" in names)
    }

    @Test
    fun `WhatsAppTools registers 5 tools`() {
        val tools = WhatsAppTools(config).allTools()
        assertEquals(5, tools.size)
        val names = tools.map { it.descriptor.name }.toSet()
        assertTrue("whatsapp_send_text" in names)
        assertTrue("whatsapp_send_template" in names)
        assertTrue("whatsapp_send_list_message" in names)
        assertTrue("whatsapp_send_reply_buttons" in names)
        assertTrue("whatsapp_read_messages" in names)
    }

    @Test
    fun `TelegramTools registers 4 tools`() {
        val tools = TelegramTools(config).allTools()
        assertEquals(4, tools.size)
    }

    @Test
    fun `MemoryTools registers 4 tools`() {
        val tools = MemoryTools(config).allTools()
        assertEquals(4, tools.size)
        val names = tools.map { it.descriptor.name }.toSet()
        assertTrue("memory_save" in names)
        assertTrue("memory_search" in names)
        assertTrue("memory_delete" in names)
        assertTrue("memory_list_all" in names)
    }

    // ── Memory store (in-memory fallback) ────────────────────────────────────

    @Test
    fun `MemoryStore save and search without Room DAO`() = runTest {
        val store = OpenClawMemoryStore("/tmp/test.db")
        // dao is null — uses in-memory fallback
        val tools = MemoryTools(config).also { it.store = store }

        val saveResult = tools.SaveMemoryTool().execute(
            MemoryTools.SaveMemoryArgs(
                content = "User prefers Kiswahili responses",
                tags    = listOf("language", "preference"),
            )
        )
        assertEquals("saved", saveResult.status)
        assertTrue(saveResult.id.isNotBlank())

        val searchResult = tools.SearchMemoryTool().execute(
            MemoryTools.SearchMemoryArgs(query = "Kiswahili")
        )
        assertEquals(1, searchResult.records.size)
        assertEquals("User prefers Kiswahili responses", searchResult.records.first().content)
    }

    @Test
    fun `MemoryStore delete removes record`() = runTest {
        val store = OpenClawMemoryStore("/tmp/test2.db")
        val tools = MemoryTools(config).also { it.store = store }

        val saved = tools.SaveMemoryTool().execute(
            MemoryTools.SaveMemoryArgs(content = "Temp fact", tags = emptyList())
        )
        val deleted = tools.DeleteMemoryTool().execute(
            MemoryTools.DeleteMemoryArgs(id = saved.id)
        )
        assertTrue(deleted.deleted)

        val all = tools.ListMemoriesTool().execute(Unit)
        assertTrue(all.records.none { it.id == saved.id })
    }

    // ── WhatsApp message provider ──────────────────────────────────────────────

    @Test
    fun `WhatsAppTools ReadMessagesTool returns from provider`() = runTest {
        val waTools = WhatsAppTools(config)
        val readTool = waTools.ReadMessagesTool()

        // Inject a fake provider
        readTool.messageProvider = { limit, from ->
            listOf(
                ai.androclaw.tools.messaging.WaMessage(
                    from = "254712345678", body = "Habari?", timestamp = 1700000000L, type = "text"
                )
            ).take(limit)
        }

        val result = readTool.execute(
            WhatsAppTools.ReadMessagesArgs(limit = 5)
        )
        assertEquals(1, result.messages.size)
        assertEquals("Habari?", result.messages.first().body)
    }

    // ── SMS provider ──────────────────────────────────────────────────────────

    @Test
    fun `TelephonyTools ReadSmsTool returns from provider`() = runTest {
        val telTools = TelephonyTools(config)
        val readTool = TelephonyTools.ReadSmsTool()

        telTools.smsProvider = { limit, from ->
            listOf(
                ai.androclaw.tools.telephony.SmsMessage(
                    from = "+254700000000",
                    text = "Confirmed. Ksh100.00 sent to John.",
                    timestamp = "2024-01-01T10:00:00Z",
                )
            )
        }

        val result = readTool.execute(TelephonyTools.ReadSmsTool.Args(limit = 10))
        assertEquals(1, result.messages.size)
        assertTrue(result.messages.first().text.contains("Ksh100.00"))
    }
}

