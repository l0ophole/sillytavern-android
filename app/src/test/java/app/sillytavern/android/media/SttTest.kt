package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class SttTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private val audio = Audio(byteArrayOf(9, 8, 7), "m4a")
    private fun settings(language: String = "") = SttSettings(provider = SttProvider.WHISPER, whisperUrl = server.url("/v1").toString(), language = language)

    @Test fun multipartRequestCarriesFileModelAndLanguage() {
        server.enqueue(MockResponse().setBody("""{"text":" hello world "}"""))
        val text = runBlocking { SttClient().transcribe(settings("en-US"), audio, "sk") }
        assertEquals("hello world", text)
        val r = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", r.path)
        assertEquals("Bearer sk", r.getHeader("Authorization"))
        assertTrue(r.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = r.body.readUtf8()
        assertTrue(body.contains("name=\"model\"") && body.contains("whisper-1"))
        assertTrue(body.contains("name=\"language\"") && body.contains("\r\n\r\nen\r\n"))
        assertTrue(body.contains("filename=\"speech.m4a\""))
    }

    @Test fun languageIsOmittedWhenAutomatic() {
        server.enqueue(MockResponse().setBody("""{"text":"x"}"""))
        runBlocking { SttClient().transcribe(settings(), audio, null) }
        assertFalse(server.takeRequest().body.readUtf8().contains("name=\"language\""))
    }

    @Test fun errorsAndBadBodiesAreReported() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad audio"))
        assertTrue(assertThrows(BackendException::class.java) { runBlocking { SttClient().transcribe(settings(), audio, null) } }.message!!.contains("400"))
        assertThrows(BackendException::class.java) { SttClient.parse("not json") }
        assertThrows(BackendException::class.java) { SttClient.parse("""{"foo":1}""") }
    }

    private class FakeInput : VoiceInput {
        var recording = false; var cancelled = 0
        override suspend fun listen(language: String) = "  heard $language  "
        override fun startRecording() { recording = true }
        override suspend fun stopRecording(): Audio { recording = false; return Audio(byteArrayOf(1), "m4a") }
        override fun cancel() { cancelled++ }
    }

    @Test fun serviceUsesTheRecognizerOrWhisper() = runBlocking {
        val input = FakeInput()
        val sys = SttService({ SttSettings(language = "de") }, { null }, input)
        assertFalse(sys.twoStep); assertEquals("heard de", sys.listen())
        server.enqueue(MockResponse().setBody("""{"text":"from whisper"}"""))
        val w = SttService({ settings() }, { "k" }, input)
        assertTrue(w.twoStep)
        w.beginRecording(); assertTrue(input.recording)
        assertEquals("from whisper", w.endRecording()); assertFalse(input.recording)
    }
}
