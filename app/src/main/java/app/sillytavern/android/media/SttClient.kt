package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Whisper-compatible `/audio/transcriptions` (OpenAI, Groq, whisper.cpp / faster-whisper servers). */
class SttClient(private val client: OkHttpClient = Backends.defaultClient) {
    fun request(s: SttSettings, audio: Audio, apiKey: String?): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", s.whisperModel)
            .addFormDataPart("response_format", "json")
            .apply { if (s.language.isNotBlank()) addFormDataPart("language", s.language.substringBefore('-').lowercase()) }
            .addFormDataPart("file", "speech.${audio.extension}", audio.bytes.toRequestBody(mimeOf(audio.extension).toMediaType()))
            .build()
        return Request.Builder().url(s.whisperUrl.trimEnd('/') + "/audio/transcriptions")
            .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(body).build()
    }

    suspend fun transcribe(s: SttSettings, audio: Audio, apiKey: String?): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request(s, audio, apiKey)).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw BackendException("STT HTTP ${r.code}: ${text.take(300)}")
                parse(text)
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }

    companion object {
        fun parse(raw: String): String {
            val o = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw BackendException("Unreadable transcription response")
            return o["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: throw BackendException("No text in transcription response")
        }

        fun mimeOf(ext: String) = when (ext.lowercase()) {
            "m4a", "mp4" -> "audio/mp4"; "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"; "ogg" -> "audio/ogg"; "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
    }
}

/** Microphone access; implemented on Android with SpeechRecognizer and MediaRecorder. */
interface VoiceInput {
    /** Listens until the user stops talking and returns the recognized text (empty when nothing was understood). */
    suspend fun listen(language: String): String
    fun startRecording()
    suspend fun stopRecording(): Audio
    fun cancel()
}

class SttService(
    private val settings: () -> SttSettings,
    private val apiKey: () -> String?,
    private val input: VoiceInput,
    private val client: SttClient = SttClient(),
) {
    /** Whisper needs an explicit start and stop; the system recognizer ends by itself. */
    val twoStep: Boolean get() = settings().provider == SttProvider.WHISPER

    suspend fun listen(): String = input.listen(settings().language).trim()

    fun beginRecording() = input.startRecording()

    suspend fun endRecording(): String = client.transcribe(settings(), input.stopRecording(), apiKey()).trim()

    fun cancel() = input.cancel()
}
