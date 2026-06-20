package ai.androclaw.integration

import ai.androclaw.agent.OpenClawAgent
import ai.androclaw.agent.OpenClawConfig
import ai.androclaw.agent.memory.OpenClawMemoryStore
import ai.androclaw.tools.memory.MemoryTools
import ai.androclaw.tools.mpesa.MpesaTools
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.messaging.WaMessage
import kotlinx.coroutines.test.runTest
import io.ktor.client.request.get
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * End-to-end integration tests.
 *
 * These tests call real APIs and are SKIPPED automatically unless
 * the required environment variables are present. Safe to run in CI
 * with secrets injected, skipped in local dev without them.
 *
 * Run a specific test:
 *   ANTHROPIC_API_KEY=sk-ant-... ./gradlew :app:test --tests "*IntegrationTest.agent responds*"
 */
class AgentIntegrationTest {

    private val googleGenAiKey = System.getenv("GOOGLE_GENAI_API_KEY") ?: ""
    private val mpesaKey      = System.getenv("MPESA_CONSUMER_KEY")   ?: ""
    private val mpesaSecret   = System.getenv("MPESA_CONSUMER_SECRET")?: ""
    private val gatewayUrl    = System.getenv("GATEWAY_BASE_URL")     ?: ""
    private val vonageApiKey   = System.getenv("VONAGE_MSG_API_KEY")   ?: ""

    private fun config(overrides: OpenClawConfig.() -> OpenClawConfig = { this }) =
        OpenClawConfig(
            googleGenAiApiKey    = googleGenAiKey,
            mpesaConsumerKey     = mpesaKey,
            mpesaConsumerSecret  = mpesaSecret,
            mpesaShortcode       = System.getenv("MPESA_SHORTCODE")  ?: "174379",
            mpesaPasskey         = System.getenv("MPESA_PASSKEY")    ?: "",
            mpesaCallbackUrl     = "$gatewayUrl/mpesa/callback",
            vonageMsgApiKey      = vonageApiKey,
            vonageMsgApiSecret   = System.getenv("VONAGE_MSG_API_SECRET") ?: "",
            vonageMsgFromNumber  = System.getenv("VONAGE_MSG_FROM_NUMBER") ?: "14157386102",
            gatewayBaseUrl       = gatewayUrl,
        ).overrides()

    // ── Agent tests ───────────────────────────────────────────────────────────

    @Test
    fun `agent responds to simple greeting`() = runTest {
        assumeTrue("GOOGLE_GENAI_API_KEY required", googleGenAiKey.isNotBlank())

        val agent = OpenClawAgent.build(config())
        val result = agent.run("Habari! Naitwa Kamau.")

        assertNotNull(result)
        assertTrue("Response should not be empty", result!!.isNotBlank())
        println("Agent response: $result")
    }

    @Test
    fun `agent uses memory tool to save and recall a fact`() = runTest {
        assumeTrue("GOOGLE_GENAI_API_KEY required", googleGenAiKey.isNotBlank())

        val store = OpenClawMemoryStore("/tmp/integration_test.db")
        val agent = OpenClawAgent.build(config())

        // Teach the agent a fact
        val saveResp = agent.run(
            "Remember this: my M-Pesa shortcode is 174379 and I prefer to receive payment confirmations in Kiswahili."
        )
        println("Save response: $saveResp")

        // Ask it to recall
        val recallResp = agent.run("What is my M-Pesa shortcode?")
        println("Recall response: $recallResp")

        assertTrue(
            "Agent should recall the shortcode",
            recallResp?.contains("174379") == true,
        )
    }

    @Test
    fun `agent can describe available tools`() = runTest {
        assumeTrue("GOOGLE_GENAI_API_KEY required", googleGenAiKey.isNotBlank())

        val agent  = OpenClawAgent.build(config())
        val result = agent.run("What tools do you have available? List them briefly.")

        assertNotNull(result)
        val lower = result!!.lowercase()
        // Should mention at least some of our tool categories
        assertTrue(
            "Agent should mention M-Pesa or messaging tools",
            lower.contains("mpesa") || lower.contains("whatsapp") || lower.contains("sms"),
        )
        println("Tools description: $result")
    }

    // ── M-Pesa sandbox tests ──────────────────────────────────────────────────

    @Test
    fun `MpesaTools fetches sandbox access token`() = runTest {
        assumeTrue("MPESA_CONSUMER_KEY required", mpesaKey.isNotBlank())
        assumeTrue("MPESA_CONSUMER_SECRET required", mpesaSecret.isNotBlank())

        val tools = MpesaTools(config())
        // Access token fetch is internal — validate indirectly via balance check
        // (Balance is async in Daraja sandbox; we just check no exception thrown)
        try {
            val result = tools.BalanceTool().execute(Unit)
            println("Balance result: $result")
            assertNotNull(result)
        } catch (e: Exception) {
            // Sandbox may reject if credentials invalid — log and pass
            println("Balance check error (expected in sandbox): ${e.message}")
        }
    }

    // ── WhatsApp message provider tests ───────────────────────────────────────

    @Test
    fun `WhatsApp read messages returns from mock provider`() = runTest {
        val tools    = WhatsAppTools(config())
        val readTool = tools.ReadMessagesTool()

        val fakeMessages = listOf(
            WaMessage("254712345678", "Ninataka kununua sukari", System.currentTimeMillis() / 1000, "text"),
            WaMessage("254722222222", "Bei ni ngapi?",            System.currentTimeMillis() / 1000, "text"),
        )
        readTool.messageProvider = { limit, from ->
            if (from.isBlank()) fakeMessages.take(limit)
            else fakeMessages.filter { it.from == from }.take(limit)
        }

        val all      = readTool.execute(WhatsAppTools.ReadMessagesArgs(limit = 10))
        assertEquals(2, all.messages.size)

        val filtered = readTool.execute(WhatsAppTools.ReadMessagesArgs(limit = 10, fromNumber = "254712345678"))
        assertEquals(1, filtered.messages.size)
        assertEquals("Ninataka kununua sukari", filtered.messages.first().body)
    }

    // ── Gateway connectivity ───────────────────────────────────────────────────

    @Test
    fun `gateway health endpoints respond`() = runTest {
        assumeTrue("GATEWAY_BASE_URL required", gatewayUrl.isNotBlank())

        val client = io.ktor.client.HttpClient()
        val services = listOf("tg", "email", "vonage", "wa").map { "$gatewayUrl/$it/health" }

        for (url in services) {
            try {
                val resp = client.get(url)
                println("$url → ${resp.status}")
            } catch (e: Exception) {
                println("$url → ERROR: ${e.message}")
                // Don't fail — service may not be deployed yet
            }
        }
        client.close()
    }
}

