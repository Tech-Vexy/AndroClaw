package ai.androclaw.feature.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import ai.androclaw.feature.auth.GoogleOAuthManager
import ai.androclaw.feature.auth.SignInState
import org.koin.androidx.compose.get as koinGet
import ai.androclaw.feature.auth.GoogleSignInButton

// ── Colour palette (mirrors openClawColorScheme) ──────────────────────────────
private val BgColor      = Color(0xFF0D1117)
private val SurfaceColor = Color(0xFF1A1F2E)
private val ClawBlue     = Color(0xFF6C9EFF)
private val ClawBlue2    = Color(0xFF4A7FE0)
private val TextPrimary  = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val SuccessGreen = Color(0xFF3FB950)
private val WarnOrange   = Color(0xFFD29922)

@Composable
fun OnboardingScreen(
    vm: OnboardingViewModel = koinViewModel(),
    onFinished: () -> Unit,
) {
    val state by vm.state.collectAsState()

    LaunchedEffect(state.step) {
        if (state.step == OnboardingStep.DONE) onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Progress bar ─────────────────────────────────────────────
            StepProgressBar(
                current = OnboardingStep.entries.indexOf(state.step),
                total   = OnboardingStep.entries.size - 1,   // exclude DONE
            )

            // ── Step content ─────────────────────────────────────────────
            AnimatedContent(
                targetState = state.step,
                transitionSpec = {
                    slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
                },
                modifier = Modifier.weight(1f),
                label    = "step_transition",
            ) { step ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    when (step) {
                        OnboardingStep.WELCOME      -> WelcomeStep(state, vm)
                        OnboardingStep.GOOGLE_GENAI -> GoogleGenAiStep(state, vm)
                        OnboardingStep.AGENTPHONE   -> AgentPhoneStep(state, vm)
                        OnboardingStep.VOICE        -> VoiceStep(state, vm)
                        OnboardingStep.GOOGLE       -> GoogleStep(state, vm)
                        OnboardingStep.GITHUB       -> GitHubStep(state, vm)
                        OnboardingStep.WHATSAPP     -> WhatsAppStep(state, vm)
                        OnboardingStep.GATEWAY      -> GatewayStep(state, vm)
                        OnboardingStep.DONE         -> {}
                    }
                }
            }

            // ── Bottom nav ───────────────────────────────────────────────
            BottomNavBar(state = state, vm = vm)
        }

        // Loading overlay
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = ClawBlue)
            }
        }
    }
}

// ── Step: Welcome ─────────────────────────────────────────────────────────────

@Composable
fun WelcomeStep(state: OnboardingState, vm: OnboardingViewModel) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Logo / icon area
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    Brush.radialGradient(listOf(ClawBlue.copy(0.3f), Color.Transparent)),
                    RoundedCornerShape(48.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint               = ClawBlue,
                modifier           = Modifier.size(52.dp),
            )
        }

        Text(
            "Karibu OpenClaw",
            fontSize   = 28.sp,
            fontWeight = FontWeight.Bold,
            color      = TextPrimary,
            textAlign  = TextAlign.Center,
        )
        Text(
            "Msaidizi wako wa AI binafsi.\nHebu tuanze na maelezo yako.",
            fontSize  = 15.sp,
            color     = TextSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )

        Spacer(Modifier.height(8.dp))

        // Name input
        ConfigTextField(
            value       = state.userName,
            onValueChange = vm::setUserName,
            label       = "Jina lako / Your name",
            placeholder = "e.g. Kamau",
            leadingIcon = Icons.Default.Person,
        )

        // Language selector
        Text("Lugha / Language", fontSize = 13.sp, color = TextSecondary,
            modifier = Modifier.fillMaxWidth())
        Row(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageChip(
                label    = "🇰🇪  Kiswahili",
                selected = state.language == "sw",
                onClick  = { vm.setLanguage("sw") },
                modifier = Modifier.weight(1f),
            )
            LanguageChip(
                label    = "🇬🇧  English",
                selected = state.language == "en",
                onClick  = { vm.setLanguage("en") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Step: Google GenAI ────────────────────────────────────────────────────────

@Composable
fun GoogleGenAiStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Key,
        title       = "Gemini API Key",
        description = "OpenClaw uses Gemini as its brain. Get your key from Google AI Studio.",
        helpUrl     = "https://aistudio.google.com/app/apikey",
        helpLabel   = "Get API key →",
    ) {
        SecretTextField(
            value         = state.googleGenAiKey,
            onValueChange = vm::setGoogleGenAiKey,
            label         = "Google GenAI API Key",
            placeholder   = "AIzaSy...",
            leadingIcon   = Icons.Default.VpnKey,
            isValid       = state.googleGenAiKey.isNotBlank(),
        )
    }
}

// ── Step: Voice ───────────────────────────────────────────────────────────────

@Composable
fun VoiceStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Mic,
        title       = "Voice",
        description = "Deepgram handles speech-to-text (Swahili supported). Cartesia handles text-to-speech.",
    ) {
        SecretTextField(
            value         = state.deepgramKey,
            onValueChange = vm::setDeepgramKey,
            label         = "Deepgram API Key",
            placeholder   = "Paste from console.deepgram.com",
            leadingIcon   = Icons.Default.Hearing,
            isValid       = state.deepgramKey.length > 10,
        )
        Spacer(Modifier.height(12.dp))
        SecretTextField(
            value         = state.cartesiaKey,
            onValueChange = vm::setCartesiaKey,
            label         = "Cartesia API Key",
            placeholder   = "Paste from play.cartesia.ai",
            leadingIcon   = Icons.Default.RecordVoiceOver,
            isValid       = state.cartesiaKey.length > 10,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("Auto-speak responses", color = TextPrimary, fontSize = 14.sp)
                Text("Agent reads replies aloud", color = TextSecondary, fontSize = 12.sp)
            }
            Switch(
                checked         = state.autoSpeak,
                onCheckedChange = vm::setAutoSpeak,
                colors          = SwitchDefaults.colors(checkedThumbColor = ClawBlue),
            )
        }
    }
}

// ── Step: WhatsApp ────────────────────────────────────────────────────────────

@Composable
fun WhatsAppStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Chat,
        title       = "WhatsApp (Vonage)",
        description = "Allows the agent to send and read WhatsApp messages via Vonage Messages API. Optional.",
        isOptional  = true,
    ) {
        SecretTextField(
            value         = state.vonageMsgApiKey,
            onValueChange = vm::setVonageMsgApiKey,
            label         = "Vonage API Key",
            leadingIcon   = Icons.Default.VpnKey
        )
        Spacer(Modifier.height(10.dp))
        SecretTextField(
            value         = state.vonageMsgApiSecret,
            onValueChange = vm::setVonageMsgApiSecret,
            label         = "Vonage API Secret",
            leadingIcon   = Icons.Default.Lock
        )
        Spacer(Modifier.height(10.dp))
        ConfigTextField(
            value         = state.vonageMsgFromNumber,
            onValueChange = vm::setVonageMsgFromNumber,
            label         = "From Number (E.164)",
            placeholder   = "+14157386102",
            leadingIcon   = Icons.Default.PhoneAndroid,
            keyboardType  = KeyboardType.Phone,
        )
    }
}




// ── Step: Gateway ─────────────────────────────────────────────────────────────

@Composable
fun GatewayStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Cloud,
        title       = "Gateway URL",
        description = "Your AndroClaw gateway running on Render. Deploy using the Render Blueprint (render.yaml) then paste the base URL here.",
    ) {
        ConfigTextField(
            value         = state.gatewayUrl,
            onValueChange = vm::setGatewayUrl,
            label         = "Gateway Base URL",
            placeholder   = "https://openclaw-gateway-xxxx-ew.a.run.app",
            leadingIcon   = Icons.Default.Cloud,
            keyboardType  = KeyboardType.Uri,
            isValid       = state.gatewayUrl.startsWith("https://"),
        )
        Spacer(Modifier.height(12.dp))
        InfoCard(
            icon    = Icons.Default.Check,
            message = "The gateway hosts your email proxy and Vonage MCP server.",
            color   = SuccessGreen.copy(alpha = 0.12f),
        )
    }
}

// ── Bottom nav bar ─────────────────────────────────────────────────────────────

@Composable
fun BottomNavBar(state: OnboardingState, vm: OnboardingViewModel) {
    val isOptional = state.step in listOf(
        OnboardingStep.AGENTPHONE,
        OnboardingStep.GOOGLE,
        OnboardingStep.GITHUB,
        OnboardingStep.WHATSAPP,
    )
    val isLast = state.step == OnboardingStep.GATEWAY

    Surface(tonalElevation = 4.dp, color = SurfaceColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            // Back
            if (state.step != OnboardingStep.WELCOME) {
                TextButton(onClick = vm::back) {
                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Back", color = TextSecondary)
                }
            } else {
                Spacer(Modifier.width(80.dp))
            }

            // Skip (optional steps)
            if (isOptional) {
                TextButton(onClick = vm::skip) {
                    Text("Skip", color = TextSecondary)
                }
            } else {
                Spacer(Modifier.width(60.dp))
            }

            // Next / Finish
            Button(
                onClick  = if (isLast) vm::finish else vm::next,
                enabled  = state.canProceed,
                colors   = ButtonDefaults.buttonColors(containerColor = ClawBlue),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Text(if (isLast) "Finish" else "Next")
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (isLast) Icons.Default.Check else Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

// ── Reusable components ───────────────────────────────────────────────────────

@Composable
fun StepProgressBar(current: Int, total: Int) {
    LinearProgressIndicator(
        progress    = { (current.toFloat() / total).coerceIn(0f, 1f) },
        modifier    = Modifier.fillMaxWidth().height(3.dp),
        color       = ClawBlue,
        trackColor  = SurfaceColor,
    )
}

@Composable
fun StepScaffold(
    icon: ImageVector,
    title: String,
    description: String,
    helpUrl: String? = null,
    helpLabel: String? = null,
    isOptional: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = ClawBlue, modifier = Modifier.size(28.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    if (isOptional) {
                        Surface(color = ClawBlue.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                            Text("Optional", fontSize = 10.sp, color = ClawBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(description, fontSize = 13.sp, color = TextSecondary, lineHeight = 18.sp)
            }
        }
        if (helpUrl != null && helpLabel != null) {
            Text(
                helpLabel,
                color    = ClawBlue,
                fontSize = 13.sp,
                modifier = Modifier.clickable { /* open URL via Intent */ },
            )
        }
        HorizontalDivider(color = SurfaceColor)
        content()
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isValid: Boolean? = null,
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 13.sp) },
        placeholder   = { Text(placeholder, color = TextSecondary, fontSize = 13.sp) },
        leadingIcon   = leadingIcon?.let { { Icon(it, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) } },
        trailingIcon  = when (isValid) {
            true  -> { { Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(18.dp)) } }
            false -> { { Icon(Icons.Default.Cancel, null, tint = Color(0xFFFF5555), modifier = Modifier.size(18.dp)) } }
            null  -> null
        },
        modifier       = Modifier.fillMaxWidth(),
        singleLine     = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors         = outlinedTextFieldColors(),
    )
}

@Composable
fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    leadingIcon: ImageVector? = null,
    placeholder: String = "",
    isValid: Boolean? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 13.sp) },
        placeholder   = { Text(placeholder, color = TextSecondary, fontSize = 13.sp) },
        leadingIcon   = leadingIcon?.let { { Icon(it, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) } },
        trailingIcon  = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (isValid == true) {
                    Icon(Icons.Default.CheckCircle, null, tint = SuccessGreen, modifier = Modifier.size(18.dp).padding(end = 8.dp))
                }
            }
        },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        modifier       = Modifier.fillMaxWidth(),
        singleLine     = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors         = outlinedTextFieldColors(),
    )
}

@Composable
fun LanguageChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier  = modifier.clickable(onClick = onClick),
        shape     = RoundedCornerShape(10.dp),
        color     = if (selected) ClawBlue.copy(alpha = 0.2f) else SurfaceColor,
        border    = if (selected)
            androidx.compose.foundation.BorderStroke(1.5.dp, ClawBlue)
        else
            androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(0.2f)),
    ) {
        Text(
            label,
            color    = if (selected) ClawBlue else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun InfoCard(icon: ImageVector, message: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(message, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
fun outlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = ClawBlue,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.25f),
    focusedLabelColor    = ClawBlue,
    unfocusedLabelColor  = TextSecondary,
    cursorColor          = ClawBlue,
    focusedTextColor     = TextPrimary,
    unfocusedTextColor   = TextPrimary,
)

// ── Step: Google OAuth ────────────────────────────────────────────────────────

@Composable
fun GoogleStep(state: OnboardingState, vm: OnboardingViewModel) {
    val googleAuth = koinGet<GoogleOAuthManager>()
    val signInState by googleAuth.signInState.collectAsState()

    // Sync sign-in state into onboarding state
    LaunchedEffect(signInState) {
        if (signInState is SignInState.SignedIn) {
            // Token is stored in DataStore automatically by GoogleOAuthManager
            vm.setGoogleOAuthToken("connected")
        }
    }

    StepScaffold(
        icon        = Icons.Default.AccountCircle,
        title       = "Google (Gmail · Calendar · Drive)",
        description = "Connect your Google account to give OpenClaw access to Gmail, Calendar, and Drive.",
        isOptional  = true,
    ) {
        GoogleSignInButton(
            state    = signInState,
            onSignIn = { googleAuth.signIn() },
            onSignOut = { googleAuth.signOut(); vm.setGoogleOAuthToken("") },
        )
        if (signInState is SignInState.SignedIn) {
            Spacer(Modifier.height(8.dp))
            InfoCard(
                icon    = Icons.Default.Check,
                message = "Gmail, Google Calendar, and Google Drive are now accessible to the agent.",
                color   = SuccessGreen.copy(alpha = 0.12f),
            )
        }
    }
}

// ── Step: GitHub ──────────────────────────────────────────────────────────────

@Composable
fun GitHubStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Code,
        title       = "GitHub",
        description = "Access issues, pull requests, notifications, and code search via GitHub's official MCP server.",
        helpUrl     = "https://github.com/settings/tokens",
        helpLabel   = "Create Personal Access Token →",
        isOptional  = true,
    ) {
        SecretTextField(
            value         = state.githubPat,
            onValueChange = vm::setGithubPat,
            label         = "GitHub Personal Access Token",
            placeholder   = "ghp_...",
            leadingIcon   = Icons.Default.VpnKey,
            isValid       = state.githubPat.startsWith("ghp_") || state.githubPat.startsWith("github_pat_"),
        )
        Spacer(Modifier.height(12.dp))
        InfoCard(
            icon    = Icons.Default.Info,
            message = "Required scopes: repo, issues, notifications, read:user. Use a fine-grained token for least-privilege access.",
            color   = SurfaceColor,
        )
    }
}







// ── Step: AgentPhone ──────────────────────────────────────────────────────────

@Composable
fun AgentPhoneStep(state: OnboardingState, vm: OnboardingViewModel) {
    StepScaffold(
        icon        = Icons.Default.Phone,
        title       = "AgentPhone",
        description = "Give your agent a real phone number — outbound calls, inbound calls, and SMS. " +
                      "No Twilio or SIP config needed. Get a key at agentphone.ai.",
        helpUrl     = "https://agentphone.ai/settings",
        helpLabel   = "Get API key →",
        isOptional  = true,
    ) {
        SecretTextField(
            value         = state.agentPhoneApiKey,
            onValueChange = vm::setAgentPhoneApiKey,
            label         = "AgentPhone API Key",
            placeholder   = "ap_live_...",
            leadingIcon   = Icons.Default.VpnKey,
            isValid       = state.agentPhoneApiKey.startsWith("ap_"),
        )
        Spacer(Modifier.height(12.dp))
        InfoCard(
            icon    = Icons.Default.Info,
            message = "Once connected the agent can: buy a phone number, make AI voice calls " +
                      "(make_conversation_call), send/read SMS, and list recent calls — " +
                      "all via natural language. 26 tools total.",
            color   = ClawBlue.copy(alpha = 0.12f),
        )
        Spacer(Modifier.height(8.dp))
        InfoCard(
            icon    = Icons.Default.Check,
            message = "After onboarding, deploy the agentphone-webhook gateway service and " +
                      "register the webhook URL in your AgentPhone dashboard to enable inbound calls.",
            color   = SuccessGreen.copy(alpha = 0.10f),
        )
    }
}

