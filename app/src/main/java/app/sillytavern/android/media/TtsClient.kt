package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Synthesized audio and its file extension. */
class Audio(val bytes: ByteArray, val extension: String)

/** Cloud TTS: OpenAI-compatible `/audio/speech` and ElevenLabs. The system voice is spoken on the device instead. */
class TtsClient(private val client: OkHttpClient = Backends.defaultClient) {
    fun request(s: TtsSettings, text: String, voice: String, apiKey: String?): Request {
        val json = "application/json".toMediaType()
        return when (s.provider) {
            TtsProvider.OPENAI -> {
                val body = buildJsonObject {
                    put("model", s.openAiModel); put("input", text); put("voice", voice.ifBlank { "alloy" })
                    put("response_format", "mp3"); put("speed", s.rate.coerceIn(0.25, 4.0))
                }
                Request.Builder().url(s.openAiUrl.trimEnd('/') + "/audio/speech")
                    .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                    .post(body.toString().toRequestBody(json)).build()
            }
            TtsProvider.ELEVENLABS -> {
                if (voice.isBlank()) throw BackendException("Choose an ElevenLabs voice id")
                val body = buildJsonObject { put("text", text); put("model_id", s.elevenModel) }
                Request.Builder().url(s.elevenUrl.trimEnd('/') + "/v1/text-to-speech/" + java.net.URLEncoder.encode(voice, "UTF-8"))
                    .header("Accept", "audio/mpeg")
                    .apply { apiKey?.takeIf { it.isNotBlank() }?.let { header("xi-api-key", it) } }
                    .post(body.toString().toRequestBody(json)).build()
            }
            TtsProvider.SYSTEM -> throw IllegalArgumentException("System voices are not synthesized over HTTP")
        }
    }

    suspend fun synthesize(s: TtsSettings, text: String, voice: String, apiKey: String?): Audio = withContext(Dispatchers.IO) {
        val req = request(s, text, voice, apiKey)
        try {
            client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (!r.isSuccessful) throw BackendException("TTS HTTP ${r.code}: ${bytes.toString(Charsets.UTF_8).take(300)}")
                if (bytes.isEmpty()) throw BackendException("TTS returned no audio")
                Audio(bytes, "mp3")
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }
}

/** Plays audio on the device. Implemented with Android's TextToSpeech and MediaPlayer. */
interface SpeechPlayer {
    /** Speaks [text] with a system voice; returns when finished or stopped. */
    suspend fun speak(text: String, voice: String, rate: Double, pitch: Double)
    suspend fun play(audio: Audio)
    fun stop()
    /** Names of the installed system voices. */
    fun voices(): List<String> = emptyList()
}

/** Orchestrates synthesis and playback; one utterance at a time, a new one replaces the current. */
class TtsService(
    private val settings: () -> TtsSettings,
    private val apiKey: () -> String?,
    private val player: SpeechPlayer,
    private val client: TtsClient = TtsClient(),
) {
    @Volatile private var generation = 0

    /** Speaks [raw] message text as [speaker]. Returns false when there is nothing to say. */
    suspend fun speak(raw: String, speaker: String, voiceOverride: String? = null): Boolean {
        val s = settings()
        val text = TtsText.prepare(raw, s)
        if (text.isEmpty()) return false
        val voice = voiceOverride ?: TtsText.voiceFor(speaker, s)
        stop()
        val mine = generation
        if (s.provider == TtsProvider.SYSTEM) player.speak(text, voice, s.rate, s.pitch)
        else for (part in TtsText.chunks(text)) {
            if (mine != generation) break // replaced or stopped meanwhile
            player.play(client.synthesize(s, part, voice, apiKey()))
        }
        return true
    }

    fun stop() { generation++; player.stop() }
    fun voices() = player.voices()
}
