package ai.androclaw.feature.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.feature.auth.FirebaseAuthManager
import ai.androclaw.feature.auth.FirebaseFirestoreManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ai.androclaw.core.AgentRestartWorker

data class SettingsState(
    val isLoading: Boolean        = true,
    val savedMessage: String?     = null,

    // Identity
    val userName: String          = "",
    val language: String          = "sw",

    // LLM
    val googleGenAiKey: String    = "",
    val agentPhoneApiKey: String  = "",

    // Voice
    val deepgramKey: String       = "",
    val cartesiaKey: String       = "",
    val cartesiaVoiceId: String   = "",
    val autoSpeak: Boolean        = true,

    val googleOAuthToken: String    = "",
    val githubPat: String           = "",
    val slackBotToken: String       = "",
    val linearApiKey: String        = "",
    val notionToken: String         = "",

    // WhatsApp (Vonage Messages API)
    val vonageMsgApiKey: String    = "",
    val vonageMsgApiSecret: String = "",
    val vonageMsgFromNumber: String = "",
    val vonageMsgSandbox: Boolean  = true,

    // Telegram
    val telegramApiId: String     = "",
    val telegramApiHash: String   = "",

    // M-Pesa
    val mpesaConsumerKey: String    = "",
    val mpesaConsumerSecret: String = "",
    val mpesaShortcode: String      = "",
    val mpesaPasskey: String        = "",
    val mpesaCallbackUrl: String    = "",
    val mpesaEnv: String            = "sandbox",

    // Gateway
    val gatewayUrl: String        = "",
)

class SettingsViewModel(
    app: Application,
    private val authManager: FirebaseAuthManager,
    private val firestoreManager: FirebaseFirestoreManager
) : AndroidViewModel(app) {

    private val store  = ConfigStore(app)
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init { loadSettings() }

    private fun loadSettings() {
        viewModelScope.launch {
            store.prefs.first().let { prefs ->
                _state.update {
                    it.copy(
                        googleOAuthToken  = prefs[ConfigStore.GOOGLE_OAUTH_TOKEN]   ?: "",
                        githubPat         = prefs[ConfigStore.GITHUB_PAT]            ?: "",
                        slackBotToken     = prefs[ConfigStore.SLACK_BOT_TOKEN]       ?: "",
                        linearApiKey      = prefs[ConfigStore.LINEAR_API_KEY]        ?: "",
                        notionToken       = prefs[ConfigStore.NOTION_TOKEN]          ?: "",
                        agentPhoneApiKey  = store.getSecret(ConfigStore.SecretKeys.AGENTPHONE_API_KEY),
                        isLoading         = false,
                        userName          = prefs[ConfigStore.USER_NAME]            ?: "",
                        language          = prefs[ConfigStore.LANGUAGE]             ?: "sw",
                        googleGenAiKey    = store.getSecret(ConfigStore.SecretKeys.GOOGLE_GENAI_API_KEY),
                        deepgramKey       = store.getSecret(ConfigStore.SecretKeys.DEEPGRAM_API_KEY),
                        cartesiaKey       = store.getSecret(ConfigStore.SecretKeys.CARTESIA_API_KEY),
                        autoSpeak         = prefs[ConfigStore.VOICE_AUTO_SPEAK]     ?: true,
                        vonageMsgApiKey    = store.getSecret(ConfigStore.SecretKeys.VONAGE_MSG_API_KEY),
                        vonageMsgApiSecret = store.getSecret(ConfigStore.SecretKeys.VONAGE_MSG_API_SECRET),
                        vonageMsgFromNumber = prefs[ConfigStore.VONAGE_MSG_FROM]    ?: "",
                        vonageMsgSandbox   = prefs[ConfigStore.VONAGE_MSG_SANDBOX]  ?: true,
                        telegramApiId     = prefs[ConfigStore.TELEGRAM_API_ID]?.toString() ?: "",
                        telegramApiHash   = prefs[ConfigStore.TELEGRAM_API_HASH]    ?: "",
                        mpesaConsumerKey  = prefs[ConfigStore.MPESA_CONSUMER_KEY]   ?: "",
                        mpesaConsumerSecret = prefs[ConfigStore.MPESA_CONSUMER_SECRET] ?: "",
                        mpesaShortcode    = prefs[ConfigStore.MPESA_SHORTCODE]      ?: "",
                        mpesaPasskey      = prefs[ConfigStore.MPESA_PASSKEY]        ?: "",
                        mpesaCallbackUrl  = prefs[ConfigStore.MPESA_CALLBACK_URL]   ?: "",
                        mpesaEnv          = prefs[ConfigStore.MPESA_ENV]            ?: "sandbox",
                        gatewayUrl        = prefs[ConfigStore.GATEWAY_BASE_URL]     ?: "",
                    )
                }
            }
        }
    }

    // Field updaters
    fun setGoogleOAuthToken(v: String) = _state.update { it.copy(googleOAuthToken = v) }
    fun setGithubPat(v: String)        = _state.update { it.copy(githubPat = v) }
    fun setSlackBotToken(v: String)    = _state.update { it.copy(slackBotToken = v) }
    fun setLinearApiKey(v: String)     = _state.update { it.copy(linearApiKey = v) }
    fun setNotionToken(v: String)      = _state.update { it.copy(notionToken = v) }
    fun setUserName(v: String)         = _state.update { it.copy(userName = v) }
    fun setLanguage(v: String)         = _state.update { it.copy(language = v) }
    fun setGoogleGenAiKey(v: String)   = _state.update { it.copy(googleGenAiKey = v) }
    fun setAgentPhoneApiKey(v: String) = _state.update { it.copy(agentPhoneApiKey = v) }
    fun setDeepgramKey(v: String)      = _state.update { it.copy(deepgramKey = v) }
    fun setCartesiaKey(v: String)      = _state.update { it.copy(cartesiaKey = v) }
    fun setAutoSpeak(v: Boolean)       = _state.update { it.copy(autoSpeak = v) }
    fun setVonageMsgApiKey(v: String)     = _state.update { it.copy(vonageMsgApiKey = v) }
    fun setVonageMsgApiSecret(v: String)  = _state.update { it.copy(vonageMsgApiSecret = v) }
    fun setVonageMsgFromNumber(v: String) = _state.update { it.copy(vonageMsgFromNumber = v) }
    fun setVonageMsgSandbox(v: Boolean)   = _state.update { it.copy(vonageMsgSandbox = v) }
    fun setTelegramApiId(v: String)    = _state.update { it.copy(telegramApiId = v) }
    fun setTelegramApiHash(v: String)  = _state.update { it.copy(telegramApiHash = v) }
    fun setMpesaConsumerKey(v: String) = _state.update { it.copy(mpesaConsumerKey = v) }
    fun setMpesaConsumerSecret(v: String) = _state.update { it.copy(mpesaConsumerSecret = v) }
    fun setMpesaShortcode(v: String)   = _state.update { it.copy(mpesaShortcode = v) }
    fun setMpesaPasskey(v: String)     = _state.update { it.copy(mpesaPasskey = v) }
    fun setMpesaCallbackUrl(v: String) = _state.update { it.copy(mpesaCallbackUrl = v) }
    fun setMpesaEnv(v: String)         = _state.update { it.copy(mpesaEnv = v) }
    fun setGatewayUrl(v: String)       = _state.update { it.copy(gatewayUrl = v) }

    fun save() {
        viewModelScope.launch {
            val s = _state.value
            with(store) {
                // ── Non-sensitive (plain DataStore) ──────────────────────
                set(ConfigStore.USER_NAME,          s.userName)
                set(ConfigStore.LANGUAGE,           s.language)
                set(ConfigStore.VOICE_AUTO_SPEAK,   s.autoSpeak)
                set(ConfigStore.VONAGE_MSG_FROM,    s.vonageMsgFromNumber)
                set(ConfigStore.VONAGE_MSG_SANDBOX, s.vonageMsgSandbox)
                s.telegramApiId.toIntOrNull()?.let { set(ConfigStore.TELEGRAM_API_ID, it) }
                set(ConfigStore.MPESA_SHORTCODE,    s.mpesaShortcode)
                set(ConfigStore.MPESA_CALLBACK_URL, s.mpesaCallbackUrl)
                set(ConfigStore.MPESA_ENV,          s.mpesaEnv)
                set(ConfigStore.GATEWAY_BASE_URL,   s.gatewayUrl)

                // ── Secrets (EncryptedSharedPreferences) ─────────────────
                setGoogleGenAiApiKey(s.googleGenAiKey)
                setAgentPhoneApiKey(s.agentPhoneApiKey)
                setDeepgramApiKey(s.deepgramKey)
                setCartesiaApiKey(s.cartesiaKey)
                setGoogleOAuthToken(s.googleOAuthToken)
                setGithubPat(s.githubPat)
                setSlackBotToken(s.slackBotToken)
                setLinearApiKey(s.linearApiKey)
                setNotionToken(s.notionToken)
                setVonageMsgApiKey(s.vonageMsgApiKey)
                setVonageMsgApiSecret(s.vonageMsgApiSecret)
                setTelegramApiHash(s.telegramApiHash)
                setMpesaConsumerKey(s.mpesaConsumerKey)
                setMpesaConsumerSecret(s.mpesaConsumerSecret)
                setMpesaPasskey(s.mpesaPasskey)
            }

            // Sync configuration to Firestore if signed in
            val currentUser = authManager.currentUser.value
            if (currentUser != null) {
                val configMap = mutableMapOf<String, Any>(
                    ConfigStore.USER_NAME.name to s.userName,
                    ConfigStore.LANGUAGE.name to s.language,
                    ConfigStore.VOICE_AUTO_SPEAK.name to s.autoSpeak,
                    ConfigStore.VONAGE_MSG_FROM.name to s.vonageMsgFromNumber,
                    ConfigStore.VONAGE_MSG_SANDBOX.name to s.vonageMsgSandbox,
                    ConfigStore.MPESA_SHORTCODE.name to s.mpesaShortcode,
                    ConfigStore.MPESA_CALLBACK_URL.name to s.mpesaCallbackUrl,
                    ConfigStore.MPESA_ENV.name to s.mpesaEnv,
                    ConfigStore.GATEWAY_BASE_URL.name to s.gatewayUrl,

                    ConfigStore.SecretKeys.GOOGLE_GENAI_API_KEY to s.googleGenAiKey,
                    ConfigStore.SecretKeys.AGENTPHONE_API_KEY to s.agentPhoneApiKey,
                    ConfigStore.SecretKeys.DEEPGRAM_API_KEY to s.deepgramKey,
                    ConfigStore.SecretKeys.CARTESIA_API_KEY to s.cartesiaKey,
                    ConfigStore.SecretKeys.GOOGLE_OAUTH_TOKEN to s.googleOAuthToken,
                    ConfigStore.SecretKeys.GITHUB_PAT to s.githubPat,
                    ConfigStore.SecretKeys.SLACK_BOT_TOKEN to s.slackBotToken,
                    ConfigStore.SecretKeys.LINEAR_API_KEY to s.linearApiKey,
                    ConfigStore.SecretKeys.NOTION_TOKEN to s.notionToken,
                    ConfigStore.SecretKeys.VONAGE_MSG_API_KEY to s.vonageMsgApiKey,
                    ConfigStore.SecretKeys.VONAGE_MSG_API_SECRET to s.vonageMsgApiSecret,
                    ConfigStore.SecretKeys.TELEGRAM_API_HASH to s.telegramApiHash,
                    ConfigStore.SecretKeys.MPESA_CONSUMER_KEY to s.mpesaConsumerKey,
                    ConfigStore.SecretKeys.MPESA_CONSUMER_SECRET to s.mpesaConsumerSecret,
                    ConfigStore.SecretKeys.MPESA_PASSKEY to s.mpesaPasskey
                )
                s.telegramApiId.toIntOrNull()?.let {
                    configMap[ConfigStore.TELEGRAM_API_ID.name] = it
                }
                firestoreManager.saveUserConfig(currentUser.uid, configMap)
            }

            AgentRestartWorker.schedule(getApplication())

            _state.update { it.copy(savedMessage = "Settings saved — agent reloading…") }
            kotlinx.coroutines.delay(2000)
            _state.update { it.copy(savedMessage = null) }
        }
    }

    fun clearAllData() {
        authManager.signOut()
        viewModelScope.launch {
            store.clearAll()
        }
    }
}

