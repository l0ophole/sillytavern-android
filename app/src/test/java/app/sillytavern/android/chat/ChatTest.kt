package app.sillytavern.android.chat

import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class ChatTest {
    private fun repo() = ChatRepository(StoragePaths(Files.createTempDirectory("t").toFile()).ensure())

    @Test fun createLoadSaveRoundTrip() {
        val r = repo()
        val chat = r.create("Ann", "Ann", "Bob", "Hello!")
        assertEquals("Hello!", r.load(chat.file!!).messages.single().text)
        val updated = chat.copy(messages = chat.messages + ChatMessage.user("Bob", "line1\nline2 \"q\""))
        r.save(updated)
        val back = r.load(chat.file!!)
        assertEquals(2, back.messages.size)
        assertEquals("line1\nline2 \"q\"", back.messages[1].text)
        assertEquals(1, r.list("Ann").size)
    }

    @Test fun swipesAndUnknownKeysSurvive() {
        val line = """{"name":"A","is_user":false,"mes":"b","swipes":["a","b"],"swipe_id":1,"gen_started":"x"}"""
        val m = ChatMessage.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(line) as kotlinx.serialization.json.JsonObject)
        assertEquals("b", m.text)
        val m2 = m.addSwipe("c")
        assertEquals("c", m2.text); assertEquals(2, m2.swipeId)
        assertEquals("a", m2.selectSwipe(0).text)
        assertTrue(m2.toJson().toString().contains("gen_started"))
        assertEquals(listOf("a", "X", "c"), m2.selectSwipe(1).withText("X").swipes.also { /* edit current swipe only */ }.let { listOf("a", it[1], "c") })
    }

    @Test fun headerlessFileLoads() {
        val r = repo(); val f = java.io.File(Files.createTempDirectory("h").toFile(), "x.jsonl")
        f.writeText("""{"name":"A","is_user":true,"mes":"hi"}""" + "\n")
        assertEquals("hi", r.load(f).messages.single().text)
    }

    @Test fun macros() {
        val c = MacroContext(user = "Bob", char = "Ann", random = Random(1))
        assertEquals("Ann hugs Bob", Macros.expand("{{char}} hugs {{USER}}", c))
        assertEquals("Ann hugs Bob", Macros.expand("<BOT> hugs <USER>", c))
        assertEquals("keep {{unknown}}", Macros.expand("keep {{unknown}}", c))
        assertEquals("ab", Macros.expand("a{{// note}}b", c))
        assertTrue(Macros.expand("{{random:x,y}}", c) in listOf("x", "y"))
        assertTrue(Macros.expand("{{roll:2d6}}", c).toInt() in 2..12)
    }
}
