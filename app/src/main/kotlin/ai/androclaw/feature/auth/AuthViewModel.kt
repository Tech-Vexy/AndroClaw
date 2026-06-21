package ai.androclaw.feature.auth

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.androclaw.data.prefs.ConfigStore
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false,
    val firebaseUser: FirebaseUser? = null,
    
    // Phone Auth Flow
    val verificationId: String? = null,
    val codeSent: Boolean = false,
)

class AuthViewModel(
    application: Application,
    private val authManager: FirebaseAuthManager,
    private val firestoreManager: FirebaseFirestoreManager,
    private val configStore: ConfigStore
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AuthUiState(firebaseUser = authManager.currentUser.value))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authManager.currentUser.collect { user ->
                _uiState.update { it.copy(firebaseUser = user) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    // ── Email/Password Authentication ───────────────────────────────────────

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = authManager.signInWithEmail(email, password)
                handleAuthSuccess(user)
            } catch (e: Exception) {
                Timber.e(e, "Sign in failed")
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Sign in failed") }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = authManager.signUpWithEmail(email, password)
                handleAuthSuccess(user)
            } catch (e: Exception) {
                Timber.e(e, "Sign up failed")
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Sign up failed") }
            }
        }
    }

    // ── Google Sign-in ──────────────────────────────────────────────────────

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = authManager.signInWithGoogle(idToken)
                handleAuthSuccess(user)
            } catch (e: Exception) {
                Timber.e(e, "Google Sign-In failed")
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Google Sign-In failed") }
            }
        }
    }

    // ── Phone Authentication ──────────────────────────────────────────────────

    fun sendSmsCode(activity: Activity, phoneNumber: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                authManager.startPhoneAuth(
                    activity = activity,
                    phoneNumber = phoneNumber,
                    onVerificationCompleted = { credential ->
                        viewModelScope.launch {
                            try {
                                val user = authManager.signInWithPhoneCredential(credential)
                                handleAuthSuccess(user)
                            } catch (e: Exception) {
                                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
                            }
                        }
                    },
                    onVerificationFailed = { e ->
                        _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "Phone auth failed") }
                    },
                    onCodeSent = { verificationId, _ ->
                        _uiState.update { it.copy(isLoading = false, verificationId = verificationId, codeSent = true) }
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun verifySmsCode(code: String) {
        val verificationId = _uiState.value.verificationId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val user = authManager.verifyAndSignInPhone(verificationId, code)
                handleAuthSuccess(user)
            } catch (e: Exception) {
                Timber.e(e, "Phone verification failed")
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage ?: "SMS verification failed") }
            }
        }
    }

    // ── Helper on success ────────────────────────────────────────────────────

    private suspend fun handleAuthSuccess(user: FirebaseUser) {
        firestoreManager.saveUserProfile(user.uid, user.email, user.phoneNumber)
        
        // Sync configuration from Firestore to DataStore if it exists
        val remoteConfig = firestoreManager.getUserConfig(user.uid)
        if (remoteConfig != null) {
            Timber.i("Syncing remote config from Firestore to local DataStore")
            // Apply all configurations locally
            remoteConfig.forEach { (key, value) ->
                if (value is String) {
                    when (key) {
                        "user_name" -> configStore.set(ConfigStore.USER_NAME, value)
                        "language" -> configStore.set(ConfigStore.LANGUAGE, value)
                        "vonage_msg_from_number" -> configStore.set(ConfigStore.VONAGE_MSG_FROM, value)
                        "gateway_base_url" -> configStore.set(ConfigStore.GATEWAY_BASE_URL, value)
                        
                        // Secrets
                        "google_genai_api_key" -> configStore.setGoogleGenAiApiKey(value)
                        "agentphone_api_key" -> configStore.setAgentPhoneApiKey(value)
                        "deepgram_api_key" -> configStore.setDeepgramApiKey(value)
                        "cartesia_api_key" -> configStore.setCartesiaApiKey(value)
                        "google_oauth_token" -> configStore.setGoogleOAuthToken(value)
                        "github_pat" -> configStore.setGithubPat(value)
                        "linear_api_key" -> configStore.setLinearApiKey(value)
                        "vonage_msg_api_key" -> configStore.setVonageMsgApiKey(value)
                        "vonage_msg_api_secret" -> configStore.setVonageMsgApiSecret(value)
                    }
                } else if (value is Boolean) {
                    when (key) {
                        "voice_auto_speak" -> configStore.set(ConfigStore.VOICE_AUTO_SPEAK, value)
                        "vonage_msg_sandbox" -> configStore.set(ConfigStore.VONAGE_MSG_SANDBOX, value)
                    }
                }
            }
            configStore.setOnboardingDone()
        }

        _uiState.update { it.copy(isLoading = false, isSuccess = true) }
    }

    fun signOut() {
        authManager.signOut()
        viewModelScope.launch {
            configStore.clearAll()
        }
        _uiState.update { AuthUiState() }
    }
}
