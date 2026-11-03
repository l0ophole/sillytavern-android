package app.sillytavern.android.tools

import app.sillytavern.android.chat.ChatFeaturesTestBase
import app.sillytavern.android.chat.ChatMessage
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

class WebSearchTest {
    private val server = MockWebServer().also { it.start() }
    @After fun stop() = server.shutdown()
    private fun svc(s: SearchSettings, key: String? = null): WebSearchService {
        val base = server.url("/").toString().trimEnd('/')
        return WebSearchService({ s }, { key }, braveUrl = "$base/brave", tavilyUrl = "$base/tavily", serperUrl = "$base/serper")
    }

    @Test fun searxngQueryAndParsing() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"Cats","url":"https://c.example","content":"All about  cats"},{"title":"Dogs","url":"https://d.example","content":""}]}"""))
        val s = SearchSettings(provider = SearchProvider.SEARXNG, searxUrl = server.url("/").toString(), resultCount = 5)
        val out = svc(s).search("cat facts")
        val r = server.takeRequest()
        assertEquals("/search?q=cat%20facts&format=json", r.path)
        assertEquals("1. Cats (https://c.example)\n   All about cats\n2. Dogs (https://d.example)", out)
    }

    @Test fun braveUsesTokenHeaderAndCount() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"web":{"results":[{"title":"T","url":"https://t","description":"D"}]}}"""))
        val found = svc(SearchSettings(provider = SearchProvider.BRAVE, resultCount = 3), "tok").results("q")
        val r = server.takeRequest()
        assertEquals("tok", r.getHeader("X-Subscription-Token"))
        assertTrue(r.path!!, r.path!!.startsWith("/brave?") && r.path!!.contains("count=3"))
        assertEquals(listOf(SearchResult("T", "https://t", "D")), found)
    }

    @Test fun tavilyAndSerperRequests() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"A","url":"https://a","content":"x"}]}"""))
        server.enqueue(MockResponse().setBody("""{"organic":[{"title":"B","link":"https://b","snippet":"y"}]}"""))
        assertEquals("A", svc(SearchSettings(provider = SearchProvider.TAVILY), "k1").results("q").single().title)
        val t = server.takeRequest()
        assertEquals("Bearer k1", t.getHeader("Authorization"))
        assertEquals("q", Json.parseToJsonElement(t.body.readUtf8()).jsonObject["query"]!!.jsonPrimitive.content)
        val serper = WebSearchService({ SearchSettings(provider = SearchProvider.SERPER, resultCount = 2) }, { "k2" }, serperUrl = server.url("/serper").toString())
        assertEquals("https://b", serper.results("q").single().url)
        val s = server.takeRequest()
        assertEquals("k2", s.getHeader("X-API-KEY"))
        assertEquals(2, Json.parseToJsonElement(s.body.readUtf8()).jsonObject["num"]!!.jsonPrimitive.int)
    }

    @Test fun errorsAreReported() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        val e = runCatching { runBlocking { svc(SearchSettings(provider = SearchProvider.BRAVE)).results("q") } }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("401"))
    }

    @Test fun queryComesFromTheNewestUserMessageAndTriggers() {
        val h = listOf(ChatMessage.user("Me", "hello"), ChatMessage.assistant("Ann", "hi"), ChatMessage.user("Me", "Who won the match yesterday?"))
        assertEquals("Who won the match yesterday?", WebSearchService.queryFor(h, SearchSettings()))
        assertNull(WebSearchService.queryFor(h.dropLast(1), SearchSettings())) // last is a character line
        assertNull(WebSearchService.queryFor(h, SearchSettings(triggers = "weather, news")))
        assertEquals("Who won the match yesterday?", WebSearchService.queryFor(h, SearchSettings(triggers = "weather, MATCH")))
    }

    @Test fun promptUsesTemplateAndSkipsWhenDisabled() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"Cats","url":"https://c","content":"purr"}]}"""))
        val on = SearchSettings(enabled = true, searxUrl = server.url("/").toString(), template = "Q={{query}}\n{{text}}", depth = 3)
        val h = listOf(ChatMessage.user("Me", "cats?"))
        val p = svc(on).prompt(h)!!
        assertEquals("Q=cats?\n1. Cats (https://c)\n   purr", p.content)
        assertEquals(3, p.depth)
        assertNull(WebSearchService({ on.copy(enabled = false) }, { null }).prompt(h))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WebSearchControllerTest : ChatFeaturesTestBase() {
    private val server = MockWebServer().also { it.start() }
    @After fun stop() = server.shutdown()

    @Test fun resultsReachThePrompt() = runTest {
        server.enqueue(MockResponse().setBody("""{"results":[{"title":"Moon","url":"https://m","content":"The moon is 384400 km away"}]}"""))
        cfg = cfg.copy(tools = ToolsSettings(search = SearchSettings(enabled = true, searxUrl = server.url("/").toString())))
        searchSvc = WebSearchService({ cfg.tools.search }, { null })
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("How far is the moon?"); waitFor { server.requestCount > 0 && !c.state.value.generating && requests.isNotEmpty() }
        assertTrue(lastContents().joinToString("\n").contains("384400 km"))
    }

    @Test fun failingSearchStillAnswers() = runTest {
        cfg = cfg.copy(tools = ToolsSettings(search = SearchSettings(enabled = true, searxUrl = "http://127.0.0.1:1")))
        searchSvc = WebSearchService({ cfg.tools.search }, { null })
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hello?"); waitFor { c.state.value.notice != null }
        assertEquals("reply", c.state.value.chat!!.messages.last().text)
        assertTrue(c.state.value.notice!!.startsWith("Web search failed"))
    }
}
