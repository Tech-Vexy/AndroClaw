package ai.androclaw.ui.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Wraps a composable behind a permission check.
 * Shows a rationale dialog before requesting. Falls back to a "Go to Settings" screen.
 *
 * Usage in nav graph:
 *   PermissionGate(PermissionGroups.CORE) {
 *       ChatScreen(onOpenSettings = { ... })
 *   }
 */
@Composable
fun PermissionGate(
    permissions: List<String>,
    onPermanentlyDenied: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val context      = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(checkGranted(context, permissions))
    }
    var showDialog by remember { mutableStateOf(!granted) }

    // Re-check on resume (user may have gone to Settings and returned)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted    = checkGranted(context, permissions)
                showDialog = !granted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when {
        granted     -> content()
        showDialog  -> PermissionRationaleDialog(
            permissions = permissions,
            onGranted   = { granted = true; showDialog = false },
            onDismiss   = { showDialog = false; onPermanentlyDenied?.invoke() },
        )
        else        -> PermissionDeniedScreen(
            permissions = permissions,
            onRetry     = { showDialog = true },
        )
    }
}

// ── Denied fallback screen ─────────────────────────────────────────────────────

@Composable
private fun PermissionDeniedScreen(permissions: List<String>, onRetry: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier         = Modifier.fillMaxSize().background(Color(0xFF0D1117)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier            = Modifier.padding(32.dp),
        ) {
            Icon(Icons.Default.Lock, null,
                tint     = Color(0xFF8B949E),
                modifier = Modifier.size(48.dp))

            Text(
                "${permissions.size} permission${if (permissions.size > 1) "s" else ""} required",
                color      = Color(0xFFE6EDF3),
                fontSize   = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "OpenClaw needs these permissions to function. " +
                "Grant them in Android Settings, or tap below to try again.",
                color     = Color(0xFF8B949E),
                fontSize  = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 19.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ))
                    },
                    border = BorderStroke(1.dp, Color(0xFF8B949E).copy(alpha = 0.3f)),
                ) {
                    Text("Open Settings", color = Color(0xFF8B949E))
                }
                Button(
                    onClick = onRetry,
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C9EFF)),
                    shape   = RoundedCornerShape(10.dp),
                ) {
                    Text("Try again")
                }
            }
        }
    }
}

