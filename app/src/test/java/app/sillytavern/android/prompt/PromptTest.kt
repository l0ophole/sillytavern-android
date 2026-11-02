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
        assertTrue(p.first().content.contains("Ann is kind to Bob."))
        assertEquals(listOf(Role.ASSISTANT, Role.USER), p.drop(1).map { it.role })
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
        assertEquals(Role.ASSISTANT, p[1].role)
        assertTrue(p.last().content.contains("Bob's point of view"))
    }

    @Test fun textPromptEndsWithCharPrefix() {
        val p = PromptBuilder.build(input(listOf(ChatMessage.user("Bob", "Hello"))))
        val t = PromptBuilder.toTextPrompt(p, "Bob", "Ann", GenerationMode.NORMAL)
        assertTrue(t.contains("Bob: Hello")); assertTrue(t.endsWith("Ann:"))
    }

    @Test fun tiktokenCountsExactly() = assertEquals(1, TiktokenCounter().count("hello"))
}
