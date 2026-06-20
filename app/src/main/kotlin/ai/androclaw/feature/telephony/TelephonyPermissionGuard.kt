package ai.androclaw.feature.telephony

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ai.androclaw.ui.permissions.PermissionGroups
import timber.log.Timber

/**
 * Checks telephony permissions before executing SMS / call operations.
 * Called from AgentService when the agent invokes a telephony tool.
 *
 * On Android, READ_SMS / SEND_SMS / CALL_PHONE are dangerous permissions
 * requiring explicit user grant. The PermissionGate composable handles the
 * UI flow; this guard is the programmatic check used in the service layer.
 *
 * If permissions are not granted, throws a [TelephonyPermissionException]
 * with a user-friendly message the agent can surface.
 */
object TelephonyPermissionGuard {

    fun requireSmsRead(context: Context) {
        requirePermission(context, android.Manifest.permission.READ_SMS,
            "READ_SMS permission not granted. " +
            "Go to Settings → Apps → OpenClaw → Permissions → SMS to enable it.")
    }

    fun requireSmsSend(context: Context) {
        requirePermission(context, android.Manifest.permission.SEND_SMS,
            "SEND_SMS permission not granted. " +
            "Go to Settings → Apps → OpenClaw → Permissions → SMS to enable it.")
    }

    fun requireCallPhone(context: Context) {
        requirePermission(context, android.Manifest.permission.CALL_PHONE,
            "CALL_PHONE permission not granted. " +
            "Go to Settings → Apps → OpenClaw → Permissions → Phone to enable it.")
    }

    fun requireRecordAudio(context: Context) {
        requirePermission(context, android.Manifest.permission.RECORD_AUDIO,
            "RECORD_AUDIO permission not granted. " +
            "Go to Settings → Apps → OpenClaw → Permissions → Microphone to enable it.")
    }

    fun allTelephonyGranted(context: Context): Boolean =
        PermissionGroups.TELEPHONY.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    private fun requirePermission(context: Context, permission: String, message: String) {
        val granted = ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("Permission not granted: $permission")
            throw TelephonyPermissionException(message)
        }
    }
}

class TelephonyPermissionException(message: String) : Exception(message)

