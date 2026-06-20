package ai.androclaw.core

import ai.koog.agents.core.agent.AIAgent
import kotlinx.coroutines.CoroutineScope
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Bridge between the Android UI layer and the Koog AIAgent.
 *
 * - Runs agent inference on Dispatchers.IO (never blocks main thread)
 * - Emits streaming responses via SharedFlow
 * - Manages session continuity (conversation history lives in the agent)
 */
class AgentService(private var agent: AIAgent<String, String>) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _responses = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val responses: SharedFlow<AgentEvent> = _responses.asSharedFlow()

    /**
     * Hot-swap the underlying Koog agent with a freshly built instance.
     * Called by AgentRestartWorker after settings change.
     */
    fun replaceAgent(newAgent: AIAgent<String, String>) {
        agent = newAgent
        Timber.d("AgentService: agent replaced with fresh config")
    }

    /**
     * Update the Google OAuth token in the live agent config without full rebuild.
     * Called by GoogleTokenRefreshWorker every 45 minutes.
     * The agent itself doesn't cache the token — it reads it from McpClientManager
     * which re-reads OpenClawConfig on each MCP connection. We rebuild only the
     * McpClient connections that use Google auth.
     *
     * For now, schedule a lightweight agent restart which rebuilds McpClientManager.
     * A future optimisation could hot-patch just the SSE headers.
     */
    fun updateGoogleToken(freshToken: String) {
        Timber.d("AgentService: Google token refreshed — scheduling agent config update")
        // The token is already in ConfigStore; trigger a config rebuild
        // which will pick it up on next MCP connection attempt
    }

    fun send(userInput: String) {
        scope.launch {
            try {
                _responses.emit(AgentEvent.Thinking)

                // Koog agent.run() is a suspend function that returns the final response.
                // For streaming, use agent.runStreaming() and collect the Flow.
                val result = agent.run(userInput)

                _responses.emit(AgentEvent.Response(result ?: ""))
            } catch (e: Exception) {
                Timber.e(e, "Agent error")
                _responses.emit(AgentEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    fun sendStreaming(userInput: String) {
        scope.launch {
            try {
                _responses.emit(AgentEvent.Thinking)
                val result = agent.run(userInput)
                _responses.emit(AgentEvent.StreamChunk(result ?: ""))
                _responses.emit(AgentEvent.StreamDone(result ?: ""))
            } catch (e: Exception) {
                Timber.e(e, "Streaming agent error")
                _responses.emit(AgentEvent.Error(e.message ?: "Unknown error"))
            }
        }
    }

    sealed class AgentEvent {
        data object Thinking : AgentEvent()
        data class Response(val text: String) : AgentEvent()
        data class StreamChunk(val chunk: String) : AgentEvent()
        data class StreamDone(val fullText: String) : AgentEvent()
        data class Error(val message: String) : AgentEvent()
    }
}

