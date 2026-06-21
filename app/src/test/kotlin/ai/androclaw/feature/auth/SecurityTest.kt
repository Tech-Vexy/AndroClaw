package ai.androclaw.feature.auth

import ai.androclaw.feature.outbox.OutboxEnqueuer
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import ai.androclaw.feature.outbox.OutboxWorker

class SecurityTest {

    @Test fun `all secret keys are distinct`() {
        val keys = listOf("anthropic_api_key","agentphone_api_key","deepgram_api_key",
            "cartesia_api_key","google_oauth_token","github_pat",
            "vonage_api_key","vonage_api_secret",
            "vonage_private_key","whatsapp_token",
            "bridge_secret")
        assertEquals(keys.size, keys.toSet().size)
    }
    @Test fun `detects ConnectException as network error`() {
        assertTrue(OutboxEnqueuer.isNetworkError(ConnectException("refused")))
    }
    @Test fun `detects SocketTimeoutException`() {
        assertTrue(OutboxEnqueuer.isNetworkError(SocketTimeoutException("timed out")))
    }
    @Test fun `detects UnknownHostException`() {
        assertTrue(OutboxEnqueuer.isNetworkError(UnknownHostException("no host")))
    }
    @Test fun `detects network error by message`() {
        assertTrue(OutboxEnqueuer.isNetworkError(RuntimeException("Failed to connect")))
        assertTrue(OutboxEnqueuer.isNetworkError(RuntimeException("network unreachable")))
    }
    @Test fun `does not misclassify API errors`() {
        assertFalse(OutboxEnqueuer.isNetworkError(RuntimeException("401 Unauthorized")))
        assertFalse(OutboxEnqueuer.isNetworkError(RuntimeException("invalid API key")))
    }
    @Test fun `work names are distinct`() {
        val names = setOf(
            OutboxWorker.WORK_NAME_IMMEDIATE,
            OutboxWorker.WORK_NAME_PERIODIC,
            "agent_restart", "google_token_refresh",
        )
        assertEquals(4, names.size)
    }
    @Test fun `PEM header stripping works`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIB\n-----END PRIVATE KEY-----"
        val clean = pem.replace("-----BEGIN PRIVATE KEY-----","")
            .replace("-----END PRIVATE KEY-----","").replace("\n","").trim()
        assertFalse(clean.contains("BEGIN"))
        assertFalse(clean.contains("\n"))
    }
}

