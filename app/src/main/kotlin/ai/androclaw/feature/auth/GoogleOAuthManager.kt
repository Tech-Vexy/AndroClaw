package ai.androclaw.feature.auth

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import ai.androclaw.data.prefs.ConfigStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Manages Google OAuth sign-in and access token lifecycle.
 *
 * Scopes requested:
 *   - Gmail API (readonly + send)
 *   - Google Calendar API (readonly + write)
 *   - Google Drive API (readonly + write)
 *
 * The access token is:
 *   1. Fetched fresh on sign-in
 *   2. Stored in DataStore (ConfigStore.GOOGLE_OAUTH_TOKEN)
 *   3. Refreshed automatically via AccountManager when expired
 *   4. Read by ConfigBridge → OpenClawConfig.googleOAuthToken at agent init
 *
 * Usage in MainActivity:
 *   val googleAuth = GoogleOAuthManager(this, configStore)
 *   googleAuth.registerLauncher(this)  // call in onCreate, before setContent
 *   googleAuth.signIn()               // call from UI button
 */
class GoogleOAuthManager(
    private val context: Context,
    private val configStore: ConfigStore,
) {
    // OAuth scopes for all three Google Workspace MCP servers
    private val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.modify",
        "https://www.googleapis.com/auth/calendar",
        "https://www.googleapis.com/auth/drive",
    )

    private val _signInState = MutableStateFlow<SignInState>(SignInState.Unknown)
    val signInState: StateFlow<SignInState> = _signInState

    private var launcher: ActivityResultLauncher<Intent>? = null
    private var googleSignInClient: GoogleSignInClient? = null

    fun registerLauncher(activity: AppCompatActivity) {
        val scopes = SCOPES.map { Scope(it) }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scopes.first(), *scopes.drop(1).toTypedArray())
            .build()

        googleSignInClient = GoogleSignIn.getClient(activity, options)

        launcher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                handleSignInResult(task)
            } else {
                Timber.w("Google Sign-In cancelled (resultCode=${result.resultCode})")
                _signInState.value = SignInState.SignedOut
            }
        }

        // Check if already signed in
        val existing = GoogleSignIn.getLastSignedInAccount(context)
        if (existing != null) {
            _signInState.value = SignInState.SignedIn(existing.email ?: "")
            refreshTokenAsync(existing)
        } else {
            _signInState.value = SignInState.SignedOut
        }
    }

    fun signIn() {
        val client = googleSignInClient ?: run {
            Timber.e("GoogleOAuthManager.registerLauncher() not called")
            return
        }
        launcher?.launch(client.signInIntent)
    }

    fun signOut() {
        googleSignInClient?.signOut()?.addOnCompleteListener {
            _signInState.value = SignInState.SignedOut
            kotlinx.coroutines.GlobalScope.launch {
                configStore.set(ConfigStore.GOOGLE_OAUTH_TOKEN, "")
            }
        }
    }

    /**
     * Get a fresh access token. Uses AccountManager for automatic refresh.
     * Returns null if not signed in.
     */
    suspend fun getFreshToken(): String? = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        val email   = account.email ?: return@withContext null

        try {
            val androidAccount = Account(email, "com.google")
            val scope  = "oauth2:${SCOPES.joinToString(" ")}"
            val token  = GoogleAuthUtil.getToken(context, androidAccount, scope)
            // Store the fresh token
            configStore.set(ConfigStore.GOOGLE_OAUTH_TOKEN, token)
            token
        } catch (e: Exception) {
            Timber.e(e, "Google token refresh failed")
            // Token expired and can't be refreshed — trigger re-sign-in
            _signInState.value = SignInState.TokenExpired
            null
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun handleSignInResult(task: com.google.android.gms.tasks.Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            val email   = account?.email ?: ""
            Timber.d("Google Sign-In success: $email")
            _signInState.value = SignInState.SignedIn(email)
            refreshTokenAsync(account)
        } catch (e: ApiException) {
            Timber.e(e, "Google Sign-In failed: ${e.statusCode}")
            _signInState.value = SignInState.Error("Sign-in failed: ${e.statusCode}")
        }
    }

    private fun refreshTokenAsync(account: GoogleSignInAccount?) {
        if (account == null) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            getFreshToken()
        }
    }
}

// ── Sign-in state ─────────────────────────────────────────────────────────────

sealed class SignInState {
    data object Unknown     : SignInState()
    data object SignedOut   : SignInState()
    data object TokenExpired : SignInState()
    data class  SignedIn(val email: String) : SignInState()
    data class  Error(val message: String)  : SignInState()
}

