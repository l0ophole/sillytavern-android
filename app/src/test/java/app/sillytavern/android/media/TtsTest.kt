package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.chat.ChatFeaturesTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TtsTextTest {
    private val s = TtsSettings()
    @Test fun stripsMarkdownTagsAndCode() {
        assertEquals("Hello there friend", TtsText.prepare("**Hello** <b>there</b> _friend_", s))
        assertEquals("before after", TtsText.prepare("before ```kotlin\nval x = 1\n``` after", s))
        assertEquals("val x", TtsText.prepare("```a```val `x`", s.copy(skipCodeBlocks = true)).let { it })
        assertEquals("see", TtsText.prepare("see https://example.com/a?b=1", s))
    }

    @Test fun actionsCanBeSkipped() {
        assertEquals("waves Hi", TtsText.prepare("*waves* Hi", s))
        assertEquals("Hi", TtsText.prepare("*waves* Hi", s.copy(speakActions = false)))
    }

    @Test fun quotedAndNonQuotedModes() {
        val t = "She smiled. \"Hello,\" she said. “Bye.”"
        assertEquals("Hello, Bye.", TtsText.prepare(t, s.copy(narrate = NarrateMode.QUOTED)))
        assertEquals("She smiled. she said.", TtsText.prepare(t, s.copy(narrate = NarrateMode.NON_QUOTED)))
        assertEquals("", TtsText.prepare("no quotes here", s.copy(narrate = NarrateMode.QUOTED)))
    }

    @Test fun symbolOnlyTextIsSilent() {
        assertEquals("", TtsText.prepare("*** ---", s))
        assertEquals("", TtsText.prepare("   ", s))
    }

    @Test fun voiceLookupIsCaseInsensitiveWithDefault() {
        val v = s.copy(voiceMap = mapOf("Ann" to "nova"), defaultVoice = "alloy")
        assertEquals("nova", TtsText.voiceFor("Ann", v)); assertEquals("nova", TtsText.voiceFor("ann", v))
        assertEquals("alloy", TtsText.voiceFor("Bob", v))
    }

    @Test fun voiceMapTextRoundTrips() {
        val m = TtsText.parseVoiceMap("Ann = nova\n\nbad line\n=x\nBob=a=b\nEve=")
        assertEquals(mapOf("Ann" to "nova", "Bob" to "a=b"), m)
        assertEquals(m, TtsText.parseVoiceMap(TtsText.formatVoiceMap(m)))
    }

    @Test fun longTextIsSplitAtSentences() {
        val text = (1..50).joinToString(" ") { "Sentence number $it is here." }
        val parts = TtsText.chunks(text, 200)
        assertTrue(parts.size > 1); assertTrue(parts.all { it.length <= 200 })
        assertEquals(text, parts.joinToString(" "))
        assertEquals(listOf("short"), TtsText.chunks("short"))
        assertTrue(TtsText.chunks("x".repeat(500), 200).all { it.length <= 200 })
    }
}

class TtsClientTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private fun base() = server.url("/v1").toString().trimEnd('/')

    @Test fun openAiRequestShape() {
        server.enqueue(MockResponse().setBody("MP3DATA"))
        val s = TtsSettings(provider = TtsProvider.OPENAI, openAiUrl = base(), rate = 1.25)
        val audio = runBlocking { TtsClient().synthesize(s, "Hi there", "nova", "sk-1") }
        assertEquals("MP3DATA", audio.bytes.toString(Charsets.UTF_8))
        val r = server.takeRequest()
        assertEquals("/v1/audio/speech", r.path)
        assertEquals("Bearer sk-1", r.getHeader("Authorization"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("tts-1", body["model"]!!.jsonPrimitive.content)
        assertEquals("nova", body["voice"]!!.jsonPrimitive.content)
        assertEquals("Hi there", body["input"]!!.jsonPrimitive.content)
        assertEquals(1.25, body["speed"]!!.jsonPrimitive.double, 0.0)
    }

    @Test fun elevenLabsRequestShape() {
        server.enqueue(MockResponse().setBody("AUDIO"))
        val s = TtsSettings(provider = TtsProvider.ELEVENLABS, elevenUrl = server.url("/").toString())
        runBlocking { TtsClient().synthesize(s, "Hello", "voice 1", "xi-key") }
        val r = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice+1", r.path)
        assertEquals("xi-key", r.getHeader("xi-api-key"))
        assertEquals("Hello", Json.parseToJsonElement(r.body.readUtf8()).jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun elevenLabsNeedsAVoice() {
        assertThrows(BackendException::class.java) { TtsClient().request(TtsSettings(provider = TtsProvider.ELEVENLABS), "x", "", "k") }
    }

    @Test fun httpErrorsAreReported() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val e = assertThrows(BackendException::class.java) {
            runBlocking { TtsClient().synthesize(TtsSettings(provider = TtsProvider.OPENAI, openAiUrl = base()), "x", "v", "k") }
        }
        assertTrue(e.message!!.contains("401"))
    }
}

class FakePlayer : SpeechPlayer {
    val spoken = mutableListOf<Triple<String, String, Double>>()
    val played = mutableListOf<Audio>()
    var stops = 0
    override suspend fun speak(text: String, voice: String, rate: Double, pitch: Double) { spoken += Triple(text, voice, rate) }
    override suspend fun play(audio: Audio) { played += audio }
    override fun stop() { stops++ }
}

class TtsServiceTest {
    @Test fun systemVoiceSpeaksPreparedTextWithMappedVoice() = runBlocking {
        val p = FakePlayer()
        val s = TtsSettings(voiceMap = mapOf("Ann" to "en-us-x"), rate = 1.5)
        val svc = TtsService({ s }, { null }, p)
        assertTrue(svc.speak("*waves* **Hi**", "Ann"))
        assertEquals(Triple("waves Hi", "en-us-x", 1.5), p.spoken.single())
        assertEquals(1, p.stops)
    }

    @Test fun nothingToSayReturnsFalse() = runBlocking {
        val p = FakePlayer()
        assertFalse(TtsService({ TtsSettings() }, { null }, p).speak("***", "Ann"))
        assertTrue(p.spoken.isEmpty())
    }

    @Test fun cloudAudioIsPlayedChunkByChunk() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("A")); server.enqueue(MockResponse().setBody("B")); server.enqueue(MockResponse().setBody("C"))
            val p = FakePlayer()
            val s = TtsSettings(provider = TtsProvider.OPENAI, openAiUrl = server.url("/v1").toString())
            val long = (1..400).joinToString(" ") { "Sentence $it ends here." }
            TtsService({ s }, { "k" }, p).speak(long, "Ann")
            assertTrue(p.played.size >= 2)
            assertEquals("A", p.played[0].bytes.toString(Charsets.UTF_8))
        } finally { server.shutdown() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TtsControllerTest : ChatFeaturesTestBase() {
    @Test fun autoPlaySpeaksNewReplies() = runTest {
        cfg = cfg.copy(media = MediaSettings(tts = TtsSettings(enabled = true, autoPlay = true)))
        val p = FakePlayer()
        val c = controller(); c.tts = TtsService({ cfg.media.tts }, { null }, p)
        c.open(ann); reply = "Nice to meet you"
        c.send("hi"); advanceUntilIdle()
        assertEquals("Nice to meet you", p.spoken.last().first)
    }

    @Test fun autoPlayIsOffByDefault() = runTest {
        val p = FakePlayer()
        val c = controller(); c.tts = TtsService({ cfg.media.tts }, { null }, p)
        c.open(ann); c.send("hi"); advanceUntilIdle()
        assertTrue(p.spoken.isEmpty())
    }

    @Test fun speakCommandHostUsesVoiceMap() = runTest {
        cfg = cfg.copy(media = MediaSettings(tts = TtsSettings(voiceMap = mapOf("Ann" to "v-ann"))))
        val p = FakePlayer()
        val c = controller(); c.tts = TtsService({ cfg.media.tts }, { null }, p)
        c.open(ann)
        c.commandHost().speak("Hello", "ann")
        assertEquals("v-ann", p.spoken.single().second)
    }
}
