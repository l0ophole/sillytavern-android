package app.sillytavern.android.media

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** [SpeechPlayer] on Android's TextToSpeech and MediaPlayer. Untested outside a device. */
class AndroidSpeechPlayer(private val context: Context) : SpeechPlayer {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var player: MediaPlayer? = null

    private suspend fun engine(): TextToSpeech? {
        tts?.let { return if (ready) it else null }
        return suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (cont.isActive) cont.resume(if (ready) created else null)
            }
            tts = created
        }
    }

    override suspend fun speak(text: String, voice: String, rate: Double, pitch: Double) {
        val t = engine() ?: throw IllegalStateException("No text-to-speech engine available")
        t.setSpeechRate(rate.toFloat()); t.setPitch(pitch.toFloat())
        if (voice.isNotBlank()) t.voices?.firstOrNull { it.name == voice }?.let { t.voice = it }
        else t.language = Locale.getDefault()
        suspendCancellableCoroutine { cont ->
            val id = "st-" + System.nanoTime()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { t.stop() }
            // Utterances over the engine's input limit are split by the engine only on newer APIs, so keep them short.
            val parts = TtsText.chunks(text, TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3900))
            parts.forEachIndexed { i, part ->
                t.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) id else "$id-$i")
            }
        }
    }

    override suspend fun play(audio: Audio) {
        val file = File.createTempFile("tts", "." + audio.extension, context.cacheDir).also { it.writeBytes(audio.bytes) }
        try {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                mp.setDataSource(file.path)
                mp.setOnCompletionListener { mp.release(); if (player === mp) player = null; if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> mp.release(); if (player === mp) player = null; if (cont.isActive) cont.resume(Unit); true }
                cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
                mp.prepare(); mp.start()
            }
        } finally { file.delete() }
    }

    override fun stop() {
        tts?.stop()
        player?.let { runCatching { it.stop(); it.release() } }; player = null
    }

    override fun voices(): List<String> = tts?.voices?.map { it.name }?.sorted().orEmpty()
}

/** [VoiceInput] on Android's SpeechRecognizer and MediaRecorder (AAC in MP4). Needs RECORD_AUDIO; untested outside a device. */
class AndroidVoiceInput(private val context: Context) : VoiceInput {
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var recorder: android.media.MediaRecorder? = null
    private var file: File? = null

    override suspend fun listen(language: String): String {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) throw IllegalStateException("Speech recognition is not available on this device")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val r = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = r
                r.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        r.destroy(); recognizer = null
                        val text = results?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (cont.isActive) cont.resume(text)
                    }
                    override fun onError(error: Int) {
                        r.destroy(); recognizer = null
                        // No match / timeout are normal "heard nothing" outcomes.
                        if (!cont.isActive) return
                        if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH || error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) cont.resume("")
                        else cont.resumeWith(Result.failure(IllegalStateException("Speech recognition error $error")))
                    }
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                cont.invokeOnCancellation { recognizer?.destroy(); recognizer = null }
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                if (language.isNotBlank()) intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
                r.startListening(intent)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun startRecording() {
        cancel()
        val f = File.createTempFile("stt", ".m4a", context.cacheDir)
        file = f
        recorder = (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context) else android.media.MediaRecorder()).apply {
            setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000); setAudioEncodingBitRate(64000)
            setOutputFile(f.path)
            prepare(); start()
        }
    }

    override suspend fun stopRecording(): Audio {
        val r = recorder ?: throw IllegalStateException("Not recording")
        val f = file!!
        recorder = null; file = null
        try { r.stop() } catch (e: RuntimeException) { f.delete(); throw IllegalStateException("Recording was too short") } finally { r.release() }
        return try { Audio(f.readBytes(), "m4a") } finally { f.delete() }
    }

    override fun cancel() {
        recognizer?.let { runCatching { it.cancel(); it.destroy() } }; recognizer = null
        recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null
        file?.delete(); file = null
    }
}
