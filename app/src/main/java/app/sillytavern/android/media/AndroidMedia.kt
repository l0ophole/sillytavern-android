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
