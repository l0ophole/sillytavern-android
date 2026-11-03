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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lets real-IO work finish: pumps the test scheduler until [done] holds (or 5 s pass). */
suspend fun TestScope.waitFor(done: () -> Boolean) {
    repeat(500) { advanceUntilIdle(); if (done()) return; withContext(Dispatchers.Default) { Thread.sleep(10) } }
    advanceUntilIdle()
}

class TextTranslatorTest {
    private val server = MockWebServer().also { it.start() }
    @After fun stop() = server.shutdown()

    private fun translator(s: TranslateSettings, key: String? = null): TextTranslator {
        return TextTranslator({ s }, { key }, googleUrl = server.url("/translate_a/single").toString())
    }

    @Test fun googleSendsFormAndJoinsSegments() = runBlocking {
        server.enqueue(MockResponse().setBody("""[[["Hallo ","Hello ",null,null,1],["Welt","world",null,null,1]],null,"en"]"""))
        val t = translator(TranslateSettings(provider = TranslateProvider.GOOGLE))
        assertEquals("Hallo Welt", t.translate("Hello world", "de"))
        val r = server.takeRequest()
        assertEquals("POST", r.method)
        assertTrue(r.path!!, r.path!!.contains("tl=de") && r.path!!.contains("client=gtx"))
        assertEquals("q=Hello%20world", r.body.readUtf8())
    }

    @Test fun libreTranslateRequest() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"translatedText":"Bonjour"}"""))
        val t = translator(TranslateSettings(provider = TranslateProvider.LIBRETRANSLATE, libreUrl = server.url("/").toString()), "k")
        assertEquals("Bonjour", t.translate("Hello", "fr"))
        val r = server.takeRequest()
        assertEquals("/translate", r.path)
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("fr", b["target"]!!.jsonPrimitive.content)
        assertEquals("k", b["api_key"]!!.jsonPrimitive.content)
    }

    @Test fun deeplRequestUsesUppercaseCodeAndKeyHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"translations":[{"detected_source_language":"EN","text":"Hallo"}]}"""))
        val t = translator(TranslateSettings(provider = TranslateProvider.DEEPL, deeplUrl = server.url("/").toString()), "abc")
        assertEquals("Hallo", t.translate("Hello", "de"))
        val r = server.takeRequest()
        assertEquals("/v2/translate", r.path)
        assertEquals("DeepL-Auth-Key abc", r.getHeader("Authorization"))
        val b = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals("DE", b["target_lang"]!!.jsonPrimitive.content)
    }

    @Test fun httpErrorBecomesBackendException() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val t = translator(TranslateSettings())
        val e = runCatching { runBlocking { t.translate("x", "de") } }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("403"))
    }

    @Test fun llmProviderUsesTheCallback() = runBlocking {
        val t = TextTranslator({ TranslateSettings(provider = TranslateProvider.LLM) }, { null })
        var prompt = ""
        assertEquals("Hola", t.translate("Hello", "es") { prompt = it; "  Hola \n" })
        assertTrue(prompt, prompt.contains("into es") && prompt.endsWith("Hello"))
        assertTrue(runCatching { runBlocking { t.translate("Hello", "es") } }.isFailure) // no connection
    }

    @Test fun longTextIsSplitAndKeepsLineBreaks() = runBlocking {
        val para = "a".repeat(2000)
        val text = "$para\n\n$para\n\n$para"
        val parts = TextTranslator.chunk(text, 3500)
        assertTrue(parts.size >= 2 && parts.all { it.length <= 3500 })
        assertEquals(text, parts.joinToString(""))
        repeat(3) { server.enqueue(MockResponse().setBody("""[[["X","y"]]]""")) }
        val t = translator(TranslateSettings())
        val out = t.translate(text, "de")
        assertTrue(out, out.contains("\n\n") || out.startsWith("X"))
        assertTrue(server.requestCount >= 2)
    }

    @Test fun blankTextIsNotSent() = runBlocking {
        val t = translator(TranslateSettings())
        assertEquals("  ", t.translate("  ", "de"))
        assertEquals(0, server.requestCount)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranslateControllerTest : ChatFeaturesTestBase() {
    private fun enable(mode: TranslateMode) {
        cfg = cfg.copy(tools = ToolsSettings(translate = TranslateSettings(mode = mode, provider = TranslateProvider.LLM, target = "de", internal = "en")))
        translatorSvc = TextTranslator({ cfg.tools.translate }, { null })
    }

    @Test fun displayOverrideFollowsTheText() {
        val m = ChatMessage.assistant("Ann", "Hello").withDisplayText("Hallo")
        assertEquals("Hallo", m.displayOverride)
        assertNull(m.withText("Hello there").displayOverride)
        assertNull(m.withDisplayText(null).displayOverride)
    }

    @Test fun responsesAreTranslatedForDisplayOnly() = runTest {
        enable(TranslateMode.RESPONSES)
        queuedReplies += "I am here."; queuedReplies += "Ich bin hier."
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hi"); advanceUntilIdle()
        val last = c.state.value.chat!!.messages.last()
        assertEquals("I am here.", last.text)
        assertEquals("Ich bin hier.", last.displayOverride)
        assertEquals("Ich bin hier.", c.displayText(last))
        assertNull(c.state.value.error)
    }

    @Test fun inputsAreTranslatedForTheModelAndKeepTheTypedText() = runTest {
        enable(TranslateMode.INPUTS)
        queuedReplies += "Hello there"; queuedReplies += "reply"
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("Hallo du"); advanceUntilIdle()
        val user = c.state.value.chat!!.messages.first { it.isUser }
        assertEquals("Hello there", user.text)
        assertEquals("Hallo du", user.displayOverride)
        assertTrue(lastContents().joinToString("\n").contains("Hello there"))
        assertFalse(lastContents().joinToString("\n").contains("Hallo du"))
    }

    @Test fun failedTranslationOnlyShowsANotice() = runTest {
        enable(TranslateMode.RESPONSES)
        translatorSvc = TextTranslator({ cfg.tools.translate.copy(provider = TranslateProvider.LIBRETRANSLATE, libreUrl = "http://127.0.0.1:1") }, { null })
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hi")
        waitFor { c.state.value.notice != null }
        assertEquals("reply", c.state.value.chat!!.messages.last().text)
        assertTrue(c.state.value.notice!!.startsWith("Translation failed"))
    }

    @Test fun messageActionTogglesTheTranslation() = runTest {
        enable(TranslateMode.OFF)
        queuedReplies += "Good day"
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hi"); advanceUntilIdle()
        val idx = c.state.value.chat!!.messages.lastIndex
        queuedReplies += "Guten Tag"
        c.toggleTranslation(idx); advanceUntilIdle()
        assertEquals("Guten Tag", c.state.value.chat!!.messages[idx].displayOverride)
        c.toggleTranslation(idx)
        assertNull(c.state.value.chat!!.messages[idx].displayOverride)
    }
}
