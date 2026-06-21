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
    val linearApiKey: String        = "",

    // WhatsApp (Vonage Messages API)
    val vonageMsgApiKey: String    = "",
    val vonageMsgApiSecret: String = "",
    val vonageMsgFromNumber: String = "",
    val vonageMsgSandbox: Boolean  = true,



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
                        linearApiKey      = prefs[ConfigStore.LINEAR_API_KEY]        ?: "",
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

                        gatewayUrl        = prefs[ConfigStore.GATEWAY_BASE_URL]     ?: "",
                    )
                }
            }
        }
    }

    // Field updaters
    fun setGoogleOAuthToken(v: String) = _state.update { it.copy(googleOAuthToken = v) }
    fun setGithubPat(v: String)        = _state.update { it.copy(githubPat = v) }
    fun setLinearApiKey(v: String)     = _state.update { it.copy(linearApiKey = v) }
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

                set(ConfigStore.GATEWAY_BASE_URL,   s.gatewayUrl)

                // ── Secrets (EncryptedSharedPreferences) ─────────────────
                setGoogleGenAiApiKey(s.googleGenAiKey)
                setAgentPhoneApiKey(s.agentPhoneApiKey)
                setDeepgramApiKey(s.deepgramKey)
                setCartesiaApiKey(s.cartesiaKey)
                setGoogleOAuthToken(s.googleOAuthToken)
                setGithubPat(s.githubPat)
                setLinearApiKey(s.linearApiKey)
                setVonageMsgApiKey(s.vonageMsgApiKey)
                setVonageMsgApiSecret(s.vonageMsgApiSecret)

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
                    ConfigStore.GATEWAY_BASE_URL.name to s.gatewayUrl,

                    ConfigStore.SecretKeys.GOOGLE_GENAI_API_KEY to s.googleGenAiKey,
                    ConfigStore.SecretKeys.AGENTPHONE_API_KEY to s.agentPhoneApiKey,
                    ConfigStore.SecretKeys.DEEPGRAM_API_KEY to s.deepgramKey,
                    ConfigStore.SecretKeys.CARTESIA_API_KEY to s.cartesiaKey,
                    ConfigStore.SecretKeys.GOOGLE_OAUTH_TOKEN to s.googleOAuthToken,
                    ConfigStore.SecretKeys.GITHUB_PAT to s.githubPat,
                    ConfigStore.SecretKeys.LINEAR_API_KEY to s.linearApiKey,
                    ConfigStore.SecretKeys.VONAGE_MSG_API_KEY to s.vonageMsgApiKey,
                    ConfigStore.SecretKeys.VONAGE_MSG_API_SECRET to s.vonageMsgApiSecret
                )
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

