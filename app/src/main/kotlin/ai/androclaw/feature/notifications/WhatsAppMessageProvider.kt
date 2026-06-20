package ai.androclaw.feature.notifications

import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.tools.messaging.WaMessage

/**
 * Provides WhatsApp messages from the Room cache to the Koog WhatsAppTools.ReadMessagesTool.
 *
 * Injected at app startup so the agent can call whatsapp_read_messages
 * and get real messages from the local Room database.
 */
class WhatsAppMessageProvider(private val db: OpenClawDatabase) {

    /**
     * Returns a suspend lambda compatible with WhatsAppTools.ReadMessagesTool.messageProvider.
     * Filters by sender number if provided, otherwise returns all recent messages.
     */
    fun asProviderLambda(): suspend (Int, String) -> List<WaMessage> {
        return { limit, fromNumber ->
            val entities = if (fromNumber.isBlank()) {
                db.waMessageDao().getRecent(limit)
            } else {
                db.waMessageDao().getFromNumber(fromNumber, limit)
            }
            entities.map { entity ->
                WaMessage(
                    from      = entity.from,
                    body      = entity.body,
                    timestamp = entity.timestamp,
                    type      = entity.type,
                )
            }
        }
    }
}

