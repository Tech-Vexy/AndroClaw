package ai.androclaw.feature.notifications

import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.data.db.WaMessageEntity
import ai.androclaw.data.prefs.ConfigStore
import com.google.firebase.messaging.FirebaseMessaging
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID

/**
 * Handles two sync concerns:
 *
 *  1. registerDevice — called on FCM token refresh and app startup.
 *     Posts the FCM token to the gateway /register-device endpoint so
 *     the webhook service knows where to send push notifications.
 *
 *  2. syncMessages — called when the app comes to foreground.
 *     Fetches recent WA messages from Firestore via the gateway REST API
 *     and upserts them into Room for the agent's ReadMessagesTool to consume.
 */
class WaMessageSyncer(
    private val db: OpenClawDatabase,
    private val configStore: ConfigStore,
    private val httpClient: HttpClient,
) {
    // Stable device ID — generated once, persisted in DataStore
    private suspend fun deviceId(): String {
        val id = configStore.prefs.first()[ConfigStore.Keys.GATEWAY_BASE_URL] ?: ""
        // In production: store a separate DEVICE_ID key in DataStore
        return "openclaw-android-${UUID.nameUUIDFromBytes(id.toByteArray())}"
    }

    // ── Token registration ────────────────────────────────────────────────────

    suspend fun registerDevice(fcmToken: String? = null) = withContext(Dispatchers.IO) {
        try {
            val token   = fcmToken ?: FirebaseMessaging.getInstance().token.await()
            val devId   = deviceId()
            val gateway = configStore.get(ConfigStore.GATEWAY_BASE_URL, "").first()
            val secret  = configStore.getSecret(ConfigStore.SecretKeys.BRIDGE_SECRET)

            if (gateway.isBlank()) {
                Timber.w("Gateway URL not configured — skipping device registration")
                return@withContext
            }

            httpClient.post("$gateway/wa/register-device") {
                header("X-Androclaw-Secret", secret)
                contentType(ContentType.Application.Json)
                setBody("""{"device_id":"$devId","fcm_token":"$token"}""")
            }
            Timber.d("Device registered with gateway: $devId")
        } catch (e: Exception) {
            Timber.e(e, "Device registration failed")
        }
    }

    // ── Message sync (foreground pull) ────────────────────────────────────────

    suspend fun syncWhatsAppMessages(limit: Int = 50) = withContext(Dispatchers.IO) {
        try {
            val gateway = configStore.getSecret(ConfigStore.SecretKeys.BRIDGE_SECRET).let {
                configStore.prefs.first()[ConfigStore.GATEWAY_BASE_URL] ?: ""
            }
            val secret  = configStore.getSecret(ConfigStore.SecretKeys.BRIDGE_SECRET)

            if (gateway.isBlank()) return@withContext

            val devId = deviceId()
            val resp  = httpClient.get("$gateway/wa/messages/$devId") {
                parameter("secret", secret)
                parameter("limit", limit)
            }

            if (!resp.status.isSuccess()) {
                Timber.w("WA sync failed: ${resp.status}")
                return@withContext
            }

            val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(resp.bodyAsText())
                .jsonObject
            val messages = root["messages"]?.jsonArray ?: return@withContext

            var upserted = 0
            messages.forEach { el ->
                try {
                    val obj = el.jsonObject
                    val id  = obj["id"]?.jsonPrimitive?.content ?: return@forEach
                    db.waMessageDao().insert(
                        ai.androclaw.data.db.WaMessageEntity(
                            id        = id,
                            from      = obj["from"]?.jsonPrimitive?.content ?: "",
                            body      = obj["body"]?.jsonPrimitive?.content ?: "",
                            timestamp = obj["timestamp"]?.jsonPrimitive?.long ?: 0L,
                            type      = obj["type"]?.jsonPrimitive?.content ?: "text",
                            isRead    = obj["is_read"]?.jsonPrimitive?.boolean ?: false,
                        )
                    )
                    upserted++
                } catch (e: Exception) {
                    Timber.w(e, "Failed to upsert WA message")
                }
            }
            Timber.d("WA sync: upserted $upserted messages")
        } catch (e: Exception) {
            Timber.e(e, "WA message sync failed — will retry on next foreground")
        }
    }
}

