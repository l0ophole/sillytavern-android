package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.model.ContextTemplate
import org.junit.Assert.*
import org.junit.Test

class ExtensionPromptTest {
    private val hist = listOf(
        ChatMessage.assistant("Al", "one"), ChatMessage.user("Me", "two"),
        ChatMessage.assistant("Al", "three"), ChatMessage.user("Me", "four"),
    )
    private fun input(vararg e: ExtensionPrompt) = PromptInput(
        CharacterCard(name = "Al"), Persona(name = "Me"), PromptPreset(), hist, 4000, 100, ApproxTokenCounter,
        promptManager = DefaultPresets.promptManager(), extensionPrompts = e.toList(),
    )

    @Test fun inChatDepthZeroIsLast() {
        val out = PromptBuilder.build(input(ExtensionPrompt("n", "NOTE", ExtensionPrompt.IN_CHAT, depth = 0)))
        assertEquals("NOTE", out.last().content)
        assertEquals("four", out[out.lastIndex - 1].content)
    }

    @Test fun inChatDepthTwoSitsBeforeLastTwoMessages() {
        val out = PromptBuilder.build(input(ExtensionPrompt("n", "NOTE", ExtensionPrompt.IN_CHAT, depth = 2, role = 1)))
        val i = out.indexOfFirst { it.content == "NOTE" }
        assertEquals(Role.USER, out[i].role)
        assertEquals(listOf("three", "four"), out.drop(i + 1).map { it.content })
    }

    @Test fun inPromptGoesAfterMainAndBeforePromptBeforeIt() {
        val out = PromptBuilder.build(input(
            ExtensionPrompt("a", "AFTER", ExtensionPrompt.IN_PROMPT), ExtensionPrompt("b", "BEFORE", ExtensionPrompt.BEFORE_PROMPT)))
        val c = out.map { it.content }
        val main = c.indexOfFirst { it.startsWith("Write Al's next reply") }
        assertTrue(main > 0)
        assertEquals("BEFORE", c[main - 1])
        assertEquals("AFTER", c[main + 1])
    }

    @Test fun macrosInExtensionPromptsAreExpanded() {
        val out = PromptBuilder.build(input(ExtensionPrompt("n", "{{char}} and {{user}}", ExtensionPrompt.IN_CHAT, depth = 0)))
        assertEquals("Al and Me", out.last().content)
    }

    @Test fun textCompletionUsesAnchorsAndDepth() {
        val ctx = ContextTemplate(storyString = "{{#if anchorBefore}}B:{{anchorBefore}}\n{{/if}}{{#if anchorAfter}}A:{{anchorAfter}}\n{{/if}}{{description}}")
        val out = PromptBuilder.buildText(input(
            ExtensionPrompt("a", "AFT", ExtensionPrompt.IN_PROMPT), ExtensionPrompt("b", "BEF", ExtensionPrompt.BEFORE_PROMPT),
            ExtensionPrompt("c", "DEEP", ExtensionPrompt.IN_CHAT, depth = 1)), null, ctx).text
        assertTrue(out, out.contains("B:BEF"))
        assertTrue(out, out.contains("A:AFT"))
        assertTrue(out, out.indexOf("DEEP") in out.indexOf("three")..out.indexOf("four"))
    }

    @Test fun noExtensionPromptsChangesNothing() {
        assertEquals(PromptBuilder.build(input()), PromptBuilder.build(input()))
        assertFalse(PromptBuilder.build(input()).any { it.content == "" })
    }
}
