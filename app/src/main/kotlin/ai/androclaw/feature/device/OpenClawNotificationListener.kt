package ai.androclaw.feature.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Notification Listener Service — intercepts all status bar notifications.
 *
 * Gives the agent full awareness of what's happening on the device:
 *   - Message previews from apps not integrated via API (Signal, iMessage via relay, etc.)
 *   - Banking alerts, delivery tracking, calendar reminders
 *   - Any app that uses notifications
 *
 * Must be enabled by the user in:
 *   Settings → Apps → Special app access → Notification access → OpenClaw
 *
 * Privacy: this service sees notification content from ALL apps.
 * The agent should only act on notifications the user has asked it to monitor.
 * We never store notification content beyond the in-memory event buffer.
 */
class OpenClawNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile internal var instance: OpenClawNotificationListener? = null
        val isEnabled: Boolean get() = instance != null

        private val _notifications = MutableSharedFlow<NotificationEvent>(
            extraBufferCapacity  = 128,
            onBufferOverflow     = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
        val notifications: SharedFlow<NotificationEvent> = _notifications.asSharedFlow()

        // Package filter — agent subscribes to specific packages
        // Empty set = receive all; non-empty = only listed packages
        val watchedPackages = mutableSetOf<String>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onListenerConnected() {
        instance = this
        Timber.d("NotificationListener: connected")
    }

    override fun onListenerDisconnected() {
        instance = null
        Timber.d("NotificationListener: disconnected")
    }

    // ── Event handling ────────────────────────────────────────────────────────

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (shouldIgnore(pkg, sbn)) return

        val extras    = sbn.notification.extras
        val title     = extras.getCharSequence("android.title")?.toString() ?: ""
        val text      = extras.getCharSequence("android.text")?.toString() ?: ""
        val bigText   = extras.getCharSequence("android.bigText")?.toString() ?: text
        val subText   = extras.getCharSequence("android.subText")?.toString() ?: ""

        val event = NotificationEvent.Posted(
            id          = sbn.id,
            key         = sbn.key,
            packageName = pkg,
            appName     = getAppName(pkg),
            title       = title,
            text        = bigText.ifBlank { text },
            subText     = subText,
            timestamp   = sbn.postTime,
            isOngoing   = sbn.isOngoing,
            category    = sbn.notification.category ?: "",
            channelId   = sbn.notification.channelId ?: "",
        )

        scope.launch { _notifications.emit(event) }
        Timber.v("Notification: [$pkg] $title — $text")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        scope.launch {
            _notifications.emit(NotificationEvent.Dismissed(
                id          = sbn.id,
                key         = sbn.key,
                packageName = pkg,
                timestamp   = System.currentTimeMillis(),
            ))
        }
    }

    // ── Query active notifications ────────────────────────────────────────────

    /** Get all currently active notifications, optionally filtered by package. */
    fun getActiveNotifications(packageFilter: String = ""): List<NotificationEvent.Posted> {
        return try {
            activeNotifications
                ?.filter { packageFilter.isBlank() || it.packageName == packageFilter }
                ?.map { sbn ->
                    val extras  = sbn.notification.extras
                    NotificationEvent.Posted(
                        id          = sbn.id,
                        key         = sbn.key,
                        packageName = sbn.packageName,
                        appName     = getAppName(sbn.packageName),
                        title       = extras.getCharSequence("android.title")?.toString() ?: "",
                        text        = extras.getCharSequence("android.text")?.toString() ?: "",
                        subText     = "",
                        timestamp   = sbn.postTime,
                        isOngoing   = sbn.isOngoing,
                        category    = sbn.notification.category ?: "",
                        channelId   = sbn.notification.channelId ?: "",
                    )
                } ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get active notifications")
            emptyList()
        }
    }

    /** Dismiss a specific notification by key. */
    fun dismissNotification(key: String) {
        try {
            cancelNotification(key)
            Timber.d("Dismissed notification: $key")
        } catch (e: Exception) {
            Timber.e(e, "Failed to dismiss notification: $key")
        }
    }

    /** Dismiss all notifications from a specific package. */
    fun dismissAllFromPackage(packageName: String) {
        try {
            activeNotifications
                ?.filter { it.packageName == packageName }
                ?.forEach { cancelNotification(it.key) }
        } catch (e: Exception) {
            Timber.e(e, "Failed to dismiss notifications from $packageName")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun shouldIgnore(pkg: String, sbn: StatusBarNotification): Boolean {
        // Always ignore our own notifications
        if (pkg == packageName) return true
        // Ignore system notifications (media playback, charging, etc.)
        if (pkg == "android") return true
        // If watchedPackages is set, only process those
        if (watchedPackages.isNotEmpty() && pkg !in watchedPackages) return true
        // Ignore group summary notifications (duplicates child content)
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return true
        return false
    }

    private fun getAppName(packageName: String): String = try {
        packageManager.getApplicationLabel(
            packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (e: Exception) {
        packageName.substringAfterLast('.')
    }
}

// ── Event models ──────────────────────────────────────────────────────────────

sealed class NotificationEvent {
    data class Posted(
        val id: Int,
        val key: String,
        val packageName: String,
        val appName: String,
        val title: String,
        val text: String,
        val subText: String,
        val timestamp: Long,
        val isOngoing: Boolean,
        val category: String,
        val channelId: String,
    ) : NotificationEvent()

    data class Dismissed(
        val id: Int,
        val key: String,
        val packageName: String,
        val timestamp: Long,
    ) : NotificationEvent()
}

