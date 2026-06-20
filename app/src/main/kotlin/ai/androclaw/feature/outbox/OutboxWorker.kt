package ai.androclaw.feature.outbox

import android.content.Context
import androidx.work.*
import ai.androclaw.data.db.OpenClawDatabase
import ai.androclaw.data.db.OutboxMessage
import ai.androclaw.tools.messaging.WhatsAppTools
import ai.androclaw.tools.telephony.TelephonyTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Drains the outbox (Room `outbox` table) when the device regains network connectivity.
 *
 * Architecture:
 *   1. When a tool call fails with a network error, OutboxEnqueuer.enqueue() saves
 *      the message to the outbox table instead of surfacing an error to the agent.
 *   2. OutboxWorker is enqueued with a CONNECTED network constraint.
 *   3. On next connection, it replays each pending message in order.
 *   4. Sent messages are marked "sent". Failed messages (after 3 attempts) are "failed".
 *   5. OutboxWorker.scheduleRecurring() runs every 15 min as a safety net.
 */
class OutboxWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val db: OpenClawDatabase by inject()
    private val telephonyTools: TelephonyTools by inject()
    private val waTools: WhatsAppTools by inject()

    companion object {
        const val WORK_NAME_IMMEDIATE  = "outbox_drain_immediate"
        const val WORK_NAME_PERIODIC   = "outbox_drain_periodic"
        const val MAX_ATTEMPTS         = 3

        /** Enqueue an immediate drain when a new outbox message is added. */
        fun scheduleImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_IMMEDIATE, ExistingWorkPolicy.KEEP, request)
        }

        /** Safety-net periodic drain — catches anything missed by the immediate trigger. */
        fun scheduleRecurring(context: Context) {
            val request = PeriodicWorkRequestBuilder<OutboxWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val pending = db.outboxDao().getPending()
        if (pending.isEmpty()) {
            Timber.d("OutboxWorker: no pending messages")
            return@withContext Result.success()
        }

        Timber.d("OutboxWorker: draining ${pending.size} pending messages")
        var anyFailed = false

        for (msg in pending) {
            if (msg.attemptCount >= MAX_ATTEMPTS) {
                db.outboxDao().updateStatus(msg.id, "failed",
                    System.currentTimeMillis(), "Max attempts exceeded")
                Timber.w("OutboxWorker: giving up on ${msg.id} after $MAX_ATTEMPTS attempts")
                continue
            }

            try {
                sendMessage(msg)
                db.outboxDao().updateStatus(msg.id, "sent", System.currentTimeMillis())
                Timber.d("OutboxWorker: sent ${msg.channel} to ${msg.to}")
            } catch (e: Exception) {
                Timber.e(e, "OutboxWorker: failed to send ${msg.id}")
                db.outboxDao().updateStatus(msg.id, "pending",
                    System.currentTimeMillis(), e.message ?: "unknown error")
                anyFailed = true
            }
        }

        // Clean up sent messages older than 7 days
        db.outboxDao().clearSent(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)

        if (anyFailed) Result.retry() else Result.success()
    }

    private suspend fun sendMessage(msg: OutboxMessage) {
        when (msg.channel) {
            "sms" -> TelephonyTools.SendSmsTool().execute(
                TelephonyTools.SendSmsTool.Args(to = msg.to, text = msg.body)
            )
            "whatsapp" -> waTools.SendTextTool().execute(
                WhatsAppTools.SendTextArgs(to = msg.to, message = msg.body)
            )
            else -> throw IllegalArgumentException("Unknown channel: ${msg.channel}")
        }
    }
}

