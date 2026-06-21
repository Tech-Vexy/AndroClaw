package ai.androclaw.feature.auth

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import org.koin.androidx.compose.koinViewModel
import timber.log.Timber

private val BgColor = Color(0xFF0D1117)
private val CardBgColor = Color(0xFF161B22)
private val ClawBlue = Color(0xFF58A6FF)
private val ClawPurple = Color(0xFFBC8CFF)
private val TextPrimary = Color(0xFFF0F6FC)
private val TextSecondary = Color(0xFF8B949E)
private val BorderColor = Color(0xFF30363D)

enum class AuthTab {
    EMAIL, GOOGLE, PHONE
}

@Composable
fun AuthScreen(
    vm: AuthViewModel = koinViewModel(),
    onAuthSuccess: () -> Unit
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTab by remember { mutableStateFlowOf(AuthTab.EMAIL) }

    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) {
            onAuthSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        // Decorative background gradient circles
        Box(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopEnd)
                .offset(x = 100.dp, y = (-80).dp)
                .background(Brush.radialGradient(listOf(ClawPurple.copy(alpha = 0.15f), Color.Transparent)), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(350.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-120).dp, y = 100.dp)
                .background(Brush.radialGradient(listOf(ClawBlue.copy(alpha = 0.15f), Color.Transparent)), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Title and Subtitle
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = ClawBlue,
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "AndroClaw",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = "Sign in to connect your personal assistant",
                fontSize = 14.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 32.dp)
            )

            // Glassmorphic Card Container
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderColor, RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = CardBgColor,
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Tab Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(BgColor)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AuthTab.entries.forEach { tab ->
                            val selected = selectedTab == tab
                            val bg by animateColorAsState(if (selected) CardBgColor else Color.Transparent)
                            val textCol by animateColorAsState(if (selected) TextPrimary else TextSecondary)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(bg)
                                    .clickable { selectedTab = tab },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (tab) {
                                        AuthTab.EMAIL -> "Email"
                                        AuthTab.GOOGLE -> "Google"
                                        AuthTab.PHONE -> "Phone"
                                    },
                                    color = textCol,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))

                    // Tab Contents
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = {
                            fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                        },
                        label = "auth_tab_transition"
                    ) { tab ->
                        when (tab) {
                            AuthTab.EMAIL -> EmailAuthTab(vm)
                            AuthTab.GOOGLE -> GoogleAuthTab(vm)
                            AuthTab.PHONE -> PhoneAuthTab(vm)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = { vm.bypassAuth() }
            ) {
                Text(
                    text = "Bypass Authentication (Offline Mode)",
                    color = ClawBlue,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Error Message Banner
            state.error?.let { err ->
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f), fontSize = 13.sp)
                        Icon(
                            Icons.Default.Close, null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { vm.dismissError() }
                        )
                    }
                }
            }
        }

        // Loader overlay
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ClawBlue)
            }
        }
    }
}

@Composable
fun EmailAuthTab(vm: AuthViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AuthTextField(
            value = email,
            onValueChange = { email = it },
            label = "Email Address",
            leadingIcon = Icons.Default.Email
        )

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            leadingIcon = Icons.Default.Lock,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(icon, null, tint = TextSecondary)
                }
            }
        )

        Button(
            onClick = {
                if (isSignUp) {
                    vm.signUpWithEmail(email, password)
                } else {
                    vm.signInWithEmail(email, password)
                }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClawBlue)
        ) {
            Text(if (isSignUp) "Sign Up" else "Sign In", fontWeight = FontWeight.Bold)
        }

        Text(
            text = if (isSignUp) "Already have an account? Sign in here" else "Don't have an account yet? Sign up",
            color = ClawBlue,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isSignUp = !isSignUp }
                .padding(vertical = 4.dp)
        )
    }
}

@Composable
fun GoogleAuthTab(vm: AuthViewModel) {
    val context = LocalContext.current
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken("45171591772-7390f8e1ah79ts06tn7muel6d1dielle.apps.googleusercontent.com")
        .requestEmail()
        .build()
    val client = GoogleSignIn.getClient(context, gso)

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    vm.signInWithGoogle(idToken)
                } else {
                    Timber.e("Google ID Token was null")
                }
            } catch (e: ApiException) {
                Timber.e(e, "Google Sign-In Activity result error")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "By using Google, you will be securely authenticated via Firebase and your configurations can be synced.",
            color = TextSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Button(
            onClick = { googleSignInLauncher.launch(client.signInIntent) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1F2433)),
            border = BorderStroke(1.dp, BorderColor)
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                tint = ClawBlue
            )
            Spacer(Modifier.width(10.dp))
            Text("Sign in with Google", color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PhoneAuthTab(vm: AuthViewModel) {
    val context = LocalContext.current
    val activity = context as Activity
    val state by vm.uiState.collectAsState()
    var phoneNumber by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!state.codeSent) {
            // Step 1: Input phone number
            AuthTextField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it },
                label = "Phone Number (e.g. +14155552671)",
                leadingIcon = Icons.Default.Phone,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )

            Button(
                onClick = { vm.sendSmsCode(activity, phoneNumber) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawBlue)
            ) {
                Text("Send Verification PIN (SMS)", fontWeight = FontWeight.Bold)
            }
        } else {
            // Step 2: Input verification code
            Text(
                "Verification PIN sent to: $phoneNumber",
                color = TextSecondary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            AuthTextField(
                value = smsCode,
                onValueChange = { smsCode = it },
                label = "SMS Verification PIN",
                leadingIcon = Icons.Default.Pin,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Button(
                onClick = { vm.verifySmsCode(smsCode) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClawBlue)
            ) {
                Text("Verify and Sign In", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(leadingIcon, null, tint = TextSecondary) },
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ClawBlue,
            unfocusedBorderColor = BorderColor,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedLabelColor = ClawBlue,
            unfocusedLabelColor = TextSecondary,
            focusedContainerColor = BgColor,
            unfocusedContainerColor = BgColor
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

// Multiplatform compatibility helper
private fun <T> mutableStateFlowOf(value: T) = mutableStateOf(value)
