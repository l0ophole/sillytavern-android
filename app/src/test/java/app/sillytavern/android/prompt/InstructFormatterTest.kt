package app.sillytavern.android.prompt

import app.sillytavern.android.prompt.model.*
import org.junit.Assert.*
import org.junit.Test

class InstructFormatterTest {
    private fun res(n: String) = javaClass.getResource("/presets/$n")!!.readText()
    private val chatMl = PresetJson.decode(InstructTemplate.serializer(), res("instruct-ChatML.json"))
    private val ctxChatMl = PresetJson.decode(ContextTemplate.serializer(), res("context-ChatML.json"))
    private fun parts(vararg h: PromptMessage, vars: Map<String, String> = mapOf("system" to "Sys")) =
        TextPromptParts(vars, history = h.toList())
    private val user = { t: String -> PromptMessage(Role.USER, t) }
    private val asst = { t: String -> PromptMessage(Role.ASSISTANT, t) }

    @Test fun chatMlTwoTurns() {
        val f = InstructFormatter.format(parts(user("Hi")), chatMl, ctxChatMl, "Bob", "Ann", GenerationMode.NORMAL)
        assertEquals(
            "<|im_start|>system\nSys<|im_end|>\n<|im_start|>user\nBob: Hi<|im_end|>\n<|im_start|>assistant\nAnn:",
            f.text,
        )
        assertTrue(f.stopStrings.contains("<|im_end|>"))
    }

    @Test fun noInstructFallsBackToNameLines() {
        val f = InstructFormatter.format(parts(user("Hello")), null, ContextTemplate(), "Bob", "Ann", GenerationMode.NORMAL)
        assertTrue(f.text.contains("Bob: Hello\n"))
        assertTrue(f.text.endsWith("Ann:"))
    }

    @Test fun emptySequencesProduceNoStrayNewlines() {
        val blank = InstructTemplate(namesBehavior = "none")
        val f = InstructFormatter.format(parts(user("Hi"), asst("Yo"), vars = emptyMap()), blank, ContextTemplate(), "Bob", "Ann", GenerationMode.NORMAL)
        assertEquals("HiYo", f.text)
        assertFalse(f.text.contains("null"))
    }

    @Test fun lastOutputSequenceUsedOnFinalPrompt() {
        val alpaca = InstructTemplate(
            inputSequence = "### Instruction:", outputSequence = "### Response:",
            lastOutputSequence = "### Response (2 paragraphs):", namesBehavior = "none", inputSuffix = "\n\n", outputSuffix = "\n\n",
        )
        val f = InstructFormatter.format(parts(user("Q"), vars = emptyMap()), alpaca, ContextTemplate(), "Bob", "Ann", GenerationMode.NORMAL)
        assertTrue(f.text.endsWith("### Response (2 paragraphs):\n"))
        assertFalse(f.text.contains("### Response:"))
    }

    @Test fun continueLeavesAssistantOpen() {
        val f = InstructFormatter.format(parts(user("Hi"), asst("Well")), chatMl, ctxChatMl, "Bob", "Ann", GenerationMode.CONTINUE)
        assertTrue(f.text.endsWith("<|im_start|>assistant\nAnn: Well"))
    }

    @Test fun impersonateEndsWithUserTurn() {
        // PromptBuilder flips roles in impersonate mode: char's message arrives as USER.
        val f = InstructFormatter.format(parts(asst("my line"), user("char line")), chatMl, ctxChatMl, "Bob", "Ann", GenerationMode.IMPERSONATE)
        assertTrue(f.text.contains("<|im_start|>user\nBob: my line<|im_end|>"))
        assertTrue(f.text.contains("<|im_start|>assistant\nAnn: char line<|im_end|>"))
        assertTrue(f.text.endsWith("<|im_start|>user\nBob:"))
    }

    @Test fun stopStringsDeduped() {
        val f = InstructFormatter.format(parts(user("Hi")), chatMl, ctxChatMl, "Bob", "Ann", GenerationMode.NORMAL)
        assertEquals(f.stopStrings.size, f.stopStrings.toSet().size)
        assertTrue(f.stopStrings.contains("<|im_start|>user"))
        assertTrue(f.stopStrings.contains("\nBob:"))
        assertTrue(f.stopStrings.none { it.isBlank() })
    }

    @Test fun macroInSequenceExpanded() {
        val t = InstructTemplate(inputSequence = "[{{user}}]", outputSequence = "[{{char}}]", namesBehavior = "none", wrap = false)
        val f = InstructFormatter.format(parts(user("hi"), vars = emptyMap()), t, ContextTemplate(), "Bob", "Ann", GenerationMode.NORMAL)
        assertEquals("[Bob]hi[Ann]", f.text)
    }
}
