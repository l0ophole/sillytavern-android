package app.sillytavern.android.command

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.macro.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FakeHost : CommandHost {
    override val variables = VariableStore.inMemory()
    val echoes = mutableListOf<String>()
    override fun macroContext() = MacroContext("Al", "Bea", vars = variables)
    override suspend fun echo(text: String, severity: String) { echoes += text }
}

class ExecutorTest {
    private val host = FakeHost()
    private fun run(text: String, limits: Limits = Limits()) = runBlocking { CommandExecutor(host, limits).runText(text) }
    private fun out(text: String) = run(text).also { assertNull(it.error, it.error) }.pipe

    @Test fun pipePassesResult() = assertEquals("HI", out("/pass hi | /upper"))
    @Test fun emptyArgUsesPipe() = assertEquals("5", out("/pass hello | /len"))
    @Test fun unknownCommandErrors() = assertEquals("Unknown command: /nope", run("/nope x").error)
    @Test fun ifTrueRunsClosureElse() {
        assertEquals("yes", out("/if left=1 right=1 rule=eq {: /pass yes :} else={: /pass no :}"))
        assertEquals("no", out("/if left=1 right=2 rule=eq {: /pass yes :} else={: /pass no :}"))
        assertEquals("yes", out("/if left=1 right=1 /pass yes"))
    }
    @Test fun ifRuleComparisons() {
        val cases = listOf(
            Triple("2", "10", "lt") to true, Triple("b", "a", "gt") to true, Triple("a", "a", "neq") to false,
            Triple("abc", "b", "in") to true, Triple("abc", "z", "nin") to true, Triple("3", "3", "gte") to true,
            Triple("3", "3", "lte") to true, Triple("", "", "not") to true, Triple("x", "", "not") to false,
        )
        for ((c, expected) in cases) {
            val r = out("/if left=\"${c.first}\" right=\"${c.second}\" rule=${c.third} {: /pass T :} else={: /pass F :}")
            assertEquals("$c", if (expected) "T" else "F", r)
        }
    }
    @Test fun ifTruthinessWithoutRule() { assertEquals("T", out("/if left=x {: /pass T :} else={: /pass F :}")); assertEquals("F", out("/if left=0 {: /pass T :} else={: /pass F :}")) }
    @Test fun whileCountsUp() {
        out("/setvar key=i 0 | /while left={{getvar::i}} right=3 rule=lt {: /incvar i :}")
        assertEquals("3", host.variables.get("i", false))
    }
    @Test fun whileCappedByLimit() {
        val r = run("/while left=1 right=1 rule=eq {: /pass x :}", Limits(maxLoopIterations = 50))
        assertTrue(r.error!!.contains("Loop limit"))
    }
    @Test fun timesLoop() { out("/setvar key=n 0 | /times 4 {: /incvar n :}"); assertEquals("4", host.variables.get("n", false)) }
    @Test fun timesIndexAvailable() { out("/times 3 {: /addvar key=s x{{timesIndex}} :}"); assertEquals("x0x1x2", host.variables.get("s", false)) }
    @Test fun letScopeShadowing() {
        assertEquals("outer", out("/let x outer | /run {: /let x inner | /echo {{var::x}} :} | /pass {{var::x}}"))
        assertEquals(listOf("inner"), host.echoes)
    }
    @Test fun closureScopeSeesParentLet() = assertEquals("seen", out("/let x seen | /run {: /pass {{var::x}} :}"))
    @Test fun runDepthCapped() {
        val r = run("/let f {: /run f :} | /run f")
        assertTrue(r.error!!.contains("depth"))
    }
    @Test fun abortStops() { val r = run("/echo a | /abort | /echo b"); assertTrue(r.aborted); assertEquals(listOf("a"), host.echoes) }
    @Test fun returnEndsClosure() = assertEquals("early", out("/run {: /return early | /pass late :}"))
    @Test fun breakLeavesLoop() { out("/times 10 {: /incvar b | /if left={{getvar::b}} right=3 rule=eq /break :}"); assertEquals("3", host.variables.get("b", false)) }
    @Test fun mathNonNumericIsZero() { assertEquals("5", out("/add 5 abc")); assertEquals("0", out("/mul x y")) }
    @Test fun divideByZeroIsZero() { assertEquals("0", out("/div 5 0")); assertEquals("0", out("/mod 5 0")) }
    @Test fun mathBasics() { assertEquals("7", out("/add 3 4")); assertEquals("1", out("/sub 4 3")); assertEquals("8", out("/pow 2 3")); assertEquals("4", out("/max 1 4 2")); assertEquals("2.5", out("/div 5 2")); assertEquals("3", out("/round 2.6")) }
    @Test fun pipeMacroSubstitution() = assertEquals("[x]", out("/pass x | /pass [{{pipe}}]"))
    @Test fun varSetGetBothScopes() {
        out("/setvar key=a 1 | /setglobalvar key=a 2")
        assertEquals("1", out("/getvar a")); assertEquals("2", out("/getglobalvar a"))
    }
    @Test fun commandErrorStopsPipeline() { val r = run("/run missing | /echo after"); assertNotNull(r.error); assertTrue(host.echoes.isEmpty()) }
    @Test fun textHelpers() {
        assertEquals("bc", out("/substr start=1 end=3 abcd")); assertEquals("a-b", out("/replace pattern=\",\" replacer=\"-\" a,b"))
        assertEquals("""["a","b"]""", out("""/split find="," a,b""")); assertEquals("a+b", out("""/split find="," a,b | /join glue="+" """))
    }
    @Test fun invalidReplaceRegexDoesNotThrow() = assertNull(run("""/replace pattern="/(/g" replacer=x abc""").error?.takeIf { false })
}
