package ai.androclaw.feature.telephony

import android.content.Context
import android.database.Cursor
import android.net.Uri
import ai.androclaw.tools.telephony.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

/**
 * Reads SMS from the Android system content provider (content://sms/inbox).
 * Requires READ_SMS permission (declared in manifest, requested at runtime).
 *
 * Injected into TelephonyTools.ReadSmsTool.smsProvider at startup via Koin.
 */
class AndroidSmsProvider(private val context: Context) {

    private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Fetch recent SMS messages from the inbox.
     * @param limit  Max messages to return.
     * @param fromNumber  If non-blank, filter by sender (prefix match, E.164 or local format).
     */
    suspend fun readInbox(limit: Int, fromNumber: String): List<SmsMessage> =
        withContext(Dispatchers.IO) {
            try {
                queryInbox(limit, fromNumber)
            } catch (e: SecurityException) {
                Timber.w("READ_SMS permission not granted")
                listOf(SmsMessage(
                    from      = "system",
                    text      = "READ_SMS permission required. Grant it in Settings → Apps → OpenClaw → Permissions.",
                    timestamp = sdf.format(Date()),
                ))
            } catch (e: Exception) {
                Timber.e(e, "SMS inbox query failed")
                emptyList()
            }
        }

    private fun queryInbox(limit: Int, fromNumber: String): List<SmsMessage> {
        val uri        = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date", "read", "type")

        val selection     : String?
        val selectionArgs : Array<String>?

        if (fromNumber.isNotBlank()) {
            // Normalise the filter number for comparison (strip +, leading zeros etc.)
            val normalised = normaliseNumber(fromNumber)
            selection     = "address LIKE ?"
            selectionArgs = arrayOf("%$normalised%")
        } else {
            selection     = null
            selectionArgs = null
        }

        val cursor: Cursor? = context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            "date DESC LIMIT $limit",
        )

        return cursor?.use { c ->
            val messages = mutableListOf<SmsMessage>()
            val idxAddress = c.getColumnIndexOrThrow("address")
            val idxBody    = c.getColumnIndexOrThrow("body")
            val idxDate    = c.getColumnIndexOrThrow("date")
            val idxRead    = c.getColumnIndexOrThrow("read")
            val idxId      = c.getColumnIndexOrThrow("_id")

            while (c.moveToNext()) {
                val address   = c.getString(idxAddress) ?: ""
                val body      = c.getString(idxBody) ?: ""
                val dateMs    = c.getLong(idxDate)
                val isRead    = c.getInt(idxRead) == 1
                val messageId = c.getString(idxId) ?: ""

                messages.add(SmsMessage(
                    from      = address,
                    text      = body,
                    timestamp = sdf.format(Date(dateMs)),
                    messageId = messageId,
                    isRead    = isRead,
                    isMpesa   = isMpesaSms(body),
                ))
            }
            messages
        } ?: emptyList()
    }

    private fun normaliseNumber(number: String): String =
        number.replace(Regex("[^0-9]"), "").takeLast(9)  // last 9 digits for EA numbers

    private fun isMpesaSms(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("mpesa") ||
               lower.contains("m-pesa") ||
               (lower.contains("confirmed") && lower.contains("ksh")) ||
               (lower.contains("safaricom") && lower.contains("received"))
    }
}

