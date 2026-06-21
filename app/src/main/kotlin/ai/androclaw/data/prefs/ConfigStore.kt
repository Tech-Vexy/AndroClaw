package ai.androclaw.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ── Plain DataStore (debug / non-sensitive prefs) ─────────────────────────────
private val Context.plainDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "openclaw_config")

/**
 * Encrypted config store.
 *
 * Architecture:
 *  - Non-sensitive prefs (userName, language, gateway URL, onboarding state)
 *    → plain DataStore (fast, Flow-based reactive reads)
 *  - Sensitive secrets (API keys, tokens, PEM keys)
 *    → EncryptedSharedPreferences backed by Android Keystore AES-256-GCM
 *
 * Why two stores?
 *  DataStore doesn't support encryption natively on Android.
 *  EncryptedSharedPreferences is synchronous and doesn't support Flow.
 *  We use both: DataStore for reactivity, EncryptedSharedPreferences for secrets.
 *  Secrets are written to both so ConfigBridge can read them synchronously.
 *
 * Key hierarchy:
 *  MasterKey → AES256_GCM (Android Keystore, hardware-backed on supported devices)
 *  EncryptedSharedPreferences → AES256_SIV (key encryption) + AES256_GCM (value encryption)
 */
class ConfigStore(private val context: Context) {

    // ── Encrypted SharedPreferences for secrets ───────────────────────────────

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)   // No biometric gate — agent runs in background
            .build()
    }

    private val encryptedPrefs: android.content.SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "openclaw_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // ── Secret keys (stored in EncryptedSharedPreferences) ───────────────────

    object SecretKeys {
        const val GOOGLE_GENAI_API_KEY    = "google_genai_api_key"
        const val AGENTPHONE_API_KEY     = "agentphone_api_key"
        const val DEEPGRAM_API_KEY       = "deepgram_api_key"
        const val CARTESIA_API_KEY       = "cartesia_api_key"
        const val GOOGLE_OAUTH_TOKEN     = "google_oauth_token"
        const val GITHUB_PAT             = "github_pat"
        const val VONAGE_MSG_API_KEY    = "vonage_msg_api_key"
        const val VONAGE_MSG_API_SECRET = "vonage_msg_api_secret"
        const val BRIDGE_SECRET          = "bridge_secret"
    }

    // ── Plain DataStore keys (non-sensitive) ──────────────────────────────────

    companion object Keys {
        // Identity
        val USER_NAME           = stringPreferencesKey("user_name")
        val LANGUAGE            = stringPreferencesKey("language")
        val ONBOARDING_DONE     = booleanPreferencesKey("onboarding_done")
        val IS_OFFLINE_MODE     = booleanPreferencesKey("is_offline_mode")

        // Voice preferences (not secrets)
        val VOICE_AUTO_SPEAK    = booleanPreferencesKey("voice_auto_speak")
        val CARTESIA_VOICE_ID   = stringPreferencesKey("cartesia_voice_id")


        // Gateway (non-secret)
        val GATEWAY_BASE_URL    = stringPreferencesKey("gateway_base_url")

        // Secret keys also mirrored here for Flow reactivity in UI
        // (UI observes these; actual values read from encryptedPrefs by ConfigBridge)
        val GOOGLE_GENAI_API_KEY = stringPreferencesKey("google_genai_api_key_present")
        val AGENTPHONE_API_KEY  = stringPreferencesKey("agentphone_api_key_present")
        val GOOGLE_OAUTH_TOKEN  = stringPreferencesKey("google_oauth_token_present")
        val GITHUB_PAT          = stringPreferencesKey("github_pat_present")
        val VONAGE_MSG_API_KEY   = stringPreferencesKey("vonage_msg_api_key_present")
        val VONAGE_MSG_FROM      = stringPreferencesKey("vonage_msg_from_number")
        val VONAGE_MSG_SANDBOX   = booleanPreferencesKey("vonage_msg_sandbox")
        val BRIDGE_SECRET       = stringPreferencesKey("bridge_secret_present")
    }

    // ── Read API ──────────────────────────────────────────────────────────────

    val prefs: Flow<Preferences> = context.plainDataStore.data

    fun <T> get(key: Preferences.Key<T>, default: T): Flow<T> =
        prefs.map { it[key] ?: default }

    fun onboardingDone(): Flow<Boolean> = get(ONBOARDING_DONE, false)
    fun isOfflineMode(): Flow<Boolean>   = get(IS_OFFLINE_MODE, false)
    fun userName(): Flow<String>        = get(USER_NAME, "")
    fun language(): Flow<String>        = get(LANGUAGE, "en")
    fun autoSpeak(): Flow<Boolean>      = get(VOICE_AUTO_SPEAK, true)

    /** Read a secret synchronously from EncryptedSharedPreferences. */
    fun getSecret(key: String): String =
        encryptedPrefs.getString(key, "") ?: ""

    /** Read a secret as a Flow (for UI reactivity). Emits on every prefs change. */
    fun getSecretFlow(key: String): Flow<String> =
        prefs.map { encryptedPrefs.getString(key, "") ?: "" }

    // ── Write API ─────────────────────────────────────────────────────────────

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.plainDataStore.edit { it[key] = value }
    }

    /**
     * Store a secret value.
     * Writes to EncryptedSharedPreferences (actual value) AND to DataStore
     * (presence flag — non-empty string = set, empty = cleared) for UI reactivity.
     */
    suspend fun setSecret(key: String, value: String, datastoreKey: Preferences.Key<String>) {
        encryptedPrefs.edit()
            .putString(key, value)
            .apply()
        // Mirror presence (not value) into DataStore for Flow-based UI updates
        context.plainDataStore.edit { prefs ->
            prefs[datastoreKey] = if (value.isNotBlank()) "set" else ""
        }
    }

    suspend fun setOnboardingDone() = set(ONBOARDING_DONE, true)

    // ── Convenience setters for each secret ──────────────────────────────────

    suspend fun setGoogleGenAiApiKey(v: String) =
        setSecret(SecretKeys.GOOGLE_GENAI_API_KEY, v, GOOGLE_GENAI_API_KEY)
    suspend fun setAgentPhoneApiKey(v: String) =
        setSecret(SecretKeys.AGENTPHONE_API_KEY, v, AGENTPHONE_API_KEY)
    suspend fun setDeepgramApiKey(v: String)  =
        setSecret(SecretKeys.DEEPGRAM_API_KEY, v, stringPreferencesKey("deepgram_api_key_present"))
    suspend fun setCartesiaApiKey(v: String)  =
        setSecret(SecretKeys.CARTESIA_API_KEY, v, stringPreferencesKey("cartesia_api_key_present"))
    suspend fun setGoogleOAuthToken(v: String) =
        setSecret(SecretKeys.GOOGLE_OAUTH_TOKEN, v, GOOGLE_OAUTH_TOKEN)
    suspend fun setGithubPat(v: String)       =
        setSecret(SecretKeys.GITHUB_PAT, v, GITHUB_PAT)

    suspend fun setVonageMsgApiKey(v: String) =
        setSecret(SecretKeys.VONAGE_MSG_API_KEY, v, VONAGE_MSG_API_KEY)
    suspend fun setVonageMsgApiSecret(v: String) =
        setSecret(SecretKeys.VONAGE_MSG_API_SECRET, v, stringPreferencesKey("vonage_msg_api_secret_present"))

    suspend fun setBridgeSecret(v: String)    =
        setSecret(SecretKeys.BRIDGE_SECRET, v, BRIDGE_SECRET)

    /** Wipe all secrets — called from SettingsViewModel.clearAllData() */
    suspend fun clearSecrets() {
        encryptedPrefs.edit().clear().apply()
        context.plainDataStore.edit { prefs ->
            listOf(GOOGLE_GENAI_API_KEY, GOOGLE_OAUTH_TOKEN, GITHUB_PAT,
                   VONAGE_MSG_API_KEY, BRIDGE_SECRET)
                .forEach { prefs.remove(it) }
        }
    }

    suspend fun clearAll() {
        clearSecrets()
        context.plainDataStore.edit { it.clear() }
    }
}

