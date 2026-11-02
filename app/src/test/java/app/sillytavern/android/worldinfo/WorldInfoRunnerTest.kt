package app.sillytavern.android.worldinfo

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.*
import org.junit.Assert.*
import org.junit.Test

class WorldInfoRunnerTest {
    private val card = CharacterCard(name = "Ann")
    private fun input(h: List<ChatMessage>) = PromptInput(card, Persona(name = "Bob"), PromptPreset(), h, 10000, 100, ApproxTokenCounter)
    private fun entry(vararg k: String, block: WorldEntry.() -> WorldEntry = { this }) =
        ScanEntry("b", WorldEntry(uid = 1, key = k.toList(), content = "LORE").block())
    private fun run(e: ScanEntry, h: List<ChatMessage>, st: WorldInfoSettings = WorldInfoSettings(), trigger: String = "normal") =
        WorldInfoRunner.run(listOf(e), h, input(h), st, trigger, TimedState(), false).first.before

    @Test fun includeNamesPrefixesScanText() {
        val h = listOf(ChatMessage.user("Bob", "hello"))
        assertEquals("LORE", run(entry("Bob: hello"), h))
        assertEquals("", run(entry("Bob: hello"), h, WorldInfoSettings(includeNames = false)))
    }
    @Test fun keysMacroExpanded() = assertEquals("LORE", run(entry("{{user}} waves"), listOf(ChatMessage.user("Bob", "bob waves"))))
    @Test fun triggerContinue() {
        val e = entry("a") { copy(triggers = listOf("continue")) }
        val h = listOf(ChatMessage.user("Bob", "a"))
        assertEquals("", run(e, h)); assertEquals("LORE", run(e, h, trigger = "continue"))
    }
    @Test fun noEntriesIsEmpty() = assertEquals(WorldInfoPrompt(), WorldInfoRunner.run(emptyList(), emptyList(), input(emptyList()), WorldInfoSettings(), "normal", TimedState(), false).first)
}
