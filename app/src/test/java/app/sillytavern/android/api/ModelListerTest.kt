package app.sillytavern.android.api

import app.sillytavern.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ModelListerTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()
    private fun p(t: BackendType) = ConnectionProfile(type = t, baseUrl = server.url("/v1").toString().trimEnd('/'))
    private fun fetch(t: BackendType, body: String, key: String? = "K"): List<ModelInfo> {
        server.enqueue(MockResponse().setBody(body))
        return runBlocking { ModelLister.fetch(p(t), key) }
    }

    @Test fun openAiList() {
        val r = fetch(BackendType.OPENAI, """{"data":[{"id":"gpt-a"},{"id":"or-b","name":"B Model"}]}""")
        assertEquals(listOf("gpt-a", "or-b"), r.map { it.id })
        assertEquals("B Model", r[1].label)
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path); assertEquals("Bearer K", req.getHeader("Authorization"))
    }

    @Test fun anthropicList() {
        val r = fetch(BackendType.ANTHROPIC, """{"data":[{"id":"claude-x","display_name":"Claude X"}]}""")
        assertEquals(ModelInfo("claude-x", "Claude X"), r[0])
        assertEquals("K", server.takeRequest().getHeader("x-api-key"))
    }

    @Test fun googleFiltersNonGenerative() {
        val r = fetch(BackendType.GOOGLE, """{"models":[
            {"name":"models/gemini-pro","displayName":"Gemini Pro","supportedGenerationMethods":["generateContent"]},
            {"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}""")
        assertEquals(listOf("gemini-pro"), r.map { it.id })
        assertEquals("K", server.takeRequest().getHeader("x-goog-api-key"))
    }

    @Test fun ollamaTags() = assertEquals(listOf("llama3:8b"), fetch(BackendType.OLLAMA, """{"models":[{"name":"llama3:8b"}]}""", null).map { it.id })
    @Test fun koboldModel() = assertEquals(listOf("koboldcpp/x"), fetch(BackendType.KOBOLD, """{"result":"koboldcpp/x"}""").map { it.id })
    @Test fun textCompletionList() = assertEquals(listOf("m1"), fetch(BackendType.TEXT_COMPLETION, """{"data":[{"id":"m1"}]}""").map { it.id })
    @Test fun hordeSortedByWorkers() =
        assertEquals(listOf("big", "small"), fetch(BackendType.HORDE, """[{"name":"small","count":1},{"name":"big","count":9}]""").map { it.id })
    @Test fun novelAiFixed() = assertTrue(runBlocking { ModelLister.fetch(p(BackendType.NOVELAI), null) }.any { it.id == "kayra-v1" })

    @Test fun unexpectedShapeIsEmpty() {
        assertEquals(emptyList<ModelInfo>(), fetch(BackendType.OPENAI, """{"weird":1}"""))
        assertEquals(emptyList<ModelInfo>(), fetch(BackendType.OLLAMA, "not json"))
        assertEquals(emptyList<ModelInfo>(), fetch(BackendType.GOOGLE, "[]"))
    }

    @Test fun httpErrorThrows() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("nope"))
        assertThrows(BackendException::class.java) { runBlocking { ModelLister.fetch(p(BackendType.OPENAI), "bad") } }
    }
}
