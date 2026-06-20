package ai.androclaw.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ai.androclaw.core.OpenClawForegroundService
import timber.log.Timber

/**
 * Restarts the OpenClaw foreground service after device reboot.
 * Requires RECEIVE_BOOT_COMPLETED permission (declared in manifest).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Timber.d("Boot completed — starting OpenClaw foreground service")
        ContextCompat.startForegroundService(
            context,
            Intent(context, OpenClawForegroundService::class.java),
        )
    }
}

