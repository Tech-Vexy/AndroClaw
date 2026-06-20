package ai.androclaw.feature.telephony

import ai.androclaw.tools.telephony.CallStatusResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber

/**
 * Single source of truth for live call state on Android.
 *
 * Updated by:
 *   - OpenClawFcmService when call_incoming FCM arrives
 *   - CallEventPoller when the agent polls vonage_get_call_status
 *   - TelephonyTools.EndCallTool after a hangup
 *
 * Consumed by:
 *   - ChatScreen (shows active call banner)
 *   - VoiceManager (mutes mic during active call)
 *   - Notification (persistent call-in-progress notification)
 */
class CallStateManager {

    // ── Public state ──────────────────────────────────────────────────────────

    private val _activeCalls = MutableStateFlow<Map<String, LiveCall>>(emptyMap())
    val activeCalls: StateFlow<Map<String, LiveCall>> = _activeCalls.asStateFlow()

    /** Most recent call regardless of status (for UI "last call" display). */
    private val _lastCall = MutableStateFlow<LiveCall?>(null)
    val lastCall: StateFlow<LiveCall?> = _lastCall.asStateFlow()

    /** True if any call is currently in ringing or answered state. */
    val hasActiveCall: Boolean
        get() = _activeCalls.value.values.any {
            it.status in setOf(CallStatus.RINGING, CallStatus.ANSWERED)
        }

    // ── Mutators ──────────────────────────────────────────────────────────────

    fun onCallStarted(uuid: String, to: String, from: String, direction: CallDirection) {
        val call = LiveCall(
            uuid      = uuid,
            to        = to,
            from      = from,
            direction = direction,
            status    = CallStatus.RINGING,
            startedAt = System.currentTimeMillis(),
        )
        _activeCalls.update { it + (uuid to call) }
        _lastCall.value = call
        Timber.d("Call started: $uuid $direction $from → $to")
    }

    fun onCallAnswered(uuid: String) {
        _activeCalls.update { calls ->
            calls[uuid]?.let { call ->
                calls + (uuid to call.copy(status = CallStatus.ANSWERED, answeredAt = System.currentTimeMillis()))
            } ?: calls
        }
        Timber.d("Call answered: $uuid")
    }

    fun onCallEnded(uuid: String, reason: String = "") {
        val ended = _activeCalls.value[uuid]?.copy(
            status  = CallStatus.COMPLETED,
            endedAt = System.currentTimeMillis(),
            endReason = reason,
        )
        _activeCalls.update { it - uuid }
        ended?.let { _lastCall.value = it }
        Timber.d("Call ended: $uuid reason=$reason duration=${ended?.durationSeconds}s")
    }

    fun onCallFailed(uuid: String, reason: String) {
        val failed = _activeCalls.value[uuid]?.copy(
            status    = CallStatus.FAILED,
            endedAt   = System.currentTimeMillis(),
            endReason = reason,
        )
        _activeCalls.update { it - uuid }
        failed?.let { _lastCall.value = it }
        Timber.w("Call failed: $uuid reason=$reason")
    }

    fun updateFromStatus(result: CallStatusResult) {
        val status = when (result.status) {
            "started"   -> CallStatus.RINGING
            "ringing"   -> CallStatus.RINGING
            "answered"  -> CallStatus.ANSWERED
            "completed" -> CallStatus.COMPLETED
            "failed"    -> CallStatus.FAILED
            "busy"      -> CallStatus.BUSY
            "timeout"   -> CallStatus.TIMEOUT
            "cancelled" -> CallStatus.CANCELLED
            else        -> CallStatus.UNKNOWN
        }
        val isTerminated = status in setOf(
            CallStatus.COMPLETED, CallStatus.FAILED, CallStatus.BUSY,
            CallStatus.TIMEOUT, CallStatus.CANCELLED
        )
        if (isTerminated) {
            val ended = _activeCalls.value[result.callUuid]?.copy(
                status = status,
                endedAt = System.currentTimeMillis()
            )
            _activeCalls.update { it - result.callUuid }
            ended?.let { _lastCall.value = it }
        } else {
            _activeCalls.update { calls ->
                val existing = calls[result.callUuid]
                if (existing != null) {
                    calls + (result.callUuid to existing.copy(status = status))
                } else if (status in setOf(CallStatus.RINGING, CallStatus.ANSWERED)) {
                    // New call discovered via polling
                    val call = LiveCall(
                        uuid      = result.callUuid,
                        to        = result.to,
                        from      = result.from,
                        direction = if (result.direction == "inbound") CallDirection.INBOUND else CallDirection.OUTBOUND,
                        status    = status,
                        startedAt = System.currentTimeMillis(),
                    )
                    _lastCall.value = call
                    calls + (result.callUuid to call)
                } else {
                    calls
                }
            }
        }
    }

    fun clear() = _activeCalls.update { emptyMap() }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class LiveCall(
    val uuid: String,
    val to: String,
    val from: String,
    val direction: CallDirection,
    val status: CallStatus,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String = "",
) {
    val durationSeconds: Long
        get() {
            val start = answeredAt ?: startedAt
            val end   = endedAt ?: System.currentTimeMillis()
            return (end - start) / 1000
        }

    val displayName: String
        get() = if (direction == CallDirection.INBOUND) from else to
}

enum class CallDirection { INBOUND, OUTBOUND }

enum class CallStatus {
    RINGING, ANSWERED, COMPLETED, FAILED, BUSY, TIMEOUT, CANCELLED, UNKNOWN
}

