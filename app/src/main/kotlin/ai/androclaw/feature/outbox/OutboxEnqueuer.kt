package ai.androclaw.feature.outbox

import android.content.Context
import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.data.db.OutboxMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Saves a failed outbound message to the Room outbox and schedules
 * OutboxWorker to retry it when connectivity is restored.
 *
 * Usage:
 *   try {
 *       telephonyTools.SendSmsTool().execute(...)
 *   } catch (e: Exception) {
 *       if (isNetworkError(e)) {
 *           OutboxEnqueuer.enqueue(context, db, "sms", to, body)
 *       }
 *   }
 */
object OutboxEnqueuer {

    private val scope = CoroutineScope(Dispatchers.IO)

    fun enqueue(
        context: Context,
        db: OpenClawDatabase,
        channel: String,
        to: String,
        body: String,
    ) {
        scope.launch {
            val msg = OutboxMessage(
                id        = UUID.randomUUID().toString(),
                channel   = channel,
                to        = to,
                body      = body,
                createdAt = System.currentTimeMillis(),
                status    = "pending",
            )
            db.outboxDao().insert(msg)
            Timber.d("OutboxEnqueuer: queued $channel message to $to (${body.take(40)}…)")
            OutboxWorker.scheduleImmediate(context)
        }
    }

    fun isNetworkError(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return msg.contains("connection") ||
               msg.contains("timeout") ||
               msg.contains("network") ||
               msg.contains("unreachable") ||
               msg.contains("failed to connect") ||
               e is java.net.ConnectException ||
               e is java.net.SocketTimeoutException ||
               e is java.net.UnknownHostException ||
               e is io.ktor.client.plugins.HttpRequestTimeoutException
    }
}

