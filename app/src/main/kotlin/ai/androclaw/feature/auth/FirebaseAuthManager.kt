package ai.androclaw.feature.auth

import android.app.Activity
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.FirebaseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.concurrent.TimeUnit

class FirebaseAuthManager(private val context: Context) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }

    val isUserLoggedIn: Boolean
        get() = auth.currentUser != null

    // ── Email/Password Authentication ───────────────────────────────────────

    suspend fun signInWithEmail(email: String, password: String): FirebaseUser {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        return result.user ?: throw Exception("Sign in failed: User is null")
    }

    suspend fun signUpWithEmail(email: String, password: String): FirebaseUser {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        return result.user ?: throw Exception("Sign up failed: User is null")
    }

    // ── Google Sign-in ──────────────────────────────────────────────────────

    suspend fun signInWithGoogle(idToken: String): FirebaseUser {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        val result = auth.signInWithCredential(credential).await()
        return result.user ?: throw Exception("Google Sign-In failed: User is null")
    }

    // ── Phone Authentication ──────────────────────────────────────────────────

    fun startPhoneAuth(
        activity: Activity,
        phoneNumber: String,
        onVerificationCompleted: (PhoneAuthCredential) -> Unit,
        onVerificationFailed: (FirebaseException) -> Unit,
        onCodeSent: (String, PhoneAuthProvider.ForceResendingToken) -> Unit
    ) {
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                Timber.d("Phone auth completed automatically")
                onVerificationCompleted(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                Timber.e(e, "Phone auth verification failed")
                onVerificationFailed(e)
            }

            override fun onCodeSent(
                verificationId: String,
                token: PhoneAuthProvider.ForceResendingToken
            ) {
                Timber.d("Phone auth SMS code sent. VerificationId: $verificationId")
                onCodeSent(verificationId, token)
            }
        }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(activity)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    suspend fun signInWithPhoneCredential(credential: PhoneAuthCredential): FirebaseUser {
        val result = auth.signInWithCredential(credential).await()
        return result.user ?: throw Exception("Phone sign-in failed: User is null")
    }

    suspend fun verifyAndSignInPhone(verificationId: String, code: String): FirebaseUser {
        val credential = PhoneAuthProvider.getCredential(verificationId, code)
        return signInWithPhoneCredential(credential)
    }

    // ── Sign Out ─────────────────────────────────────────────────────────────

    fun signOut() {
        auth.signOut()
    }
}
