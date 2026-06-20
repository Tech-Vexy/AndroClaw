package ai.androclaw.feature.notifications

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Observes app foreground/background transitions.
 *
 * On foreground (onStart):
 *   - Syncs recent WhatsApp messages from gateway → Room
 *   - Refreshes FCM device registration (token may have rotated)
 *
 * Register in MainActivity.onCreate():
 *   lifecycle.addObserver(AppLifecycleObserver(syncer))
 */
class AppLifecycleObserver(
    private val syncer: WaMessageSyncer,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStart(owner: LifecycleOwner) {
        Timber.d("App foregrounded — syncing messages")
        scope.launch {
            try {
                syncer.syncWhatsAppMessages()
            } catch (e: Exception) {
                Timber.w(e, "Foreground WA sync failed")
            }
        }
    }
}

