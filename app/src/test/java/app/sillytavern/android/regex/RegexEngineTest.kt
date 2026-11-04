package app.sillytavern.android.regex

import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegexEngineTest {
    private fun s(find: String, rep: String = "", placement: List<Int> = listOf(2), block: RegexScript.() -> RegexScript = { this }) =
        RegexScript(scriptName = "t", findRegex = find, replaceString = rep, placement = placement).block()
    private fun ai(text: String, vararg sc: RegexScript, p: RegexParams = RegexParams()) = RegexEngine.apply(text, Placement.AI_OUTPUT, sc.toList(), p)

    @Test fun bareBracesAreLiteral() = assertEquals("[x]", ai("{x}", s("/{(\\w)}/", "[$1]")))
    @Test fun bareBraceEscaping() {
        assertEquals("\\{a\\}", RegexEngine.escapeBareBraces("{a}"))
        assertEquals("a{2,3}[{}]\\p{L}\\{", RegexEngine.escapeBareBraces("a{2,3}[{}]\\p{L}\\{"))
        assertEquals("x{2}\\}", RegexEngine.escapeBareBraces("x{2}}"))
    }
    @Test fun simpleReplace()= assertEquals("a-b b", ai("a b b", s("/ /", "-")))
    @Test fun globalFlagReplacesAll() = assertEquals("a-b-b", ai("a b b", s("/ /g", "-")))
    @Test fun noGlobalFlagFirstOnly() = assertEquals("axb", ai("abb", s("/b/", "x")))
    @Test fun plainPatternWithoutSlashes() = assertEquals("hello world", ai("hello there", s("there", "world")))
    @Test fun captureGroupsInReplacement() = assertEquals("[bob] hi", ai("hi bob", s("/(\\w+) (\\w+)/", "[$2] $1")))
    @Test fun namedGroup() = assertEquals("y_z", ai("z_y", s("/(?<first_part>\\w)_(?<second>\\w)/", "$<second>_$<first_part>")))
    @Test fun matchMacro() = assertEquals("<<abc>>", ai("abc", s("/abc/", "<<{{match}}>>")))
    @Test fun trimStringsRemoved() = assertEquals("[ab]", ai("a-b", s("/(.+)/", "[$1]") .copy(trimStrings = listOf("-"))))
    @Test fun placementFiltering() { assertEquals("x", RegexEngine.apply("x", Placement.USER_INPUT, listOf(s("/x/", "y")))); assertEquals("y", RegexEngine.apply("x", Placement.AI_OUTPUT, listOf(s("/x/", "y")))) }
    @Test fun markdownOnlyRunsOnlyForDisplay() {
        val sc = s("/x/", "y").copy(markdownOnly = true)
        assertEquals("x", ai("x", sc)); assertEquals("x", ai("x", sc, p = RegexParams(isPrompt = true))); assertEquals("y", ai("x", sc, p = RegexParams(isMarkdown = true)))
    }
    @Test fun promptOnlyRunsOnlyForPrompt() {
        val sc = s("/x/", "y").copy(promptOnly = true)
        assertEquals("x", ai("x", sc)); assertEquals("y", ai("x", sc, p = RegexParams(isPrompt = true)))
    }
    @Test fun ordinaryScriptSkipsDisplayAndPrompt() {
        val sc = s("/x/", "y")
        assertEquals("y", ai("x", sc)); assertEquals("x", ai("x", sc, p = RegexParams(isMarkdown = true))); assertEquals("x", ai("x", sc, p = RegexParams(isPrompt = true)))
    }
    @Test fun depthWindow() {
        val sc = s("/x/", "y").copy(minDepth = 1, maxDepth = 2, promptOnly = true)
        fun at(d: Int) = ai("x", sc, p = RegexParams(isPrompt = true, depth = d))
        assertEquals("x", at(0)); assertEquals("y", at(1)); assertEquals("y", at(2)); assertEquals("x", at(3))
    }
    @Test fun runOnEditFalse() { val sc = s("/x/", "y").copy(runOnEdit = false); assertEquals("x", ai("x", sc, p = RegexParams(isEdit = true))); assertEquals("y", ai("x", sc)) }
    @Test fun disabledSkipped() = assertEquals("x", ai("x", s("/x/", "y").copy(disabled = true)))
    @Test fun invalidPatternSkipped() = assertEquals("x", ai("x", s("/(/", "y")))
    @Test fun substituteRegexEscaped() {
        val m: (String) -> String = { it.replace("{{user}}", "a.b") }
        val sc = s("/{{user}}/", "Z").copy(substituteRegex = 2)
        assertEquals("aXb Z", ai("aXb a.b", sc, p = RegexParams(macros = m)))
        val raw = s("/{{user}}/g", "Z").copy(substituteRegex = 1)
        assertEquals("Z Z", ai("aXb a.b", raw, p = RegexParams(macros = m)))
    }
    @Test fun macrosInReplacement() = assertEquals("hi Al", ai("hi", s("/hi/", "hi {{user}}"), p = RegexParams(macros = { it.replace("{{user}}", "Al") })))
    @Test fun oversizedInputSkipped() { val big = "x".repeat(1_100_000); assertEquals(big.length, ai(big, s("/x/g", "y")).length); assertEquals('x', ai(big, s("/x/g", "y"))[0]) }
    @Test fun backtrackingBombDoesNotHang() {
        val t0 = System.nanoTime()
        val out = ai("a".repeat(40) + "!", s("/^(a+)+$/", "ok"))
        assertTrue((System.nanoTime() - t0) < 5_000_000_000L); assertEquals("a".repeat(40) + "!", out)
    }
    @Test fun scriptsApplyInOrder() = assertEquals("c", ai("a", s("/a/", "b"), s("/b/", "c")))
    @Test fun stSampleScriptRoundTrip() {
        val text = javaClass.getResource("/regex/sample.json")!!.readText()
        val sc = RegexScript.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)
        assertTrue(sc.promptOnly); assertEquals(listOf(2), sc.placement)
        assertEquals("Hi", ai("<think>plan</think>Hi", sc, p = RegexParams(isPrompt = true)))
        assertTrue(sc.toJson().toString().contains("futureKey"))
    }
    @Test fun importArrayAndSingle() {
        val repo = RegexRepository(StoragePaths(Files.createTempDirectory("rx").toFile()).ensure())
        assertEquals(1, repo.importJson("""{"scriptName":"a","findRegex":"x"}""").size)
        assertEquals(2, repo.importJson("""[{"scriptName":"a","findRegex":"x"},{"scriptName":"b","findRegex":"y"}]""").size)
    }
    @Test fun globalStorageRoundTrip() {
        val repo = RegexRepository(StoragePaths(Files.createTempDirectory("rx").toFile()).ensure())
        repo.saveGlobal(listOf(s("/x/", "y").copy(scriptName = "mine")))
        assertEquals("mine", RegexRepository(StoragePaths(repo.root)).global().single().scriptName)
    }
    @Test fun scopedScriptsFromCard() {
        val repo = RegexRepository(StoragePaths(Files.createTempDirectory("rx").toFile()).ensure())
        val card = repo.withScoped(CharacterCard(name = "A"), listOf(s("/x/", "y")))
        assertEquals("y", repo.scopedOf(card).single().replaceString)
    }
}
