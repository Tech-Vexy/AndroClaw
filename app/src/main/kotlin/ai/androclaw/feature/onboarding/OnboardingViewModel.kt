package ai.androclaw.feature.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.feature.auth.FirebaseAuthManager
import ai.androclaw.feature.auth.FirebaseFirestoreManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class OnboardingStep {
    WELCOME,
    GOOGLE_GENAI,
    AGENTPHONE,
    VOICE,
    GOOGLE,
    GITHUB,
    LINEAR,
    WHATSAPP,
    GATEWAY,
    DONE,
}

data class OnboardingState(
    val step: OnboardingStep        = OnboardingStep.WELCOME,
    val canProceed: Boolean         = false,
    val isLoading: Boolean          = false,
    val errorMessage: String?       = null,

    // Fields
    val userName: String            = "",
    val language: String            = "sw",

    val googleGenAiKey: String      = "",
    val agentPhoneApiKey: String    = "",

    val deepgramKey: String         = "",
    val cartesiaKey: String         = "",
    val autoSpeak: Boolean          = true,

    val googleOAuthToken: String    = "",

    val githubPat: String           = "",
    val linearApiKey: String        = "",

    val vonageMsgApiKey: String   = "",
    val vonageMsgApiSecret: String = "",
    val vonageMsgFromNumber: String = "",
    val vonageMsgSandbox: Boolean  = true,



    val gatewayUrl: String          = "",
)

class OnboardingViewModel(
    app: Application,
    private val authManager: FirebaseAuthManager,
    private val firestoreManager: FirebaseFirestoreManager
) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init { validateStep() }

    // ── Field updaters (one per input) ────────────────────────────────────────

    fun setGoogleOAuthToken(v: String) = update { copy(googleOAuthToken = v) }
    fun setGithubPat(v: String)        = update { copy(githubPat = v) }
    fun setLinearApiKey(v: String)     = update { copy(linearApiKey = v) }
    fun setUserName(v: String)         = update { copy(userName = v) }
    fun setLanguage(v: String)          = update { copy(language = v) }
    fun setGoogleGenAiKey(v: String)    = update { copy(googleGenAiKey = v) }
    fun setAgentPhoneApiKey(v: String)  = update { copy(agentPhoneApiKey = v) }
    fun setDeepgramKey(v: String)       = update { copy(deepgramKey = v) }
    fun setCartesiaKey(v: String)       = update { copy(cartesiaKey = v) }
    fun setAutoSpeak(v: Boolean)        = update { copy(autoSpeak = v) }
    fun setVonageMsgApiKey(v: String)    = update { copy(vonageMsgApiKey = v) }
    fun setVonageMsgApiSecret(v: String) = update { copy(vonageMsgApiSecret = v) }
    fun setVonageMsgFromNumber(v: String) = update { copy(vonageMsgFromNumber = v) }
    fun setVonageMsgSandbox(v: Boolean)  = update { copy(vonageMsgSandbox = v) }

    fun setGatewayUrl(v: String)        = update { copy(gatewayUrl = v) }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun next() {
        val s = _state.value
        if (!s.canProceed) return
        val nextStep = OnboardingStep.entries.let { steps ->
            val idx = steps.indexOf(s.step)
            if (idx < steps.lastIndex) steps[idx + 1] else s.step
        }
        update { copy(step = nextStep) }
    }

    fun back() {
        val s = _state.value
        val prevStep = OnboardingStep.entries.let { steps ->
            val idx = steps.indexOf(s.step)
            if (idx > 0) steps[idx - 1] else s.step
        }
        update { copy(step = prevStep) }
    }

    fun skip() = next()   // Optional steps can be skipped

    fun finish() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val s = _state.value
            with(store) {
                // ── Non-sensitive ─────────────────────────────────────────
                set(ConfigStore.USER_NAME,          s.userName)
                set(ConfigStore.LANGUAGE,           s.language)
                set(ConfigStore.VOICE_AUTO_SPEAK,   s.autoSpeak)
                set(ConfigStore.VONAGE_MSG_FROM,   s.vonageMsgFromNumber)
                set(ConfigStore.VONAGE_MSG_SANDBOX, s.vonageMsgSandbox)

                set(ConfigStore.GATEWAY_BASE_URL,   s.gatewayUrl)

                // ── Secrets (EncryptedSharedPreferences) ──────────────────
                setGoogleGenAiApiKey(s.googleGenAiKey)
                setAgentPhoneApiKey(s.agentPhoneApiKey)
                setDeepgramApiKey(s.deepgramKey)
                setCartesiaApiKey(s.cartesiaKey)
                setGoogleOAuthToken(s.googleOAuthToken)
                setGithubPat(s.githubPat)
                setLinearApiKey(s.linearApiKey)
                setVonageMsgApiKey(s.vonageMsgApiKey)
                setVonageMsgApiSecret(s.vonageMsgApiSecret)

                setOnboardingDone()
            }

            // Sync to Firebase Firestore if signed in
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

            _state.update { it.copy(isLoading = false, step = OnboardingStep.DONE) }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private fun update(block: OnboardingState.() -> OnboardingState) {
        _state.update { block(it) }
        validateStep()
    }

    private fun validateStep() {
        val s     = _state.value
        val valid = when (s.step) {
            OnboardingStep.WELCOME     -> s.userName.isNotBlank()
            OnboardingStep.GOOGLE_GENAI -> s.googleGenAiKey.isNotBlank()
            OnboardingStep.VOICE       -> s.deepgramKey.isNotBlank() && s.cartesiaKey.isNotBlank()
            OnboardingStep.GATEWAY     -> s.gatewayUrl.startsWith("https://")
            // Optional steps
            OnboardingStep.AGENTPHONE,
            OnboardingStep.GOOGLE,
            OnboardingStep.GITHUB,
            OnboardingStep.LINEAR,
            OnboardingStep.WHATSAPP,
            OnboardingStep.DONE       -> true
        }
        _state.update { it.copy(canProceed = valid) }
    }
}

