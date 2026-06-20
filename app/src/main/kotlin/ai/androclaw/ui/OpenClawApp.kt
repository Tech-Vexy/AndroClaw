package ai.androclaw.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.feature.chat.ChatMessage
import ai.androclaw.feature.chat.ChatViewModel
import ai.androclaw.feature.chat.Role
import ai.androclaw.feature.onboarding.OnboardingScreen
import ai.androclaw.feature.settings.SettingsScreen
import ai.androclaw.feature.auth.AuthScreen
import ai.androclaw.feature.auth.FirebaseAuthManager
import ai.androclaw.feature.telephony.VoiceViewModel
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.androidx.compose.koinViewModel
import ai.androclaw.ui.permissions.PermissionGate
import ai.androclaw.ui.permissions.PermissionGroups
import ai.androclaw.feature.device.DevicePermissionsScreen
import ai.androclaw.feature.telephony.CallDirection
import ai.androclaw.feature.telephony.CallStateManager
import ai.androclaw.feature.telephony.CallStatus
import ai.androclaw.feature.telephony.LiveCall
import org.koin.androidx.compose.get as koinGet

// ── Nav routes ────────────────────────────────────────────────────────────────
object Routes {
    const val AUTH              = "auth"
    const val ONBOARDING        = "onboarding"
    const val CHAT              = "chat"
    const val SETTINGS          = "settings"
    const val DEVICE_PERMISSIONS = "device_permissions"
}

@Composable
fun OpenClawApp() {
    MaterialTheme(colorScheme = openClawColorScheme()) {
        val context    = LocalContext.current
        val navController = rememberNavController()

        // Determine start destination from DataStore
        val authManager = koinGet<FirebaseAuthManager>()
        val startDest = remember {
            if (!authManager.isUserLoggedIn) {
                Routes.AUTH
            } else {
                val store = ConfigStore(context)
                val done  = runBlocking { store.onboardingDone().first() }
                if (done) Routes.CHAT else Routes.ONBOARDING
            }
        }

        NavHost(navController = navController, startDestination = startDest) {
            composable(Routes.AUTH) {
                AuthScreen(onAuthSuccess = {
                    val store = ConfigStore(context)
                    val done = runBlocking { store.onboardingDone().first() }
                    val nextRoute = if (done) Routes.CHAT else Routes.ONBOARDING
                    navController.navigate(nextRoute) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                })
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinished = {
                    navController.navigate(Routes.CHAT) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                })
            }
            composable(Routes.CHAT) {
                PermissionGate(
                    permissions = PermissionGroups.ALL_CORE,
                ) {
                    ChatScreen(
                        onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                    )
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack              = { navController.popBackStack() },
                    onOpenDevicePerms   = { navController.navigate(Routes.DEVICE_PERMISSIONS) },
                )
            }
            composable(Routes.DEVICE_PERMISSIONS) {
                DevicePermissionsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

// ── Chat screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel       = koinViewModel(),
    voiceVm: VoiceViewModel = koinViewModel(),
    onOpenSettings: () -> Unit,
) {
    val state         by vm.uiState.collectAsState()
    val isListening   by voiceVm.isListening.collectAsState()
    val isSpeaking    by voiceVm.isSpeaking.collectAsState()
    val listState     = rememberLazyListState()
    val callManager   = koinGet<CallStateManager>()
    val activeCalls   by callManager.activeCalls.collectAsState()
    val activeCall    = activeCalls.values.firstOrNull {
        it.status in setOf(
            CallStatus.RINGING,
            CallStatus.ANSWERED,
        )
    }

    LaunchedEffect(Unit) {
        voiceVm.transcriptions.collect { text -> vm.sendVoice(text) }
    }
    LaunchedEffect(state.messages) {
        val last = state.messages.lastOrNull()
        if (last?.role == Role.ASSISTANT && !last.isStreaming && last.text.isNotBlank()) {
            voiceVm.speak(last.text)
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (isSpeaking) {
                        IconButton(onClick = voiceVm::stopSpeaking) {
                            Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Active call banner ─────────────────────────────────────
            activeCall?.let { call ->
                ActiveCallBanner(
                    call    = call,
                    onHangUp = {
                        vm.send("telephony_end_call for call ${call.uuid}")
                    },
                )
            }

            LazyColumn(
                state       = listState,
                modifier    = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg -> MessageBubble(msg) }
                if (state.isThinking) { item { ThinkingIndicator() } }
            }
            state.error?.let { err ->
                Surface(
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(8.dp)),
                ) {
                    Row(Modifier.padding(12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                    }
                }
            }
            InputBar(
                text         = state.inputText,
                onTextChange = vm::onInputChange,
                onSend       = vm::send,
                onVoice      = voiceVm::toggleListening,
                isListening  = isListening,
                isThinking   = state.isThinking,
            )
        }
    }
}

@Composable
fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart    = if (isUser) 16.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp,
            ),
            color    = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text  = msg.text,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp,
                )
                if (msg.isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(1.dp),
                        color    = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingIndicator() {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(3) { i ->
                    val alpha by animateFloatAsState(if (i % 2 == 0) 1f else 0.3f, label = "dot_$i")
                    Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
                }
            }
        }
    }
}

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    isListening: Boolean = false,
    isThinking: Boolean,
) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val micTint by animateColorAsState(
                if (isListening) Color(0xFFE53935) else MaterialTheme.colorScheme.primary, label = "mic_color")
            IconButton(onClick = onVoice) {
                Icon(
                    imageVector        = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (isListening) "Stop listening" else "Voice input",
                    tint               = micTint,
                )
            }
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                placeholder   = { Text(if (isListening) "Nasikia…" else "Niambie…") },
                modifier      = Modifier.weight(1f),
                maxLines      = 4,
                shape         = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            FilledIconButton(onClick = onSend, enabled = text.isNotBlank() && !isThinking) {
                Icon(if (isThinking) Icons.Default.Stop else Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun openClawColorScheme() = darkColorScheme(
    primary          = Color(0xFF6C9EFF),
    onPrimary        = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1E3A5F),
    surface          = Color(0xFF0D1117),
    surfaceVariant   = Color(0xFF1A1F2E),
    onSurfaceVariant = Color(0xFFCDD5E0),
    background       = Color(0xFF0D1117),
)

@Composable
private fun animateFloatAsState(targetValue: Float, label: String) =
    androidx.compose.animation.core.animateFloatAsState(targetValue, label = label)

// ── Active call banner ────────────────────────────────────────────────────────

@Composable
fun ActiveCallBanner(
    call: LiveCall,
    onHangUp: () -> Unit,
) {
    val bg = if (call.status == CallStatus.RINGING)
        Color(0xFFE65100) else Color(0xFF1B5E20)
    val label = if (call.status == CallStatus.RINGING)
        "Ringing" else "In call"
    val direction = if (call.direction == CallDirection.INBOUND)
        "← Inbound" else "Outbound →"

    Surface(color = bg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("$label · ${call.displayName}", color = Color.White,
                    fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("$direction · ${call.durationSeconds}s",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // End call
                Surface(
                    color    = Color(0xFFB71C1C),
                    shape    = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = Modifier.clickable(onClick = onHangUp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.CallEnd, null,
                            tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("End", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

