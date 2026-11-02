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

class ProvidersTest {
    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    private fun run(provider: String, reasoning: ReasoningSettings = ReasoningSettings(), model: String = "m"): Pair<okhttp3.mockwebserver.RecordedRequest, JsonObject> {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val p = ConnectionProfile(
            type = BackendType.OPENAI, provider = provider, baseUrl = server.url("/v1").toString(), model = model,
            sampler = SamplerSettings(stream = false), reasoning = reasoning,
        )
        runBlocking { Backends.forType(BackendType.OPENAI).generate(GenerationRequest(p, "K", listOf(PromptMessage(Role.USER, "hi")), "")).toList() }
        val r = server.takeRequest()
        return r to Json.parseToJsonElement(r.body.readUtf8()).jsonObject
    }

    @Test fun registryHasUniqueIds() = assertEquals(Providers.all.size, Providers.all.map { it.id }.toSet().size)

    @Test fun byIdFallsBackToCustom() = assertEquals("custom", Providers.byId("nope").id)

    @Test fun mismatchedTypeFallsBackToGenericOfType() {
        assertEquals("textgen", Providers.forProfile(ConnectionProfile(type = BackendType.TEXT_COMPLETION, provider = "openrouter")).id)
    }

    @Test fun openRouterSendsAttributionHeaders() {
        val (r, _) = run("openrouter")
        assertEquals("SillyTavern Android", r.getHeader("X-Title"))
        assertNotNull(r.getHeader("HTTP-Referer"))
        assertEquals("Bearer K", r.getHeader("Authorization"))
    }

    @Test fun openRouterReasoningBody() {
        assertEquals("high", run("openrouter", ReasoningSettings("high")).second["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals(2000, run("openrouter", ReasoningSettings("high", 2000)).second["reasoning"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun openAiReasoningEffort() {
        assertEquals("low", run("openai", ReasoningSettings("low")).second["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("minimal", run("openai", ReasoningSettings("min")).second["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun autoEffortSendsNothing() {
        val b = run("openai").second
        assertNull(b["reasoning_effort"]); assertNull(b["reasoning"])
    }

    @Test fun deepSeekReasonerOmitsSamplers() {
        val b = run("deepseek", model = "deepseek-reasoner").second
        assertNull(b["temperature"]); assertNull(b["top_p"]); assertNotNull(b["max_tokens"])
        assertNotNull(run("deepseek", model = "deepseek-chat").second["temperature"])
    }
}
