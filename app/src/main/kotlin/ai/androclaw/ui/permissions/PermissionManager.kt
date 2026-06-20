package ai.androclaw.ui.permissions

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ── Permission groups ─────────────────────────────────────────────────────────

/**
 * All runtime permissions OpenClaw may need, grouped by feature.
 * Each group is requested together when the feature is first used.
 */
object PermissionGroups {

    /** Core agent — needed immediately on first launch */
    val CORE = listOf(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.RECORD_AUDIO,
    )

    /** SMS read/send — needed when telephony tools are first invoked */
    val TELEPHONY = listOfNotNull(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    /** Foreground service with microphone (Android 14+) */
    val FOREGROUND_MICROPHONE = listOfNotNull(
        if (Build.VERSION.SDK_INT >= 34)
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
        else null
    )

    /** All permissions in one list for a single-shot grant (e.g. onboarding) */
    val ALL_CORE = CORE + FOREGROUND_MICROPHONE

    /** Human-readable labels for rationale dialogs */
    val labels = mapOf(
        Manifest.permission.POST_NOTIFICATIONS  to "Notifications"       to "Required to alert you about messages and calls.",
        Manifest.permission.RECORD_AUDIO        to "Microphone"          to "Required for voice input (Swahili/English STT).",
        Manifest.permission.SEND_SMS            to "Send SMS"            to "Required to send SMS on your behalf.",
        Manifest.permission.RECEIVE_SMS         to "Receive SMS"         to "Required to detect incoming SMS and M-Pesa confirmations.",
        Manifest.permission.READ_SMS            to "Read SMS"            to "Required to read your inbox via the telephony_read_sms tool.",
        Manifest.permission.CALL_PHONE          to "Make Calls"          to "Required to place calls via the telephony_make_call tool.",
        Manifest.permission.READ_CALL_LOG       to "Call Log"            to "Required to list recent calls.",
        Manifest.permission.READ_CONTACTS       to "Contacts"            to "Required to resolve phone numbers to contact names.",
    )
}

// ── Rationale data ─────────────────────────────────────────────────────────────

data class PermissionItem(
    val permission: String,
    val label: String,
    val rationale: String,
    val icon: ImageVector,
)

private val permissionMeta = listOf(
    PermissionItem(Manifest.permission.POST_NOTIFICATIONS,
        "Notifications", "Alert you about incoming messages, calls, and M-Pesa confirmations.",
        Icons.Default.Notifications),
    PermissionItem(Manifest.permission.RECORD_AUDIO,
        "Microphone", "Voice input for Swahili and English speech-to-text.",
        Icons.Default.Mic),
    PermissionItem(Manifest.permission.SEND_SMS,
        "Send SMS", "Send text messages on your behalf via the agent.",
        Icons.Default.Send),
    PermissionItem(Manifest.permission.RECEIVE_SMS,
        "Receive SMS", "Detect incoming SMS including M-Pesa confirmations.",
        Icons.Default.Inbox),
    PermissionItem(Manifest.permission.READ_SMS,
        "Read SMS", "Let the agent read your SMS inbox when you ask.",
        Icons.Default.Message),
    PermissionItem(Manifest.permission.CALL_PHONE,
        "Phone Calls", "Place outbound calls when you ask the agent to call someone.",
        Icons.Default.Phone),
    PermissionItem(Manifest.permission.READ_CALL_LOG,
        "Call Log", "Check recent call history.",
        Icons.Default.History),
    PermissionItem(Manifest.permission.READ_CONTACTS,
        "Contacts", "Resolve phone numbers to contact names in messages.",
        Icons.Default.Contacts),
)

// ── Permission request composable ─────────────────────────────────────────────

/**
 * Full-screen permission rationale dialog.
 * Shown once before requesting the permission group.
 * Dismissed after grant or explicit denial.
 */
@Composable
fun PermissionRationaleDialog(
    permissions: List<String>,
    onGranted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val items = permissions.mapNotNull { perm ->
        permissionMeta.firstOrNull { it.permission == perm }
    }
    if (items.isEmpty()) { onGranted(); return }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) onGranted() else onDismiss()
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1A1F2E),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Permissions needed",
                    color      = Color(0xFFE6EDF3),
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "OpenClaw needs the following permissions to work. " +
                    "You can change these later in Settings → Apps → OpenClaw.",
                    color    = Color(0xFF8B949E),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )

                items.forEach { item ->
                    Row(
                        verticalAlignment     = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            item.icon, null,
                            tint     = Color(0xFF6C9EFF),
                            modifier = Modifier.size(20.dp).padding(top = 2.dp),
                        )
                        Column {
                            Text(item.label, color = Color(0xFFE6EDF3),
                                fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(item.rationale, color = Color(0xFF8B949E),
                                fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }

                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Not now", color = Color(0xFF8B949E))
                    }
                    Button(
                        onClick = { launcher.launch(permissions.toTypedArray()) },
                        colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C9EFF)),
                        shape   = RoundedCornerShape(10.dp),
                    ) {
                        Text("Allow")
                    }
                }
            }
        }
    }
}

// ── Permission state checker ──────────────────────────────────────────────────

/**
 * Returns true if all [permissions] are currently granted.
 * Recomposed when the app comes back from Settings.
 */
@Composable
fun rememberPermissionsGranted(permissions: List<String>): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(checkGranted(context, permissions)) }

    // Re-check when composition resumes (user may have gone to Settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                granted = checkGranted(context, permissions)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

fun checkGranted(context: android.content.Context, permissions: List<String>): Boolean =
    permissions.all {
        androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

