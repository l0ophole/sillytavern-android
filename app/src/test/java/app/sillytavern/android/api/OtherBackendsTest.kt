package app.sillytavern.android.api

import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OtherBackendsTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private fun profile(type: BackendType, stream: Boolean = true, model: String = "m") = ConnectionProfile(
        type = type, baseUrl = server.url("/").toString().trimEnd('/'), model = model,
        sampler = SamplerSettings(stream = stream, topK = 30, stopSequences = listOf("User:")),
    )
    private fun req(p: ConnectionProfile) = GenerationRequest(p, "KEY", listOf(PromptMessage(Role.USER, "hi")), "Ann: hi\nBob:")
    private fun chunks(b: ChunkBackend, p: ConnectionProfile) = runBlocking { b.chunks(req(p)).toList() }
    private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    private val client = Backends.defaultClient

    @Test fun ollamaStreamsNdjsonWithThinking() {
        server.enqueue(MockResponse().setBody(
            """{"message":{"role":"assistant","thinking":"hm"},"done":false}""" + "\n" +
                """{"message":{"role":"assistant","content":"Hel"},"done":false}""" + "\n" +
                """{"message":{"role":"assistant","content":"lo"},"done":false}""" + "\n" +
                """{"message":{"role":"assistant","content":""},"done":true}""" + "\n"))
        val p = profile(BackendType.OLLAMA)
        assertEquals(listOf(Chunk.Reasoning("hm"), Chunk.Text("Hel"), Chunk.Text("lo")), chunks(OllamaBackend(client), p))
    }

    @Test fun ollamaRequestShape() {
        server.enqueue(MockResponse().setBody("""{"message":{"content":"x"},"done":true}"""))
        val p = profile(BackendType.OLLAMA, false).copy(reasoning = ReasoningSettings("min"))
        assertEquals(listOf(Chunk.Text("x")), chunks(OllamaBackend(client), p))
        val r = server.takeRequest()
        assertEquals("/api/chat", r.path)
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals(false, b["think"]!!.jsonPrimitive.boolean)
        assertEquals(30, b["options"]!!.jsonObject["top_k"]!!.jsonPrimitive.int)
        assertEquals("User:", b["options"]!!.jsonObject["stop"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun ollamaErrorSurfaced() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model 'x' not found"}"""))
        val e = assertThrows(BackendException::class.java) { chunks(OllamaBackend(client), profile(BackendType.OLLAMA)) }
        assertTrue(e.message!!.contains("not found"))
    }

    @Test fun koboldCppStreamsTokens() {
        server.enqueue(MockResponse().setBody("event: message\ndata: {\"token\": \"Hi\"}\n\nevent: message\ndata: {\"token\": \" there\"}\n\n"))
        assertEquals(listOf(Chunk.Text("Hi"), Chunk.Text(" there")), chunks(KoboldBackend(client), profile(BackendType.KOBOLD)))
        // body is read below
    }

    @Test fun koboldRequestBodyAndClassicNonStream() {
        server.enqueue(MockResponse().setBody("""{"results":[{"text":"whole"}]}"""))
        assertEquals(listOf(Chunk.Text("whole")), chunks(KoboldBackend(client), profile(BackendType.KOBOLD, false)))
        val r = server.takeRequest()
        assertEquals("/api/v1/generate", r.path)
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("Ann: hi\nBob:", b["prompt"]!!.jsonPrimitive.content)
        assertEquals("User:", b["stop_sequence"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(30, b["top_k"]!!.jsonPrimitive.int)
    }

    @Test fun koboldCppStreamPath() {
        server.enqueue(MockResponse().setBody("data: {\"token\": \"a\"}\n\n"))
        chunks(KoboldBackend(client), profile(BackendType.KOBOLD))
        assertEquals("/api/extra/generate/stream", server.takeRequest().path)
    }

    @Test fun novelAiStreamsAndCutsStopStrings() {
        server.enqueue(MockResponse().setBody("data: {\"token\":\"Hello \"}\n\ndata: {\"token\":\"there\\nUs\"}\n\ndata: {\"token\":\"er: no\"}\n\n"))
        val out = chunks(NovelAiBackend(client), profile(BackendType.NOVELAI, model = "kayra-v1"))
        assertEquals("Hello there\n", out.filterIsInstance<Chunk.Text>().joinToString("") { it.text })
        val r = server.takeRequest()
        assertEquals("/ai/generate-stream", r.path)
        assertEquals("Bearer KEY", r.getHeader("Authorization"))
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("kayra-v1", b["model"]!!.jsonPrimitive.content)
        assertEquals("Ann: hi\nBob:", b["input"]!!.jsonPrimitive.content)
    }

    @Test fun stopStringsAcrossBoundaryAndPassthrough() = runBlocking {
        val cut = StopStrings.apply(flowOf(Chunk.Text("ab Us"), Chunk.Text("er: x")), listOf("User:")).toList()
        assertEquals("ab ", cut.joinToString("") { (it as Chunk.Text).text })
        val keep = StopStrings.apply(flowOf(Chunk.Text("abc"), Chunk.Text("def")), listOf("User:")).toList()
        assertEquals("abcdef", keep.joinToString("") { (it as Chunk.Text).text })
    }

    @Test fun hordeSubmitsPollsAndReturns() {
        server.enqueue(MockResponse().setBody("""{"id":"job1"}"""))
        server.enqueue(MockResponse().setBody("""{"done":false,"queue_position":2}"""))
        server.enqueue(MockResponse().setBody("""{"done":true,"generations":[{"text":"done text"}]}"""))
        val p = profile(BackendType.HORDE, model = "aphrodite/some-model")
        assertEquals(listOf(Chunk.Text("done text")), chunks(HordeBackend(client, 10), p.copy(baseUrl = p.baseUrl + "/api")))
        val submit = server.takeRequest()
        assertEquals("/api/v2/generate/text/async", submit.path)
        assertEquals("KEY", submit.getHeader("apikey"))
        val b = Json.parseToJsonElement(submit.body.readUtf8()).jsonObject
        assertEquals("aphrodite/some-model", b["models"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("/api/v2/generate/text/status/job1", server.takeRequest().path)
    }

    @Test fun hordeFaultedThrows() {
        server.enqueue(MockResponse().setBody("""{"id":"j"}"""))
        server.enqueue(MockResponse().setBody("""{"faulted":true}"""))
        assertThrows(BackendException::class.java) { chunks(HordeBackend(client, 10), profile(BackendType.HORDE)) }
    }

    @Test fun hordeSubmitErrorSurfaced() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Wrong API key"}"""))
        val e = assertThrows(BackendException::class.java) { chunks(HordeBackend(client, 10), profile(BackendType.HORDE)) }
        assertTrue(e.message!!.contains("Wrong API key"))
    }

    @Test fun hordeCancelDeletesJob() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"id":"j9"}"""))
        repeat(50) { server.enqueue(MockResponse().setBody("""{"done":false}""")) }
        val job = async { HordeBackend(client, 20).chunks(req(profile(BackendType.HORDE))).toList() }
        withTimeout(5000) { while (server.requestCount < 3) kotlinx.coroutines.delay(10) }
        job.cancelAndJoin()
        withTimeout(5000) { while (server.requestCount < 3 || true) { if (drain()) break; kotlinx.coroutines.delay(10) } }
    }

    private fun drain(): Boolean {
        while (true) {
            val r = server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return false
            if (r.method == "DELETE" && r.path == "/v2/generate/text/status/j9") return true
        }
    }

    @Test fun textModeForTypes() {
        assertTrue(BackendType.KOBOLD.usesTextPrompt && BackendType.NOVELAI.usesTextPrompt &&
            BackendType.HORDE.usesTextPrompt && BackendType.TEXT_COMPLETION.usesTextPrompt)
        assertFalse(BackendType.OLLAMA.usesTextPrompt || BackendType.GOOGLE.usesTextPrompt || BackendType.OPENAI.usesTextPrompt)
    }
}
