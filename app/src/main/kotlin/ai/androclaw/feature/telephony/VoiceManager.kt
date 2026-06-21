package ai.androclaw.feature.telephony

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.websocket.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import timber.log.Timber
import java.nio.ByteBuffer
import ai.androclaw.data.prefs.ConfigStore
import io.ktor.client.engine.okhttp.OkHttp

/**
 * VoiceManager — full implementation.
 *
 * STT: Deepgram Nova-2 (sw / en) via streaming WebSocket
 *      → emits final transcripts to [transcriptions] SharedFlow
 *
 * TTS: Cartesia sonic-multilingual via REST (PCM bytes)
 *      → streams audio bytes directly to Android AudioTrack
 *      → supports mid-speech interruption via [stopSpeaking]
 *
 * Usage:
 *   voiceManager.startListening()
 *   voiceManager.transcriptions.collect { text -> viewModel.sendVoice(text) }
 *   voiceManager.speak("Habari yako?")
 *   voiceManager.stopListening()
 */
class VoiceManager(
    private val context: Context,
    private val store: ConfigStore,
) {
    companion object {
        // Cartesia voice IDs — replace with your cloned/selected voices
        const val VOICE_ID_SWAHILI = "79a125e8-cd45-4c13-8a67-188112f4dd22"   // sonic-multilingual Swahili
        const val VOICE_ID_ENGLISH = "a0e99841-438c-4a64-b679-ae501e7d6091"   // sonic-english

        // TTS audio format — Cartesia raw PCM output
        const val TTS_SAMPLE_RATE  = 44100
        const val TTS_CHANNELS     = AudioFormat.CHANNEL_OUT_MONO
        const val TTS_ENCODING     = AudioFormat.ENCODING_PCM_16BIT

        // STT capture format — Deepgram expects 16kHz mono PCM16
        const val STT_SAMPLE_RATE  = 16000
        const val CARTESIA_API_URL = "https://api.cartesia.ai/tts/bytes"
        const val CARTESIA_VERSION = "2024-06-10"
    }

    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(OkHttp) { install(WebSockets) }

    private val _transcriptions = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val transcriptions: SharedFlow<String> = _transcriptions.asSharedFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var recordingJob: Job? = null
    private var speakingJob: Job?  = null
    private var audioTrack: AudioTrack? = null

    // ── STT — Deepgram streaming ──────────────────────────────────────────────

    fun startListening() {
        if (_isListening.value) return
        _isListening.value = true

        recordingJob = scope.launch {
            try {
                val language = store.language().first()
                val deepgramApiKey = store.getSecret(ConfigStore.SecretKeys.DEEPGRAM_API_KEY)
                val langCode = if (language == "sw") "sw" else "en-US"
                val wsUrl = "wss://api.deepgram.com/v1/listen" +
                        "?model=nova-2" +
                        "&language=$langCode" +
                        "&encoding=linear16" +
                        "&sample_rate=$STT_SAMPLE_RATE" +
                        "&channels=1" +
                        "&interim_results=true" +
                        "&utterance_end_ms=1000" +
                        "&vad_events=true" +
                        "&endpointing=300"

                httpClient.webSocket(wsUrl, request = {
                    header(HttpHeaders.Authorization, "Token $deepgramApiKey")
                }) {
                    // Launch audio capture → send PCM frames to Deepgram
                    val audioJob = launch { captureAndSendAudio(this@webSocket) }

                    // Receive transcription results
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val json   = frame.readText()
                        val result = parseDeepgramResult(json) ?: continue
                        if (result.isFinal && result.transcript.isNotBlank()) {
                            Timber.d("STT [final]: ${result.transcript}")
                            _transcriptions.emit(result.transcript)
                        }
                    }
                    audioJob.cancelAndJoin()
                }
            } catch (e: CancellationException) {
                // Normal stop
            } catch (e: Exception) {
                Timber.e(e, "Deepgram STT error")
            } finally {
                _isListening.value = false
            }
        }
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob      = null
        _isListening.value = false
    }

    private suspend fun captureAndSendAudio(session: DefaultWebSocketSession) {
        val bufferSize = AudioRecord.getMinBufferSize(
            STT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(4096)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            STT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )

        recorder.startRecording()
        val buffer = ByteArray(bufferSize)
        try {
            while (session.isActive) {
                val read = recorder.read(buffer, 0, bufferSize)
                if (read > 0) {
                    session.send(Frame.Binary(true, ByteBuffer.wrap(buffer, 0, read)))
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    // ── STT result parsing ────────────────────────────────────────────────────

    private data class DeepgramResult(val transcript: String, val isFinal: Boolean, val confidence: Float)

    private fun parseDeepgramResult(json: String): DeepgramResult? {
        // Skip metadata and UtteranceEnd events
        if (!json.contains("\"transcript\"")) return null

        val isFinal     = json.contains("\"is_final\":true")
        val transcript  = """"transcript"\s*:\s*"([^"]*)"""".toRegex()
            .find(json)?.groupValues?.get(1)?.trim() ?: return null
        val confidence  = """"confidence"\s*:\s*([\d.]+)""".toRegex()
            .find(json)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        return DeepgramResult(transcript, isFinal, confidence)
    }

    // ── TTS — Cartesia streaming PCM ──────────────────────────────────────────

    fun speak(text: String) {
        // Cancel any ongoing speech
        stopSpeaking()

        speakingJob = scope.launch {
            _isSpeaking.value = true
            try {
                val language = store.language().first()
                val cartesiaApiKey = store.getSecret(ConfigStore.SecretKeys.CARTESIA_API_KEY)
                val cartesiaVoiceId = store.prefs.first()[ConfigStore.CARTESIA_VOICE_ID] ?: VOICE_ID_SWAHILI

                val modelId  = "sonic-multilingual"
                val langTag  = if (language == "sw") "sw" else "en"
                val voiceId  = if (language == "sw") cartesiaVoiceId else VOICE_ID_ENGLISH

                val requestBody = buildJsonObject {
                    put("model_id",   modelId)
                    put("transcript", text)
                    // Conditionally omit language tag if it is Swahili ("sw") to avoid Cartesia 400 Bad Request
                    if (langTag != "sw") {
                        put("language", langTag)
                    }
                    putJsonObject("voice") {
                        put("mode", "id")
                        put("id",   voiceId)
                    }
                    putJsonObject("output_format") {
                        put("container",   "raw")
                        put("encoding",    "pcm_s16le")
                        put("sample_rate", TTS_SAMPLE_RATE)
                    }
                }.let { Json.encodeToString(it) }

                val response = httpClient.post(CARTESIA_API_URL) {
                    header("Cartesia-Version", CARTESIA_VERSION)
                    header(HttpHeaders.Authorization, "Bearer $cartesiaApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                if (!response.status.isSuccess()) {
                    Timber.e("Cartesia TTS error: ${response.status} — ${response.bodyAsText()}")
                    return@launch
                }

                // Stream PCM bytes directly to AudioTrack
                streamToAudioTrack(response)

            } catch (e: CancellationException) {
                // Interrupted — normal
            } catch (e: Exception) {
                Timber.e(e, "Cartesia TTS error")
            } finally {
                releaseAudioTrack()
                _isSpeaking.value = false
            }
        }
    }

    fun stopSpeaking() {
        speakingJob?.cancel()
        speakingJob = null
        releaseAudioTrack()
        _isSpeaking.value = false
    }

    private suspend fun streamToAudioTrack(response: HttpResponse) {
        val minBuf = AudioTrack.getMinBufferSize(TTS_SAMPLE_RATE, TTS_CHANNELS, TTS_ENCODING)
            .coerceAtLeast(8192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(TTS_SAMPLE_RATE)
                    .setChannelMask(TTS_CHANNELS)
                    .setEncoding(TTS_ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        // Read raw PCM bytes from Cartesia response body and write to AudioTrack
        val channel = response.bodyAsChannel()
        val chunk   = ByteArray(4096)
        while (!channel.isClosedForRead && currentCoroutineContext().isActive) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read > 0) {
                track.write(chunk, 0, read)
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let {
            try {
                if (it.state == AudioTrack.STATE_INITIALIZED) {
                    it.stop()
                }
                it.release()
            } catch (_: Exception) {}
        }
        audioTrack = null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun destroy() {
        stopListening()
        stopSpeaking()
        scope.cancel()
        httpClient.close()
    }
}

