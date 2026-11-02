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

class BackendTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private fun profile(type: BackendType, stream: Boolean = true) = ConnectionProfile(
        type = type, baseUrl = server.url("/v1").toString(), model = "m",
        sampler = SamplerSettings(stream = stream, temperature = 0.7, topK = 40),
    )
    private val msgs = listOf(PromptMessage(Role.SYSTEM, "sys"), PromptMessage(Role.USER, "hi"))
    private fun req(p: ConnectionProfile) = GenerationRequest(p, "KEY", msgs, "Bob: hi\nAnn:")
    private fun sse(vararg lines: String) = MockResponse().setBody(lines.joinToString("\n\n", postfix = "\n\n") { "data: $it" })

    @Test fun openAiStreams() = runBlocking {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"Hel"}}]}""", """{"choices":[{"delta":{"content":"lo"}}]}""", "[DONE]"))
        val out = Backends.forType(BackendType.OPENAI).generate(req(profile(BackendType.OPENAI))).toList()
        assertEquals("Hello", out.joinToString(""))
        val r = server.takeRequest()
        assertEquals("/v1/chat/completions", r.path)
        assertEquals("Bearer KEY", r.getHeader("Authorization"))
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("system", body["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(40, body["top_k"]!!.jsonPrimitive.int)
    }

    @Test fun openAiNonStreaming() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"whole"}}]}"""))
        val out = Backends.forType(BackendType.OPENAI).generate(req(profile(BackendType.OPENAI, false))).toList()
        assertEquals(listOf("whole"), out)
    }

    @Test fun httpErrorSurfacesMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val e = assertThrows(BackendException::class.java) {
            runBlocking { Backends.forType(BackendType.OPENAI).generate(req(profile(BackendType.OPENAI))).toList() }
        }
        assertTrue(e.message!!.contains("401") && e.message!!.contains("bad key"))
    }

    @Test fun textCompletionStreams() = runBlocking {
        server.enqueue(sse("""{"choices":[{"text":" a"}]}""", """{"choices":[{"text":"b"}]}""", "[DONE]"))
        val out = Backends.forType(BackendType.TEXT_COMPLETION).generate(req(profile(BackendType.TEXT_COMPLETION))).toList()
        assertEquals(" ab", out.joinToString(""))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("Bob: hi\nAnn:", body["prompt"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicStreamsAndSendsHeaders() = runBlocking {
        server.enqueue(sse(
            """{"type":"message_start"}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hey"}}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"!"}}""",
            """{"type":"message_stop"}"""))
        val out = Backends.forType(BackendType.ANTHROPIC).generate(req(profile(BackendType.ANTHROPIC))).toList()
        assertEquals("Hey!", out.joinToString(""))
        val r = server.takeRequest()
        assertEquals("/v1/messages", r.path)
        assertEquals("KEY", r.getHeader("x-api-key"))
        assertNotNull(r.getHeader("anthropic-version"))
        assertEquals("sys", Json.parseToJsonElement(r.body.readUtf8()).jsonObject["system"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicMessageNormalisation() {
        val (sys, turns) = AnthropicBackend.toAnthropicMessages(listOf(
            PromptMessage(Role.SYSTEM, "S"), PromptMessage(Role.ASSISTANT, "greeting"),
            PromptMessage(Role.USER, "a"), PromptMessage(Role.USER, "b"), PromptMessage(Role.SYSTEM, "tail")))
        assertEquals("S", sys)
        assertEquals("user", turns.first().first)
        assertEquals(listOf("user", "assistant", "user"), turns.map { it.first })
        assertTrue(turns.last().second.contains("tail"))
    }
}
