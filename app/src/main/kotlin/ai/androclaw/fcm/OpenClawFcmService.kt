package ai.androclaw.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import ai.androclaw.MainActivity
import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.data.db.WaMessageEntity
import ai.androclaw.data.prefs.ConfigStore
import ai.androclaw.feature.notifications.WaMessageSyncer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import timber.log.Timber
import ai.androclaw.feature.telephony.CallDirection
import ai.androclaw.feature.telephony.CallEventPoller
import ai.androclaw.feature.telephony.CallStateManager

/**
 * Handles two event types:
 *
 *  1. onNewToken — device FCM token refreshed → register with gateway
 *  2. onMessageReceived — incoming WA message push → cache in Room + show notification
 */
class OpenClawFcmService : FirebaseMessagingService() {

    private val db: OpenClawDatabase by inject()
    private val configStore: ConfigStore by inject()
    private val syncer: WaMessageSyncer by inject()
    private val callStateManager: CallStateManager by inject()
    private val callEventPoller: CallEventPoller by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val CHANNEL_MESSAGES = "openclaw_messages"
        const val CHANNEL_CALLS    = "openclaw_calls"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    // ── Token refresh ─────────────────────────────────────────────────────────

    override fun onNewToken(token: String) {
        Timber.d("FCM token refreshed: ${token.take(20)}…")
        scope.launch {
            syncer.registerDevice(token)
        }
    }

    // ── Incoming push ─────────────────────────────────────────────────────────

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        Timber.d("FCM message: type=${data["type"]}")

        when (data["type"]) {
            "whatsapp_message" -> handleWhatsAppMessage(data)
            "sms_received"     -> handleSmsNotification(data)
            "call_incoming"    -> handleCallNotification(data)
            else               -> Timber.d("Unknown FCM type: ${data["type"]}")
        }
    }

    // ── WhatsApp message ──────────────────────────────────────────────────────

    private fun handleWhatsAppMessage(data: Map<String, String>) {
        val fromName   = data["from_name"]   ?: data["from_number"] ?: "Unknown"
        val fromNumber = data["from_number"] ?: ""
        val preview    = data["preview"]     ?: ""
        val messageId  = data["message_id"]  ?: System.currentTimeMillis().toString()

        // Cache in Room immediately (full message synced on next foreground)
        scope.launch {
            db.waMessageDao().insert(
                WaMessageEntity(
                    id        = messageId,
                    from      = fromNumber,
                    body      = preview,
                    timestamp = System.currentTimeMillis() / 1000,
                    type      = "text",
                    isRead    = false,
                )
            )
        }

        // Show notification
        showMessageNotification(
            id      = messageId.hashCode(),
            channel = CHANNEL_MESSAGES,
            title   = "WhatsApp: $fromName",
            body    = preview,
            deepLink = "openclaw://chat?from=$fromNumber&channel=whatsapp",
        )
    }

    // ── SMS notification ──────────────────────────────────────────────────────

    private fun handleSmsNotification(data: Map<String, String>) {
        val from    = data["from"]    ?: "Unknown"
        val preview = data["preview"] ?: ""
        showMessageNotification(
            id      = ("sms_$from").hashCode(),
            channel = CHANNEL_MESSAGES,
            title   = "SMS: $from",
            body    = preview,
            deepLink = "openclaw://chat?channel=sms",
        )
    }

    // ── Incoming call notification ────────────────────────────────────────────

    private fun handleCallNotification(data: Map<String, String>) {
        val from     = data["from"]      ?: "Unknown"
        val callUuid = data["call_uuid"] ?: ""

        callStateManager.onCallStarted(
            uuid      = callUuid,
            to        = "",
            from      = from,
            direction = CallDirection.INBOUND,
        )
        if (callUuid.isNotBlank()) callEventPoller.startPolling(callUuid)

        showMessageNotification(
            id       = ("call_$from").hashCode(),
            channel  = CHANNEL_CALLS,
            title    = "Incoming call: $from",
            body     = "Tap to answer via OpenClaw",
            deepLink = "openclaw://chat?channel=telephony&call_uuid=$callUuid",
            priority = NotificationCompat.PRIORITY_MAX,
        )
    }

    // ── Notification builder ──────────────────────────────────────────────────

    private fun showMessageNotification(
        id: Int,
        channel: String,
        title: String,
        body: String,
        deepLink: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra("deep_link", deepLink)
            flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(priority)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(id, notification)
    }

    // ── Notification channels ─────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "WhatsApp, SMS message notifications"
            enableVibration(true)
        })

        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming call notifications"
            enableVibration(true)
        })
    }
}

