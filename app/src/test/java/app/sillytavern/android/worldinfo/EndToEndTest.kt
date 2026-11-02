package app.sillytavern.android.worldinfo

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.*
import org.junit.Assert.*
import org.junit.Test

class EndToEndTest {
    private val book = WorldJson.decode("Eldoria", javaClass.getResource("/worldinfo/Eldoria.json")!!.readText())
    private val card = CharacterCard(name = "Seraphina", description = "A guardian.")
    private fun prompt(userText: String, settings: WorldInfoSettings = WorldInfoSettings(), pos: Int? = null): List<String> {
        val entries = book.entries.map { if (pos != null) it.copy(position = pos) else it }.map { ScanEntry(book.name, it) }
        val history = listOf(ChatMessage.assistant("Seraphina", "Welcome."), ChatMessage.user("Bob", userText))
        val base = PromptInput(card, Persona(name = "Bob"), PromptPreset(), history, 8000, 300, ApproxTokenCounter)
        val (wi, _) = WorldInfoRunner.run(entries, history, base, settings, "normal", TimedState(), false)
        return PromptManagerBuilder.build(base.copy(worldInfo = wi)).map { it.content }
    }

    @Test fun eldoriaActivatesThroughPromptBuilder() {
        val key = book.entries.first { it.key.isNotEmpty() && !it.constant }.let { it.key.first() }
        val hit = prompt("Tell me about $key please")
        val body = book.entries.first { it.key.contains(key) }.content.replace("{{user}}", "Bob").replace("{{char}}", "Seraphina")
        assertEquals(1, hit.count { it.contains(body.take(40)) })
        assertTrue(prompt("completely unrelated chatter").none { it.contains(body.take(40)) })
    }

    @Test fun atDepthEntryLandsInsideHistory() {
        val key = book.entries.first { it.key.isNotEmpty() && !it.constant }.key.first()
        val out = prompt("about $key", pos = WiPosition.AT_DEPTH)
        val idx = out.indexOfFirst { it.contains("Eldoria") && it.length > 100 }
        assertTrue("entry present", idx >= 0)
        assertTrue("before the last history message", idx < out.lastIndex)
    }
}
