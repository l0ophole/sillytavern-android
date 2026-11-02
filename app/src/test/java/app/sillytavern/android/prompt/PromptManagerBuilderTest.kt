package app.sillytavern.android.prompt

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.Persona
import app.sillytavern.android.data.PromptPreset
import app.sillytavern.android.prompt.model.*
import org.junit.Assert.*
import org.junit.Test

class PromptManagerBuilderTest {
    private val card = CharacterCard(name = "Ann", description = "Ann is kind.", personality = "warm", scenario = "a cafe", messageExample = "Ann: hi")
    private val hist = listOf(
        ChatMessage.assistant("Ann", "Hello"), ChatMessage.user("Bob", "Hey"),
        ChatMessage.assistant("Ann", "How are you"), ChatMessage.user("Bob", "Fine"),
    )

    private fun input(
        pm: PromptManagerPreset = DefaultPresets.promptManager(), history: List<ChatMessage> = hist, ctx: Int = 4000,
        mode: GenerationMode = GenerationMode.NORMAL, card: CharacterCard = this.card, sys: SystemPromptPreset? = null,
    ) = PromptInput(card, Persona(name = "Bob", description = "Bob is tall."), PromptPreset(), history, ctx, 100, ApproxTokenCounter, mode, pm, sys)

    private fun pmOf(vararg defs: PromptDefinition, order: List<PromptOrderEntry>, extra: PromptManagerPreset.() -> PromptManagerPreset = { this }) =
        PromptManagerPreset(prompts = defs.toList(), order = order, newChatPrompt = "").extra()
    private fun p(id: String, content: String, pos: Int = 0, depth: Int = 4, order: Int = 100, role: String = "system", forbid: Boolean = false) =
        PromptDefinition(id, id, role = role, content = content, injectionPosition = pos, injectionDepth = depth, injectionOrder = order, forbidOverrides = forbid)
    private val history = PromptDefinition("chatHistory", "Chat History", marker = true)
    private fun ord(vararg ids: String) = ids.map { PromptOrderEntry(it, true) }
    private fun texts(ms: List<PromptMessage>) = ms.map { it.content }

    @Test fun orderDeterminesSequence() {
        val pm = pmOf(p("a", "A"), p("b", "B"), history, order = ord("b", "chatHistory", "a"))
        val out = PromptManagerBuilder.build(input(pm, hist.take(1)))
        assertEquals(listOf("B", "Hello", "A"), texts(out))
    }

    @Test fun disabledEntrySkipped() {
        val pm = pmOf(p("a", "A"), p("b", "B"), history, order = listOf(PromptOrderEntry("a", false), PromptOrderEntry("b", true), PromptOrderEntry("chatHistory", true)))
        assertFalse(texts(PromptManagerBuilder.build(input(pm))).contains("A"))
    }

    @Test fun unknownIdentifierSkipped() {
        val pm = pmOf(p("a", "A"), history, order = ord("ghost", "a", "chatHistory"))
        assertEquals("A", PromptManagerBuilder.build(input(pm)).first().content)
    }

    @Test fun markerExpandsCardFields() {
        val out = PromptManagerBuilder.build(input())
        val all = texts(out)
        assertTrue(all.contains("Ann is kind."))
        assertTrue(all.contains("warm"))
        assertTrue(all.contains("a cafe"))
        assertTrue(all.contains("Bob is tall."))
        assertTrue(all.any { it.startsWith("[Example Chat]") && it.contains("Ann: hi") })
    }

    @Test fun absoluteInjectionAtDepth1PutsBeforeLastMessage() {
        val pm = pmOf(p("inj", "INJ", pos = 1, depth = 1), history, order = ord("inj", "chatHistory"))
        val out = texts(PromptManagerBuilder.build(input(pm)))
        assertEquals(listOf("Hello", "Hey", "How are you", "INJ", "Fine"), out)
    }

    @Test fun depthLargerThanHistoryInsertsAtStart() {
        val pm = pmOf(p("inj", "INJ", pos = 1, depth = 99), history, order = ord("inj", "chatHistory"))
        assertEquals("INJ", PromptManagerBuilder.build(input(pm)).first().content)
    }

    @Test fun depth0AppendsAtEnd() {
        val pm = pmOf(p("inj", "INJ", pos = 1, depth = 0), history, order = ord("inj", "chatHistory"))
        assertEquals("INJ", PromptManagerBuilder.build(input(pm)).last().content)
    }

    @Test fun sameDepthSameRoleJoinedByOrder() {
        val pm = pmOf(p("x", "X", pos = 1, depth = 1, order = 2), p("y", "Y", pos = 1, depth = 1, order = 1), history, order = ord("x", "y", "chatHistory"))
        assertTrue(texts(PromptManagerBuilder.build(input(pm))).contains("Y\nX"))
    }

    @Test fun cardSystemPromptOverridesMain() {
        val pm = pmOf(p("main", "preset main"), history, order = ord("main", "chatHistory"))
        assertEquals("card main", PromptManagerBuilder.build(input(pm, card = card.copy(systemPrompt = "card main"))).first().content)
    }

    @Test fun forbidOverridesBlocksCardOverride() {
        val pm = pmOf(p("main", "preset main", forbid = true), history, order = ord("main", "chatHistory"))
        assertEquals("preset main", PromptManagerBuilder.build(input(pm, card = card.copy(systemPrompt = "card main"))).first().content)
    }

    @Test fun sysPromptPresetFeedsMainAndJailbreak() {
        val pm = pmOf(p("main", "preset main"), p("jailbreak", "preset jb"), history, order = ord("main", "chatHistory", "jailbreak"))
        val out = PromptManagerBuilder.build(input(pm, sys = SystemPromptPreset(content = "sys main", postHistory = "sys jb")))
        assertEquals("sys main", out.first().content)
        assertEquals("sys jb", out.last().content)
    }

    @Test fun oversizedPromptsStillKeepLastMessage() {
        val pm = pmOf(p("huge", "x".repeat(50_000)), history, order = ord("huge", "chatHistory"))
        val out = PromptManagerBuilder.build(input(pm, ctx = 500))
        assertEquals("Fine", out.last { it.role != Role.SYSTEM }.content)
        assertEquals(1, out.count { it.role != Role.SYSTEM })
    }

    @Test fun squashMergesConsecutiveSystem() {
        val pm = pmOf(p("a", "A"), p("b", "B"), history, order = ord("a", "b", "chatHistory")) { copy(squashSystemMessages = true) }
        val out = PromptManagerBuilder.build(input(pm))
        assertEquals("A\nB", out.first().content)
        assertEquals(Role.ASSISTANT, out[1].role)
    }

    @Test fun impersonateAddsNudgeAfterHistory() {
        val out = PromptManagerBuilder.build(input(DefaultPresets.promptManager(), mode = GenerationMode.IMPERSONATE))
        assertTrue(out.last().content.contains("point of view of Bob"))
        assertEquals(Role.USER, out.first { it.role != Role.SYSTEM }.role) // roles flipped: Ann's first line is "user"
    }

    @Test fun continueAddsNudge() {
        val out = PromptManagerBuilder.build(input(mode = GenerationMode.CONTINUE))
        assertTrue(out.last().content.startsWith("[Continue your last message"))
    }

    @Test fun worldInfoMarkersFilled() {
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(before = "BEFORE", after = "AFTER")
        val t = texts(PromptManagerBuilder.build(input().copy(worldInfo = wi)))
        val d = t.indexOf("Ann is kind.")
        assertTrue(t.indexOf("BEFORE") in 0 until d)
        assertTrue(t.indexOf("AFTER") > d)
    }

    @Test fun worldInfoDepthInjectedAtDepth() {
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(depth = listOf(app.sillytavern.android.worldinfo.DepthInjection(1, 0, "LORE")))
        val t = texts(PromptManagerBuilder.build(input().copy(worldInfo = wi)))
        assertEquals("LORE", t[t.lastIndex - 1]); assertEquals("Fine", t.last())
    }

    @Test fun wiFormatApplied() {
        val pm = DefaultPresets.promptManager().copy(wiFormat = "[Lore]\n{0}")
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(before = "BEFORE")
        assertTrue("[Lore]\nBEFORE" in texts(PromptManagerBuilder.build(input(pm).copy(worldInfo = wi))))
    }

    @Test fun emptyWorldInfoChangesNothing() {
        assertEquals(PromptManagerBuilder.build(input()), PromptManagerBuilder.build(input().copy(worldInfo = app.sillytavern.android.worldinfo.WorldInfoPrompt())))
    }

    @Test fun exampleAnchorsWrapDialogueExamples() {
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(exampleTop = listOf("TOP"), exampleBottom = listOf("BOT"))
        val ex = texts(PromptManagerBuilder.build(input().copy(worldInfo = wi))).first { "Ann: hi" in it }
        assertTrue(ex.indexOf("TOP") < ex.indexOf("Ann: hi") && ex.indexOf("Ann: hi") < ex.indexOf("BOT"))
    }

    @Test fun outletMacroExpands() {
        val wi = app.sillytavern.android.worldinfo.WorldInfoPrompt(outlets = mapOf("o" to "OUTLET"))
        val pm = pmOf(p("a", "x {{outlet::o}} y {{outlet::none}}"), history, order = ord("a", "chatHistory"))
        assertEquals("x OUTLET y", texts(PromptManagerBuilder.build(input(pm, hist.take(1)).copy(worldInfo = wi))).first())
    }
}
