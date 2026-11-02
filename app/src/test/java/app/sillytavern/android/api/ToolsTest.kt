package app.sillytavern.android.api

import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.PromptMessage
import app.sillytavern.android.prompt.Role
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ToolsTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private val schema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject { put("q", buildJsonObject { put("type", "string") }) }) }
    private val tool = ToolDef("search", "Search the web", schema)

    private fun profile(t: BackendType, stream: Boolean = true) = ConnectionProfile(
        type = t, baseUrl = server.url("/v1").toString().trimEnd('/'), model = "m", sampler = SamplerSettings(stream = stream),
    )
    private fun chunks(t: BackendType, stream: Boolean, tools: List<ToolDef> = emptyList(), msgs: List<PromptMessage> = listOf(PromptMessage(Role.USER, "hi"))) = runBlocking {
        (Backends.forType(t) as ChunkBackend).chunks(GenerationRequest(profile(t, stream), "K", msgs, "", tools = tools)).toList()
    }
    private fun sse(vararg l: String) = MockResponse().setBody(l.joinToString("\n\n", postfix = "\n\n") { "data: $it" })
    private fun body() = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun openAiAssemblesSplitToolCallArguments() {
        server.enqueue(sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"search","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"cats\"}"}}]}}]}""",
            "[DONE]"))
        assertEquals(listOf(Chunk.ToolCall("c1", "search", """{"q":"cats"}""")), chunks(BackendType.OPENAI, true, listOf(tool)))
    }

    @Test fun openAiParallelCallsByIndex() {
        server.enqueue(sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"f1","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"f2","arguments":"{\"x\":1}"}}]}}]}""",
            "[DONE]"))
        assertEquals(listOf("f1", "f2"), chunks(BackendType.OPENAI, true, listOf(tool)).map { (it as Chunk.ToolCall).name })
    }

    @Test fun openAiNonStreamToolCall() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":null,"tool_calls":[{"id":"z","type":"function","function":{"name":"search","arguments":"{\"q\":\"x\"}"}}]}}]}"""))
        assertEquals(listOf(Chunk.ToolCall("z", "search", """{"q":"x"}""")), chunks(BackendType.OPENAI, false, listOf(tool)))
    }

    @Test fun anthropicToolUseAssembled() {
        server.enqueue(sse(
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu1","name":"search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"dogs\"}"}}""",
            """{"type":"message_stop"}"""))
        assertEquals(listOf(Chunk.ToolCall("tu1", "search", """{"q":"dogs"}""")), chunks(BackendType.ANTHROPIC, true, listOf(tool)))
    }

    @Test fun anthropicNonStreamToolUse() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"tool_use","id":"t","name":"search","input":{"q":"a"}}]}"""))
        assertEquals(listOf(Chunk.ToolCall("t", "search", """{"q":"a"}""")), chunks(BackendType.ANTHROPIC, false, listOf(tool)))
    }

    @Test fun geminiFunctionCall() {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"functionCall":{"name":"search","args":{"q":"b"}}}]}}]}"""))
        assertEquals(listOf(Chunk.ToolCall("search-0", "search", """{"q":"b"}""")), chunks(BackendType.GOOGLE, false, listOf(tool)))
    }

    @Test fun ollamaToolCalls() {
        server.enqueue(MockResponse().setBody("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"search","arguments":{"q":"c"}}}]},"done":true}"""))
        assertEquals(listOf(Chunk.ToolCall("search-0", "search", """{"q":"c"}""")), chunks(BackendType.OLLAMA, false, listOf(tool)))
    }

    @Test fun requestsIncludeToolDefs() {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        chunks(BackendType.OPENAI, false, listOf(tool))
        assertEquals("search", body()["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setBody("""{"content":[]}"""))
        chunks(BackendType.ANTHROPIC, false, listOf(tool))
        assertNotNull(body()["tools"]!!.jsonArray[0].jsonObject["input_schema"])

        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        chunks(BackendType.GOOGLE, false, listOf(tool))
        assertEquals("search", body()["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse().setBody("""{"message":{"content":"x"},"done":true}"""))
        chunks(BackendType.OLLAMA, false, listOf(tool))
        assertEquals("search", body()["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun noToolsMeansNoToolsField() {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        chunks(BackendType.OPENAI, false)
        assertNull(body()["tools"])
    }

    private val callMsgs = listOf(
        PromptMessage(Role.USER, "find"),
        PromptMessage(Role.ASSISTANT, "", toolCalls = listOf(Chunk.ToolCall("c1", "search", """{"q":"x"}"""))),
        PromptMessage(Role.TOOL, "result!", toolCallId = "c1", toolName = "search"),
    )

    @Test fun openAiSerializesToolRoundTrip() {
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        chunks(BackendType.OPENAI, false, listOf(tool), callMsgs)
        val m = body()["messages"]!!.jsonArray
        assertEquals("c1", m[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("tool", m[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("c1", m[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
    }

    @Test fun anthropicSerializesToolRoundTrip() {
        val (_, turns) = AnthropicBackend.toAnthropicTurns(callMsgs)
        assertEquals(listOf("user", "assistant", "user"), turns.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("tool_use", turns[1]["content"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
        val r = turns[2]["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", r["type"]!!.jsonPrimitive.content)
        assertEquals("c1", r["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun geminiSerializesToolRoundTrip() {
        val (_, c) = GeminiBackend.toContents(callMsgs)
        assertEquals(listOf("user", "model", "user"), c.map { it["role"]!!.jsonPrimitive.content })
        assertNotNull(c[1]["parts"]!!.jsonArray[0].jsonObject["functionCall"])
        assertEquals("search", c[2]["parts"]!!.jsonArray[0].jsonObject["functionResponse"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    // --- ToolLoop with a scripted backend ---
    private class Scripted(val rounds: List<List<Chunk>>) : ChunkBackend {
        val seen = mutableListOf<GenerationRequest>()
        override fun chunks(req: GenerationRequest): Flow<Chunk> = flow {
            seen += req
            rounds.getOrElse(seen.size - 1) { emptyList() }.forEach { emit(it) }
        }
    }
    private class Reg(val out: String = "42") : ToolRegistry {
        val calls = mutableListOf<Chunk.ToolCall>()
        override fun definitions() = listOf(ToolDef("calc", "calc", JsonObject(emptyMap())))
        override suspend fun execute(call: Chunk.ToolCall): String { calls += call; return out }
    }
    private val baseReq = GenerationRequest(ConnectionProfile(), "k", listOf(PromptMessage(Role.USER, "q")), "")

    @Test fun loopRunsToolAndReRequests() = runBlocking {
        val b = Scripted(listOf(listOf(Chunk.Text("let me check. "), Chunk.ToolCall("1", "calc", "{}")), listOf(Chunk.Text("It is 42"))))
        val reg = Reg()
        val out = ToolLoop.run(b, baseReq, reg).toList()
        assertEquals("let me check. It is 42", out.filterIsInstance<Chunk.Text>().joinToString("") { it.text })
        assertEquals(1, reg.calls.size)
        assertEquals(2, b.seen.size)
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.TOOL), b.seen[1].messages.map { it.role })
        assertEquals("42", b.seen[1].messages.last().content)
        assertEquals("calc", b.seen[0].tools[0].name)
    }

    @Test fun loopStopsAtMaxRounds() = runBlocking {
        val call = listOf(Chunk.ToolCall("1", "calc", "{}"))
        val b = Scripted(List(10) { call })
        ToolLoop.run(b, baseReq, Reg(), maxRounds = 3).toList()
        assertEquals(3, b.seen.size)
    }

    @Test fun loopSurvivesFailingTool() = runBlocking {
        val failing = object : ToolRegistry {
            override fun definitions() = listOf(ToolDef("calc", "", JsonObject(emptyMap())))
            override suspend fun execute(call: Chunk.ToolCall): String = error("boom")
        }
        val b = Scripted(listOf(listOf(Chunk.ToolCall("1", "calc", "{}")), listOf(Chunk.Text("ok"))))
        ToolLoop.run(b, baseReq, failing).toList()
        assertTrue(b.seen[1].messages.last().content.contains("boom"))
    }

    @Test fun emptyRegistryNoLoop() = runBlocking {
        val b = Scripted(listOf(listOf(Chunk.Text("plain"))))
        val out = ToolLoop.run(b, baseReq, EmptyToolRegistry).toList()
        assertEquals(listOf<Chunk>(Chunk.Text("plain")), out)
        assertEquals(1, b.seen.size)
        assertTrue(b.seen[0].tools.isEmpty())
    }
}
