package app.sillytavern.android.api

import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GeminiBackendTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private fun profile(stream: Boolean, reasoning: ReasoningSettings = ReasoningSettings()) = ConnectionProfile(
        type = BackendType.GOOGLE, baseUrl = server.url("/v1beta").toString(), model = "gemini-2.5-pro",
        sampler = SamplerSettings(stream = stream, topK = 20, stopSequences = listOf("END")), reasoning = reasoning,
    )
    private val msgs = listOf(PromptMessage(Role.SYSTEM, "sys"), PromptMessage(Role.USER, "hi"), PromptMessage(Role.ASSISTANT, "yo"), PromptMessage(Role.ASSISTANT, "more"))
    private fun chunks(p: ConnectionProfile) = runBlocking {
        (Backends.forType(BackendType.GOOGLE) as ChunkBackend).chunks(GenerationRequest(p, "KEY", msgs, "")).toList()
    }
    private fun sse(vararg l: String) = MockResponse().setBody(l.joinToString("\n\n", postfix = "\n\n") { "data: $it" })

    @Test fun toContentsMapsRoles() {
        val (sys, c) = GeminiBackend.toContents(msgs)
        assertEquals("sys", sys)
        assertEquals(listOf("user", "model"), c.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("yo\n\nmore", c[1]["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun toContentsPrependsUserWhenFirstIsModel() {
        val (_, c) = GeminiBackend.toContents(listOf(PromptMessage(Role.ASSISTANT, "a")))
        assertEquals("user", c[0]["role"]!!.jsonPrimitive.content)
    }

    @Test fun streamsTextAndThoughts() {
        server.enqueue(sse(
            """{"candidates":[{"content":{"parts":[{"text":"plan","thought":true}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
            """{"candidates":[{"content":{"parts":[{"text":"lo"}]},"finishReason":"STOP"}]}""",
        ))
        assertEquals(listOf(Chunk.Reasoning("plan"), Chunk.Text("Hel"), Chunk.Text("lo")), chunks(profile(true)))
        val r = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-pro:streamGenerateContent?alt=sse", r.path)
        assertEquals("KEY", r.getHeader("x-goog-api-key"))
    }

    @Test fun nonStreaming() {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"whole"}]}}]}"""))
        assertEquals(listOf(Chunk.Text("whole")), chunks(profile(false)))
        assertEquals("/v1beta/models/gemini-2.5-pro:generateContent", server.takeRequest().path)
    }

    @Test fun requestHasSafetyAndConfig() {
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        chunks(profile(false, ReasoningSettings("high")))
        val b = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val g = b["generationConfig"]!!.jsonObject
        assertEquals(20, g["topK"]!!.jsonPrimitive.int)
        assertEquals("END", g["stopSequences"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(24576, g["thinkingConfig"]!!.jsonObject["thinkingBudget"]!!.jsonPrimitive.int)
        assertTrue(b["safetySettings"]!!.jsonArray.all { it.jsonObject["threshold"]!!.jsonPrimitive.content == "BLOCK_NONE" })
        assertEquals("sys", b["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test fun blockedPromptThrows() {
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        val e = assertThrows(BackendException::class.java) { chunks(profile(false)) }
        assertTrue(e.message!!.contains("SAFETY"))
    }

    @Test fun errorBodySurfaced() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"API key not valid"}}"""))
        val e = assertThrows(BackendException::class.java) { chunks(profile(false)) }
        assertTrue(e.message!!.contains("API key not valid"))
    }
}
