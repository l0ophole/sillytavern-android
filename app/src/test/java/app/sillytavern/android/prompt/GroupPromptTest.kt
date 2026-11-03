package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.model.ContextTemplate
import org.junit.Assert.*
import org.junit.Test

class GroupPromptTest {
    private val hist = listOf(
        ChatMessage.assistant("Alice", "hi"), ChatMessage.assistant("Bob", "yo"),
        ChatMessage.narrator("a door creaks"), ChatMessage.user("Me", "hey"),
    )
    private fun input(group: Boolean, pmCfg: (app.sillytavern.android.prompt.model.PromptManagerPreset) -> app.sillytavern.android.prompt.model.PromptManagerPreset = { it }) =
        PromptInput(CharacterCard(name = "Alice"), Persona(name = "Me"), PromptPreset(), hist, 4000, 100, ApproxTokenCounter,
            promptManager = pmCfg(DefaultPresets.promptManager()), groupNames = group)

    private fun tail(ms: List<PromptMessage>) = ms.takeLast(4).map { it.content }

    @Test fun groupNamesPrefixAssistantAndUserMessages() =
        assertEquals(listOf("Alice: hi", "Bob: yo", "a door creaks", "Me: hey"), tail(PromptBuilder.build(input(true))).let { t -> t })

    @Test fun groupNamesOffLeavesContent() = assertEquals(listOf("hi", "yo", "a door creaks", "hey"), tail(PromptBuilder.build(input(false))))

    @Test fun namesBehaviorContentUsesMessageNameInGroup() {
        val t = tail(PromptBuilder.build(input(true) { it.copy(namesBehavior = 2) }))
        assertEquals(listOf("Alice: hi", "Bob: yo", "a door creaks", "Me: hey"), t)
    }

    @Test fun groupNamesTextCompletionWithoutInstruct() {
        val out = PromptBuilder.buildText(input(true), null, ContextTemplate())
        assertTrue(out.text, out.text.contains("Bob: yo"))
        assertTrue(out.text, out.text.contains("Me: hey"))
        assertFalse(PromptBuilder.buildText(input(false), null, ContextTemplate()).text.contains("Bob: yo"))
    }
}
