package app.sillytavern.android.command

import org.junit.Assert.*
import org.junit.Test

class CommandParserTest {
    private fun lit(a: Arg?) = (a as Arg.Literal).s
    private fun text(c: CommandCall) = c.unnamed.joinToString("") { (it as Arg.Literal).s }

    @Test fun simpleCommandWithArg() {
        val s = CommandParser.parse("/echo hello world")
        assertEquals("echo", s.pipeline.single().name); assertEquals("hello world", text(s.pipeline.single()))
    }
    @Test fun namedArgsBareAndQuoted() {
        val c = CommandParser.parse("""/sendas name="Big Bob" mood=happy hi there""").pipeline.single()
        assertEquals("Big Bob", lit(c.named["name"])); assertEquals("happy", lit(c.named["mood"])); assertEquals("hi there", text(c))
    }
    @Test fun pipeChain() {
        val s = CommandParser.parse("/pass a | /upper | /echo")
        assertEquals(listOf("pass", "upper", "echo"), s.pipeline.map { it.name }); assertEquals("a", text(s.pipeline[0]))
    }
    @Test fun escapedPipeKept() = assertEquals("a|b", text(CommandParser.parse("""/echo a\|b""").pipeline.single()))
    @Test fun pipeInsideQuotesKept() = assertEquals("""say "a | b" ok""", text(CommandParser.parse("""/echo say "a | b" ok""").pipeline.single()))
    @Test fun pipeInsideMacroKept() = assertEquals("{{random::a|b}}", text(CommandParser.parse("/echo {{random::a|b}}").pipeline.single()))
    @Test fun closureArg() {
        val c = CommandParser.parse("/if left=1 right=1 rule=eq else={: /echo no :} /echo yes").pipeline.single()
        assertEquals("if", c.name)
        val el = c.named["else"] as Arg.Closure
        assertEquals("echo", el.body.pipeline.single().name)
        assertEquals("/echo yes", text(c))
    }
    @Test fun unnamedClosureIsSeparatePart() {
        val c = CommandParser.parse("/times 3 {: /echo x :}").pipeline.single()
        assertTrue(c.unnamed.any { it is Arg.Closure }); assertEquals("3", (c.unnamed.first() as Arg.Literal).s.trim())
    }
    @Test fun nestedClosures() {
        val c = CommandParser.parse("/if left=1 right=1 rule=eq {: /if left=2 right=2 rule=eq {: /echo deep :} :}").pipeline.single()
        val inner = (c.unnamed.filterIsInstance<Arg.Closure>().single()).body.pipeline.single()
        assertEquals("if", inner.name); assertTrue(inner.unnamed.any { it is Arg.Closure })
    }
    @Test fun pipeInsideClosureNotSplit() {
        val c = CommandParser.parse("/run {: /echo a | /upper :} | /echo done")
        assertEquals(2, c.pipeline.size)
    }
    @Test fun commentCommand() {
        assertTrue(CommandParser.parse("// nothing here").pipeline.isEmpty())
        assertEquals(listOf("echo"), CommandParser.parse("// c | /echo hi").pipeline.map { it.name })
    }
    @Test fun isCommandRules() {
        assertTrue(CommandParser.isCommand("/echo x")); assertTrue(CommandParser.isCommand("  /echo"))
        assertFalse(CommandParser.isCommand("/ shrug")); assertFalse(CommandParser.isCommand("//")); assertFalse(CommandParser.isCommand("hello /x"))
        assertTrue(CommandParser.isCommand("// note"))
    }
    @Test fun unterminatedQuoteThrows() { assertThrows(CommandParseError::class.java) { CommandParser.parse("""/echo name="abc""") } }
    @Test fun unterminatedClosureThrows() { assertThrows(CommandParseError::class.java) { CommandParser.parse("/run {: /echo a") } }
    @Test fun emptyAfterSlashThrows() { assertThrows(CommandParseError::class.java) { CommandParser.parse("/ ") } }
    @Test fun lonelyQuoteInProseIsLiteral() = assertEquals("""5" tall""", text(CommandParser.parse("""/echo 5" tall""").pipeline.single()))
}
