package app.sillytavern.android.api

import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import app.sillytavern.android.prompt.model.TextGenSamplerPreset
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

    private fun textBody(preset: TextGenSamplerPreset?, stops: List<String> = emptyList()): JsonObject {
        server.enqueue(sse("""{"choices":[{"text":"x"}]}""", "[DONE]"))
        val p = profile(BackendType.TEXT_COMPLETION)
        runBlocking {
            Backends.forType(BackendType.TEXT_COMPLETION).generate(
                GenerationRequest(p, null, emptyList(), "prompt", stopSequences = stops, sampler = preset)
            ).toList()
        }
        return Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    @Test fun defaultPresetSendsOnlyBasicFields() {
        val b = textBody(TextGenSamplerPreset())
        assertFalse(b.containsKey("dry_multiplier")); assertFalse(b.containsKey("xtc_probability")); assertFalse(b.containsKey("seed"))
        assertEquals(1.0, b["temperature"]!!.jsonPrimitive.double, 0.0)
        assertFalse("preset overrides profile top_k", b.containsKey("top_k"))
    }

    @Test fun nonDefaultSamplersAreSent() {
        val b = textBody(TextGenSamplerPreset(temp = 1.5, minP = 0.1, dryMultiplier = 0.8, xtcProbability = 0.5, mirostatMode = 2))
        assertEquals(1.5, b["temperature"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.1, b["min_p"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.8, b["dry_multiplier"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.5, b["xtc_probability"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2, b["mirostat_mode"]!!.jsonPrimitive.int)
    }

    @Test fun seedMinusOneOmittedAndPositiveSent() {
        assertFalse(textBody(TextGenSamplerPreset(seed = -1)).containsKey("seed"))
        assertEquals(42, textBody(TextGenSamplerPreset(seed = 42))["seed"]!!.jsonPrimitive.int)
    }

    @Test fun breakersParsedAsArray() {
        val b = textBody(TextGenSamplerPreset(dryMultiplier = 0.8))
        assertEquals(4, b["dry_sequence_breakers"]!!.jsonArray.size)
    }

    @Test fun stopStringsSent() {
        val b = textBody(TextGenSamplerPreset(), listOf("<|im_end|>", "\nBob:"))
        assertEquals(listOf("<|im_end|>", "\nBob:"), b["stop"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun noPresetKeepsProfileSamplers() {
        assertEquals(40, textBody(null)["top_k"]!!.jsonPrimitive.int)
    }

    private fun chunks(type: BackendType, p: ConnectionProfile) =
        runBlocking { (Backends.forType(type) as ChunkBackend).chunks(req(p)).toList() }

    @Test fun openAiReasoningContentStreams() {
        server.enqueue(sse("""{"choices":[{"delta":{"reasoning_content":"hm"}}]}""", """{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        assertEquals(listOf(Chunk.Reasoning("hm"), Chunk.Text("ok")), chunks(BackendType.OPENAI, profile(BackendType.OPENAI)))
        server.enqueue(sse("""{"choices":[{"delta":{"reasoning_content":"hm"}}]}""", """{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        val text = runBlocking { Backends.forType(BackendType.OPENAI).generate(req(profile(BackendType.OPENAI))).toList() }
        assertEquals(listOf("ok"), text)
    }

    @Test fun openAiReasoningFieldAlias() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"reasoning":"why","content":"ans"}}]}"""))
        assertEquals(listOf(Chunk.Reasoning("why"), Chunk.Text("ans")), chunks(BackendType.OPENAI, profile(BackendType.OPENAI, false)))
    }

    @Test fun anthropicThinkingDelta() {
        server.enqueue(sse(
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"t1"}}""",
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"hi"}}""",
            """{"type":"message_stop"}"""))
        assertEquals(listOf(Chunk.Reasoning("t1"), Chunk.Text("hi")), chunks(BackendType.ANTHROPIC, profile(BackendType.ANTHROPIC)))
    }

    @Test fun anthropicThinkingRequestBody() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"thinking","thinking":"a"},{"type":"text","text":"b"}]}"""))
        val p = profile(BackendType.ANTHROPIC, false).copy(reasoning = ReasoningSettings(budgetTokens = 2000))
        assertEquals(listOf(Chunk.Reasoning("a"), Chunk.Text("b")), chunks(BackendType.ANTHROPIC, p))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(2000, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertNull(body["temperature"])
        assertTrue(body["max_tokens"]!!.jsonPrimitive.int > 2000)
    }

    @Test fun streamWithoutDoneCompletes() {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"a"}}]}"""))
        assertEquals(listOf(Chunk.Text("a")), chunks(BackendType.OPENAI, profile(BackendType.OPENAI)))
    }

    @Test fun keepAliveEventsIgnored() {
        server.enqueue(sse("""{"choices":[]}""", """{"usage":{"total_tokens":3}}""", """{"choices":[{"delta":{"content":"x"}}]}""", "[DONE]"))
        assertEquals(listOf(Chunk.Text("x")), chunks(BackendType.OPENAI, profile(BackendType.OPENAI)))
    }
}
