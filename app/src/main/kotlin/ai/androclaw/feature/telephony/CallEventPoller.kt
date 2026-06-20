package ai.androclaw.feature.telephony

import ai.androclaw.core.AgentService
import ai.androclaw.tools.telephony.TelephonyTools
import ai.androclaw.tools.telephony.GetCallStatusTool
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Polls Vonage for call status updates while a call is active.
 *
 * Why poll instead of relying solely on FCM webhooks?
 *   - Vonage webhooks go to Render; FCM can be delayed on Android
 *   - During calls, 2-second poll gives near-real-time status in the UI
 *   - Detects hung calls that never sent a completion webhook
 *
 * Polling stops automatically when no calls are active.
 * Triggered by CallStateManager.hasActiveCall.
 */
class CallEventPoller(
    private val telephonyTools: TelephonyTools,
    private val callStateManager: CallStateManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    private val _isPolling = MutableStateFlow(false)
    val isPolling: StateFlow<Boolean> = _isPolling

    fun startPolling(callUuid: String, intervalMs: Long = 2_000L) {
        if (pollJob?.isActive == true) return
        Timber.d("CallEventPoller: starting for $callUuid every ${intervalMs}ms")
        _isPolling.value = true

        pollJob = scope.launch {
            var consecutiveErrors = 0
            while (isActive) {
                try {
                    val status = telephonyTools.GetCallStatusTool().execute(
                        TelephonyTools.GetCallStatusTool.Args(callUuid = callUuid)
                    )
                    callStateManager.updateFromStatus(status)
                    consecutiveErrors = 0

                    // Stop polling when call terminates
                    if (status.status in setOf("completed", "failed", "busy", "timeout", "cancelled")) {
                        Timber.d("CallEventPoller: call $callUuid ended (${status.status})")
                        break
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    consecutiveErrors++
                    Timber.w(e, "CallEventPoller error #$consecutiveErrors for $callUuid")
                    if (consecutiveErrors >= 5) {
                        Timber.e("CallEventPoller: too many errors, stopping poll for $callUuid")
                        break
                    }
                }
                delay(intervalMs)
            }
            _isPolling.value = false
            Timber.d("CallEventPoller: stopped for $callUuid")
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _isPolling.value = false
    }
}

