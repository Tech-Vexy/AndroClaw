package ai.androclaw.feature.telephony

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ai.androclaw.feature.telephony.VoiceManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Thin ViewModel wrapping VoiceManager.
 * The ChatScreen collects [transcriptions] and forwards them to ChatViewModel.
 */
class VoiceViewModel(
    app: Application,
    private val voiceManager: VoiceManager,
) : AndroidViewModel(app) {

    val isListening: StateFlow<Boolean> = voiceManager.isListening
    val isSpeaking:  StateFlow<Boolean> = voiceManager.isSpeaking
    val transcriptions: SharedFlow<String> = voiceManager.transcriptions

    fun toggleListening() {
        if (voiceManager.isListening.value) {
            voiceManager.stopListening()
        } else {
            voiceManager.startListening()
        }
    }

    fun speak(text: String) = voiceManager.speak(text)
    fun stopSpeaking()      = voiceManager.stopSpeaking()

    override fun onCleared() {
        super.onCleared()
        voiceManager.destroy()
    }
}

