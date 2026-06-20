package ai.androclaw.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.androclaw.core.AgentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: Role,
    val text: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val inputText: String = "",
    val error: String? = null,
)

class ChatViewModel(private val agentService: AgentService) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        // Collect agent events and update UI state
        viewModelScope.launch {
            agentService.responses.collect { event ->
                when (event) {
                    is AgentService.AgentEvent.Thinking -> {
                        _uiState.update { it.copy(isThinking = true, error = null) }
                    }
                    is AgentService.AgentEvent.Response -> {
                        appendAssistantMessage(event.text)
                        _uiState.update { it.copy(isThinking = false) }
                    }
                    is AgentService.AgentEvent.StreamChunk -> {
                        appendStreamChunk(event.chunk)
                    }
                    is AgentService.AgentEvent.StreamDone -> {
                        finaliseStream()
                        _uiState.update { it.copy(isThinking = false) }
                    }
                    is AgentService.AgentEvent.Error -> {
                        _uiState.update { it.copy(isThinking = false, error = event.message) }
                    }
                }
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun send(customText: String? = null) {
        val text = customText ?: _uiState.value.inputText.trim()
        if (text.isBlank()) return

        if (customText == null) {
            val userMsg = ChatMessage(role = Role.USER, text = text)
            _uiState.update { it.copy(
                messages  = it.messages + userMsg,
                inputText = "",
            )}
        }

        agentService.sendStreaming(text)
    }

    fun sendVoice(transcription: String) {
        _uiState.update { it.copy(inputText = transcription) }
        send()
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun appendAssistantMessage(text: String) {
        val msg = ChatMessage(role = Role.ASSISTANT, text = text)
        _uiState.update { it.copy(messages = it.messages + msg) }
    }

    private fun appendStreamChunk(chunk: String) {
        val msgs = _uiState.value.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.isStreaming) {
            msgs[msgs.lastIndex] = last.copy(text = last.text + chunk)
        } else {
            msgs.add(ChatMessage(role = Role.ASSISTANT, text = chunk, isStreaming = true))
        }
        _uiState.update { it.copy(messages = msgs) }
    }

    private fun finaliseStream() {
        val msgs = _uiState.value.messages.toMutableList()
        val last = msgs.lastOrNull()
        if (last != null && last.role == Role.ASSISTANT && last.isStreaming) {
            msgs[msgs.lastIndex] = last.copy(isStreaming = false)
        }
        _uiState.update { it.copy(messages = msgs) }
    }
}

