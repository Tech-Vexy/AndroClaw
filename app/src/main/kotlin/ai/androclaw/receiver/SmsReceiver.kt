package ai.androclaw.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import ai.androclaw.feature.telephony.SmsAutoReplyPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Receives inbound SMS from the Android system.
 * Combines multi-part messages and hands off to SmsAutoReplyPipeline.
 */
class SmsReceiver : BroadcastReceiver(), KoinComponent {

    private val pipeline: SmsAutoReplyPipeline by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val from      = messages.first().originatingAddress ?: return
        val body      = messages.joinToString("") { it.messageBody ?: "" }
        val timestamp = messages.first().timestampMillis

        Timber.d("SMS from $from: ${body.take(60)}")
        pipeline.process(from = from, body = body, timestamp = timestamp)
    }
}

