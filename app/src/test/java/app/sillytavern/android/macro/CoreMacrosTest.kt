package app.sillytavern.android.macro

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import java.time.Instant
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CoreMacrosTest {
    private val now = LocalDateTime.of(2026, 11, 2, 18, 31, 5)
    private val ctx = MacroContext(user = "Al", char = "Bea", description = "desc", now = now, random = Random(1),
        creatorNotes = "notes", maxPrompt = 4096, maxResponse = 300, model = "gpt-x", input = "typed")
    private fun x(s: String, c: MacroContext = ctx) = Macros.expand(s, c)

    @Test fun cardFieldMacros() {
        assertEquals("desc|desc|notes", x("{{charDescription}}|{{description}}|{{creatorNotes}}"))
        assertEquals("gpt-x typed", x("{{model}} {{input}}"))
    }
    @Test fun groupMacrosSingleChat() = assertEquals("Bea Al", x("{{group}} {{notChar}}"))
    @Test fun spaceAndNewlineCounts() { assertEquals("\n\n", x("{{newline::2}}")); assertEquals("a   b", x("a{{space::3}}b")) }
    @Test fun reverseMacro() = assertEquals("cba", x("{{reverse::abc}}"))
    @Test fun ifElseTrueFalse() {
        assertEquals("A", x("{{if 1}}A{{else}}B{{/if}}"))
        assertEquals("B", x("{{if }}A{{else}}B{{/if}}"))
        assertEquals("B", x("{{if false}}A{{else}}B{{/if}}"))
        assertEquals("", x("{{if 0}}A{{/if}}"))
    }
    @Test fun ifNegation() { assertEquals("B", x("{{if !yes}}A{{else}}B{{/if}}")); assertEquals("A", x("{{if !false}}A{{else}}B{{/if}}")) }
    @Test fun ifWithNestedMacroCondition() = assertEquals("hi Al", x("{{if {{user}}}}hi {{user}}{{else}}no{{/if}}"))
    @Test fun nestedIfs() = assertEquals("in", x("{{if 1}}{{if 1}}in{{else}}x{{/if}}{{else}}out{{/if}}"))
    @Test fun ifWithoutCloseStaysLiteral() = assertEquals("{{if 1}}A", x("{{if 1}}A"))
    @Test fun randomColonAndDoubleColon() {
        assertTrue(x("{{random::a::b::c}}") in listOf("a", "b", "c"))
        assertTrue(x("{{random:a,b}}") in listOf("a", "b"))
    }
    @Test fun pickIsStable() { val a = x("{{pick::a::b::c::d}}"); assertEquals(a, x("{{pick::a::b::c::d}}")) }
    @Test fun rollDice() { val v = x("{{roll:2d6}}").toInt(); assertTrue(v in 2..12); assertTrue(x("{{roll::20}}").toInt() in 1..20) }
    @Test fun datetimeformatTokens() = assertEquals("2026-11-02 18:31", x("{{datetimeformat::YYYY-MM-DD HH:mm}}"))
    @Test fun timeUtcOffset() = assertEquals("20:31", x("{{time_UTC+2}}", ctx.copy(zone = java.time.ZoneOffset.UTC)))
    @Test fun timeDiffHumanized() = assertEquals("2 hours ago", x("{{timeDiff::2026-11-02 16:31:05::2026-11-02 18:31:05}}"))
    @Test fun idleDuration() {
        assertEquals("just now", x("{{idleDuration}}"))
        val c = ctx.copy(idleSince = Instant.now().minusSeconds(3 * 3600 + 5))
        assertEquals("3 hours", x("{{idleDuration}}", c))
    }
    @Test fun maxContextAlias() = assertEquals("4096 4096 300 300", x("{{maxPrompt}} {{maxPromptTokens}} {{maxResponse}} {{maxResponseTokens}}"))
    @Test fun originalOnlyOnce() { val c = ctx.copy(originalText = "ORIG"); assertEquals("ORIG|", x("{{original}}|{{original}}", c)) }
    @Test fun noopAndBanned() = assertEquals("ab", x("a{{noop}}{{banned::x}}b"))
}
