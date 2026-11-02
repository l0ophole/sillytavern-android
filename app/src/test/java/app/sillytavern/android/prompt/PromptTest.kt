package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import org.junit.Assert.*
import org.junit.Test

class PromptTest {
    private val card = CharacterCard(name = "Ann", description = "{{char}} is kind to {{user}}.", firstMessage = "Hi")
    private fun input(history: List<ChatMessage>, ctx: Int = 1000, mode: GenerationMode = GenerationMode.NORMAL) =
        PromptInput(card, Persona(name = "Bob"), PromptPreset(), history, ctx, 100, ApproxTokenCounter, mode)

    @Test fun systemExpandsMacrosAndRolesMap() {
        val p = PromptBuilder.build(input(listOf(ChatMessage.assistant("Ann", "Hi"), ChatMessage.user("Bob", "Hello"))))
        assertEquals(Role.SYSTEM, p.first().role)
        assertTrue(p.any { it.role == Role.SYSTEM && it.content.contains("Ann is kind to Bob.") })
        assertEquals(listOf(Role.ASSISTANT, Role.USER), p.filter { it.role != Role.SYSTEM }.map { it.role })
    }

    @Test fun trimsOldestKeepsNewest() {
        val h = (1..50).map { ChatMessage.user("Bob", "message number $it ".repeat(10)) }
        val p = PromptBuilder.build(input(h, ctx = 600))
        val bodies = p.filter { it.role != Role.SYSTEM }
        assertTrue(bodies.size < 50)
        assertTrue(bodies.last().content.contains("number 50"))
    }

    @Test fun alwaysKeepsLastMessageEvenIfHuge() {
        val p = PromptBuilder.build(input(listOf(ChatMessage.user("Bob", "x".repeat(20000))), ctx = 300))
        assertEquals(1, p.count { it.role == Role.USER })
    }

    @Test fun impersonateFlipsRolesAndAddsInstruction() {
        val p = PromptBuilder.build(input(listOf(ChatMessage.user("Bob", "q"), ChatMessage.assistant("Ann", "a")), mode = GenerationMode.IMPERSONATE))
        assertEquals(Role.ASSISTANT, p.first { it.role != Role.SYSTEM }.role)
        assertTrue(p.last().content.contains("point of view of Bob"))
    }

    @Test fun textPromptEndsWithCharPrefix() {
        val t = PromptBuilder.buildText(input(listOf(ChatMessage.user("Bob", "Hello"))), null, app.sillytavern.android.prompt.model.ContextTemplate()).text
        assertTrue(t.contains("Bob: Hello")); assertTrue(t.endsWith("Ann:"))
    }

    @Test fun tiktokenCountsExactly() = assertEquals(1, TiktokenCounter().count("hello"))

    @Test fun textCompletionStoryStringGetsWiVars() {
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(before = "B", after = "A")
        val ctx = app.sillytavern.android.prompt.model.ContextTemplate(storyString = "{{wiBefore}}|{{wiAfter}}")
        val out = PromptBuilder.buildText(input(listOf(ChatMessage.user("Bob", "hey"))).copy(worldInfo = wi), null, ctx)
        assertTrue(out.text, out.text.contains("B|A"))
    }
}
