package app.sillytavern.android.macro

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import org.junit.Assert.*
import org.junit.Test

class MacroEngineTest {
    private val ctx = MacroContext(user = "Al", char = "Bea")
    private fun x(s: String, c: MacroContext = ctx) = Macros.expand(s, c)

    @Test fun plainTextUntouched() = assertEquals("hello { } world", x("hello { } world"))
    @Test fun nestedMacroInArg() = assertEquals("Al", x("{{random::{{user}}::{{user}}}}"))
    @Test fun unterminatedBraceKept() = assertEquals("a {{user b", x("a {{user b"))
    @Test fun unknownMacroKept() = assertEquals("keep {{nope::1}}", x("keep {{nope::1}}"))
    @Test fun commentRemoved() = assertEquals("ab", x("a{{// note {{user}}}}b"))
    @Test fun legacyRandomColon() = assertTrue(x("{{random:x,y}}") in listOf("x", "y"))
    @Test fun trimRemovesSurroundingWhitespace() = assertEquals("ab", x("a \n{{trim}}\n b"))
    @Test fun caseInsensitiveNames() = assertEquals("Al and Bea", x("{{USER}} and {{Char}}"))
    @Test fun legacyAngleTokens() = assertEquals("Bea hugs Al", x("<BOT> hugs <USER>"))
    @Test fun scopedPairMatchedByNesting() {
        MacroRegistry.register(MacroDef(listOf("wrapx"), scoped = true) { "[" + it.scopeContent + "]" })
        assertEquals("[1{{wrapx}}2{{/wrapx}}]", MacroEngine.expandPass("{{wrapx}}1{{wrapx}}2{{/wrapx}}{{/wrapx}}", ctx))
    }
    @Test fun recursionBounded() {
        val c = ctx.copy(outlets = mapOf("a" to "{{outlet::a}}x"))
        assertTrue(x("{{outlet::a}}", c).length < 100)
    }
    @Test fun parserSplitsArgs() {
        val n = MacroParser.parse("a{{foo::b::{{c}}}}d")
        assertEquals(3, n.size)
        val call = n[1] as MacroNode.Call
        assertEquals("foo", call.name); assertEquals(2, call.args.size)
    }
}
