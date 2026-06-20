package ai.androclaw.data.prefs

import ai.koog.agents.snapshot.providers.InMemoryPersistenceStorageProvider
import ai.androclaw.BuildConfig
import ai.androclaw.agent.OpenClawConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Builds a live OpenClawConfig snapshot from ConfigStore.
 *
 * Secret priority (first non-blank wins):
 *   1. EncryptedSharedPreferences  — user-entered via onboarding/settings
 *   2. BuildConfig                 — injected from local.properties at build time
 *   3. Empty string                — agent's hasXxx guards skip that service
 *
 * Non-secrets read from plain DataStore (also falls back to BuildConfig).
 */
object ConfigBridge {

    fun fromStore(store: ConfigStore, dbPath: String): OpenClawConfig = runBlocking {
        val prefs = store.prefs.first()

        // Plain DataStore reader
        fun plain(key: androidx.datastore.preferences.core.Preferences.Key<String>,
                  buildFallback: String = "") =
            prefs[key]?.takeIf { it.isNotBlank() } ?: buildFallback

        fun plainInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>,
                     default: Int = 0) = prefs[key] ?: default

        fun plainBool(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>,
                      default: Boolean = false) = prefs[key] ?: default

        // EncryptedSharedPreferences reader — falls back to BuildConfig
        fun secret(encKey: String, buildFallback: String = ""): String {
            val enc = store.getSecret(encKey)
            return enc.ifBlank { buildFallback }
        }

        OpenClawConfig(
            // Identity
            userName         = plain(ConfigStore.USER_NAME, "User"),
            language         = plain(ConfigStore.LANGUAGE, "sw"),

            // LLM
            googleGenAiApiKey = secret(ConfigStore.SecretKeys.GOOGLE_GENAI_API_KEY,
                                      BuildConfig.GOOGLE_GENAI_API_KEY),

            // AgentPhone
            agentPhoneApiKey = secret(ConfigStore.SecretKeys.AGENTPHONE_API_KEY,
                                      BuildConfig.AGENTPHONE_API_KEY),

            // Google OAuth (falls back to BuildConfig for testing)
            googleOAuthToken = secret(ConfigStore.SecretKeys.GOOGLE_OAUTH_TOKEN,
                                      BuildConfig.GOOGLE_OAUTH_TOKEN),

            // Dev connectors
            githubPat        = secret(ConfigStore.SecretKeys.GITHUB_PAT),
            linearApiKey     = secret(ConfigStore.SecretKeys.LINEAR_API_KEY),

            // Vonage fields removed — telephony now via AgentPhone MCP

            // WhatsApp (Vonage Messages API)
            vonageMsgApiKey     = secret(ConfigStore.SecretKeys.VONAGE_MSG_API_KEY,
                                         BuildConfig.VONAGE_MSG_API_KEY),
            vonageMsgApiSecret  = secret(ConfigStore.SecretKeys.VONAGE_MSG_API_SECRET,
                                         BuildConfig.VONAGE_MSG_API_SECRET),
            vonageMsgFromNumber = plain(ConfigStore.VONAGE_MSG_FROM,
                                        BuildConfig.VONAGE_MSG_FROM_NUMBER),
            vonageMsgSandbox    = (prefs[ConfigStore.VONAGE_MSG_SANDBOX] ?: true),
            // M-Pesa
            mpesaConsumerKey    = secret(ConfigStore.SecretKeys.MPESA_CONSUMER_KEY,
                                         BuildConfig.MPESA_CONSUMER_KEY),
            mpesaConsumerSecret = secret(ConfigStore.SecretKeys.MPESA_CONSUMER_SECRET,
                                         BuildConfig.MPESA_CONSUMER_SECRET),
            mpesaShortcode      = plain(ConfigStore.MPESA_SHORTCODE,
                                        BuildConfig.MPESA_SHORTCODE),
            mpesaPasskey        = secret(ConfigStore.SecretKeys.MPESA_PASSKEY,
                                         BuildConfig.MPESA_PASSKEY),
            mpesaCallbackUrl    = plain(ConfigStore.MPESA_CALLBACK_URL),
            mpesaEnv            = plain(ConfigStore.MPESA_ENV, "sandbox"),

            // Gateway
            gatewayBaseUrl   = plain(ConfigStore.GATEWAY_BASE_URL,
                                     BuildConfig.GATEWAY_BASE_URL),
            bridgeSecret     = secret(ConfigStore.SecretKeys.BRIDGE_SECRET,
                                      BuildConfig.BRIDGE_SECRET),

            // Storage
            memoryDbPath      = dbPath,
            persistenceStorage = InMemoryPersistenceStorageProvider(),
        )
    }
}

