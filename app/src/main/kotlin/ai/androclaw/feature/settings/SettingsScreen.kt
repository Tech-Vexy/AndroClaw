package ai.androclaw.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.androclaw.feature.onboarding.*
import org.koin.androidx.compose.koinViewModel

private val BgColor      = Color(0xFF0D1117)
private val SurfaceColor = Color(0xFF1A1F2E)
private val ClawBlue     = Color(0xFF6C9EFF)
private val TextPrimary  = Color(0xFFE6EDF3)
private val TextSecondary = Color(0xFF8B949E)
private val DangerRed    = Color(0xFFFF5555)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel = koinViewModel(),
    onBack: () -> Unit,
    onOpenDevicePerms: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = TextPrimary)
                    }
                },
                actions = {
                    TextButton(onClick = vm::save) {
                        Text("Save", color = ClawBlue, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceColor),
            )
        },
        containerColor = BgColor,
        snackbarHost = {
            state.savedMessage?.let { msg ->
                Snackbar(
                    modifier         = Modifier.padding(16.dp),
                    containerColor   = Color(0xFF238636),
                    contentColor     = TextPrimary,
                ) { Text(msg) }
            }
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ClawBlue)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {

            // ── Identity ──────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Person, title = "Identity") {
                ConfigTextField(state.userName, vm::setUserName, "Your name", leadingIcon = Icons.Default.Person)
                Spacer(Modifier.height(10.dp))
                Text("Language", fontSize = 13.sp, color = TextSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LanguageChip("🇰🇪  Kiswahili", state.language == "sw", { vm.setLanguage("sw") }, Modifier.weight(1f))
                    LanguageChip("🇬🇧  English",   state.language == "en", { vm.setLanguage("en") }, Modifier.weight(1f))
                }
            }

            // ── Gemini LLM ────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.AutoAwesome, title = "Gemini (LLM)") {
                SecretTextField(
                    value         = state.googleGenAiKey,
                    onValueChange = vm::setGoogleGenAiKey,
                    label         = "Google GenAI API Key",
                    leadingIcon   = Icons.Default.VpnKey,
                    isValid       = if (state.googleGenAiKey.isBlank()) null else state.googleGenAiKey.isNotEmpty()
                )
            }

            // ── AgentPhone ────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Phone, title = "AgentPhone") {
                SecretTextField(
                    value         = state.agentPhoneApiKey,
                    onValueChange = vm::setAgentPhoneApiKey,
                    label         = "AgentPhone API Key",
                    leadingIcon   = Icons.Default.VpnKey,
                    isValid       = if (state.agentPhoneApiKey.isBlank()) null
                                    else state.agentPhoneApiKey.startsWith("ap_"),
                )
                if (state.agentPhoneApiKey.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "26 tools active: buy numbers, make calls, read SMS, manage voice agents.",
                        color = Color(0xFF3FB950), fontSize = 11.sp,
                    )
                }
            }

            // ── Voice ─────────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Mic, title = "Voice") {
                SecretTextField(state.deepgramKey,  vm::setDeepgramKey,  "Deepgram API Key",  Icons.Default.Hearing)
                Spacer(Modifier.height(10.dp))
                SecretTextField(state.cartesiaKey,  vm::setCartesiaKey,  "Cartesia API Key",  Icons.Default.RecordVoiceOver)
                Spacer(Modifier.height(12.dp))
                SettingsToggle(
                    label    = "Auto-speak responses",
                    subtitle = "Agent reads assistant replies aloud",
                    checked  = state.autoSpeak,
                    onCheckedChange = vm::setAutoSpeak,
                )
            }

            // ── Google Workspace ──────────────────────────────────────────
            SettingsSection(icon = Icons.Default.AccountCircle, title = "Google (Gmail · Calendar · Drive)") {
                SecretTextField(
                    value         = state.googleOAuthToken,
                    onValueChange = vm::setGoogleOAuthToken,
                    label         = "Google OAuth Token",
                    leadingIcon   = Icons.Default.VpnKey,
                    isValid       = if (state.googleOAuthToken.isBlank()) null else state.googleOAuthToken.startsWith("ya29."),
                )
            }

            // ── GitHub ────────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Code, title = "GitHub") {
                SecretTextField(
                    value         = state.githubPat,
                    onValueChange = vm::setGithubPat,
                    label         = "Personal Access Token",
                    leadingIcon   = Icons.Default.VpnKey,
                    isValid       = if (state.githubPat.isBlank()) null
                                    else state.githubPat.startsWith("ghp_") || state.githubPat.startsWith("github_pat_"),
                )
            }


            // ── Linear ────────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.LinearScale, title = "Linear") {
                SecretTextField(
                    value         = state.linearApiKey,
                    onValueChange = vm::setLinearApiKey,
                    label         = "API Key (lin_api_...)",
                    leadingIcon   = Icons.Default.VpnKey,
                    isValid       = if (state.linearApiKey.isBlank()) null else state.linearApiKey.startsWith("lin_api_"),
                )
            }


            // ── Telephony & WhatsApp (Vonage) ─────────────────────────────
            SettingsSection(icon = Icons.Default.Phone, title = "Telephony & WhatsApp (Vonage)") {
                SecretTextField(state.vonageMsgApiKey,    vm::setVonageMsgApiKey,    "API Key",     Icons.Default.VpnKey)
                Spacer(Modifier.height(10.dp))
                SecretTextField(state.vonageMsgApiSecret, vm::setVonageMsgApiSecret, "API Secret",  Icons.Default.Lock)
                Spacer(Modifier.height(10.dp))
                ConfigTextField(state.vonageMsgFromNumber, vm::setVonageMsgFromNumber, "From Number (E.164)",
                    placeholder = "+254700000000", leadingIcon = Icons.Default.PhoneAndroid)
                Spacer(Modifier.height(14.dp))
                SettingsToggle("Sandbox Mode", "Use Vonage Messages API Sandbox", state.vonageMsgSandbox, vm::setVonageMsgSandbox)
            }


            // ── Gateway ───────────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Cloud, title = "Gateway") {
                ConfigTextField(
                    value         = state.gatewayUrl,
                    onValueChange = vm::setGatewayUrl,
                    label         = "Gateway Base URL",
                    placeholder   = "https://openclaw-gateway-xxxx.run.app",
                    leadingIcon   = Icons.Default.Cloud,
                    isValid       = if (state.gatewayUrl.isBlank()) null else state.gatewayUrl.startsWith("https://"),
                )
            }

            // ── Device capabilities ───────────────────────────────────────
            onOpenDevicePerms?.let { navigate ->
                SettingsSection(icon = Icons.Default.PhoneAndroid, title = "Device Capabilities") {
                    Text(
                        "Manage Device Admin, Accessibility, Notification Listener, VPN, and Usage Access.",
                        color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = navigate,
                        border  = androidx.compose.foundation.BorderStroke(1.dp, ClawBlue.copy(0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Manage device permissions", color = ClawBlue, fontSize = 13.sp)
                    }
                }
            }

            // ── Danger zone ───────────────────────────────────────────────
            SettingsSection(icon = Icons.Default.Warning, title = "Danger Zone", headerColor = DangerRed) {
                OutlinedButton(
                    onClick = vm::clearAllData,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                    border  = androidx.compose.foundation.BorderStroke(1.dp, DangerRed.copy(0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset onboarding & clear config")
                }
            }

            // Save button (bottom, duplicates toolbar for thumb reach)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick  = vm::save,
                colors   = ButtonDefaults.buttonColors(containerColor = ClawBlue),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(Icons.Default.Save, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save Settings", fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun SettingsSection(
    icon: ImageVector,
    title: String,
    headerColor: Color = ClawBlue,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color  = SurfaceColor,
        shape  = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                Icon(icon, null, tint = headerColor, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            }
            HorizontalDivider(color = TextSecondary.copy(0.1f), modifier = Modifier.padding(bottom = 14.dp))
            content()
        }
    }
}

@Composable
fun SettingsToggle(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label,    color = TextPrimary,   fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(checkedThumbColor = ClawBlue),
        )
    }
}

