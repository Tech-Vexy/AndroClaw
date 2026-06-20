package ai.androclaw.agent

import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider

/**
 * All runtime configuration for the OpenClaw agent.
 * Populated from DataStore via ConfigBridge at app startup.
 */
data class OpenClawConfig(
    // Identity
    val userName: String            = "User",
    val deviceName: String          = "Android",
    val language: String            = "sw",

    // LLM
    val googleGenAiApiKey: String   = "",

    // AgentPhone (hosted phone number + SMS + voice calls via MCP)
    val agentPhoneApiKey: String    = "",

    // Google OAuth (single token covers Gmail + Calendar + Drive MCP)
    val googleOAuthToken: String    = "",

    // GitHub
    val githubPat: String           = "",

    // Slack
    val slackBotToken: String       = "",     // xoxb-...

    // Linear
    val linearApiKey: String        = "",     // lin_api_...

    // Notion
    val notionToken: String         = "",     // secret_...

    // Telephony (AgentPhone — via MCP)
    // No local credentials needed; handled entirely by AgentPhone MCP.

    // WhatsApp (Vonage Messages API)
    val vonageMsgApiKey: String     = "",
    val vonageMsgApiSecret: String  = "",
    val vonageMsgFromNumber: String = "",  // Vonage sandbox: 14157386102
    val vonageMsgSandbox: Boolean   = true,

    // Telegram (user account — MTProto bridge)
    val telegramApiId: Int          = 0,
    val telegramApiHash: String     = "",
    val telegramSessionPath: String = "",

    // M-Pesa (Daraja)
    val mpesaConsumerKey: String    = "",
    val mpesaConsumerSecret: String = "",
    val mpesaShortcode: String      = "",
    val mpesaPasskey: String        = "",
    val mpesaCallbackUrl: String    = "",
    val mpesaEnv: String            = "sandbox",  // "sandbox" | "production"

    // Gateway (Render)
    val gatewayBaseUrl: String      = "",
    val bridgeSecret: String        = "",

    // Storage
    val memoryDbPath: String        = "",
    val persistenceStorage: PersistenceStorageProvider<*> = InMemoryPersistenceStorageProvider(),
) {
    // ── Configuration guards ──────────────────────────────────────────────────
    // Used by OpenClawAgent to skip MCP connections for unconfigured services.

    val hasAgentPhone: Boolean get() = agentPhoneApiKey.isNotBlank()
    val hasGoogle: Boolean    get() = googleOAuthToken.isNotBlank()
    val hasGitHub: Boolean    get() = githubPat.isNotBlank()
    val hasSlack: Boolean     get() = slackBotToken.isNotBlank()
    val hasLinear: Boolean    get() = linearApiKey.isNotBlank()
    val hasNotion: Boolean    get() = notionToken.isNotBlank()
    val hasMpesa: Boolean     get() = mpesaConsumerKey.isNotBlank()
    val hasGateway: Boolean   get() = gatewayBaseUrl.startsWith("https://")
    val hasWhatsApp: Boolean  get() = vonageMsgApiKey.isNotBlank() && vonageMsgApiSecret.isNotBlank()
    val hasTelegram: Boolean  get() = telegramApiId != 0 && telegramApiHash.isNotBlank()
    val hasGoogleGenAi: Boolean get() = googleGenAiApiKey.isNotBlank()
}

