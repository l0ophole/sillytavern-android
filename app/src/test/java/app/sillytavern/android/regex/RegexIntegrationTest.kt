package app.sillytavern.android.regex

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.chat.*
import app.sillytavern.android.data.*
import app.sillytavern.android.prompt.*
import app.sillytavern.android.worldinfo.WorldBookRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RegexIntegrationTest {
    private val paths = StoragePaths(Files.createTempDirectory("ri").toFile()).ensure()
    private val ch = StoredCharacter("Ann", CharacterCard(name = "Ann", firstMessage = "Hello"), File("x"))
    private var lastReq: GenerationRequest? = null
    private val backend = Backend { req -> lastReq = req; flow { emit("reply SECRET") } }
    private fun sc(find: String, rep: String, vararg placement: Int, block: RegexScript.() -> RegexScript = { this }) =
        RegexScript(scriptName = "t", findRegex = find, replaceString = rep, placement = placement.toList()).block()

    private fun TestScope.controller(scripts: List<RegexScript>, regexEnabled: Boolean = true) = ChatController(
        ChatRepository(paths), { backend }, { AppSettings(connections = listOf(ConnectionProfile(name = "c")), regexEnabled = regexEnabled) },
        { "key" }, this, worlds = WorldBookRepository(paths), regex = { scripts },
    )

    private fun prompt(history: List<ChatMessage>, scripts: List<RegexScript>) = PromptBuilder.build(PromptInput(
        card = CharacterCard(name = "Ann"), persona = Persona(name = "Bob"), preset = PromptPreset(), history = history,
        maxContextTokens = 4000, maxResponseTokens = 100, counter = ApproxTokenCounter, regexScripts = scripts,
    ))

    private val hist = listOf(ChatMessage.assistant("Ann", "a0 SECRET"), ChatMessage.user("Bob", "u1 SECRET"), ChatMessage.assistant("Ann", "a2 SECRET"))

    @Test fun promptOnlyAffectsPromptNotStored() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "***", 1, 2) { copy(promptOnly = true) })); c.open(ch)
        c.send("hi SECRET"); advanceUntilIdle()
        assertTrue(lastReq!!.messages.none { it.content.contains("SECRET") })
        assertEquals("hi SECRET", c.state.value.chat!!.messages[1].text)
        assertEquals("reply SECRET", c.state.value.chat!!.messages[2].text)
    }
    @Test fun markdownOnlyNotInPrompt() {
        val out = prompt(hist, listOf(sc("/SECRET/g", "***", 1, 2) { copy(markdownOnly = true) }))
        assertTrue(out.any { it.content.contains("SECRET") })
    }
    @Test fun ordinaryScriptChangesStoredUserText() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "xx", 1))); c.open(ch)
        c.send("hi SECRET"); advanceUntilIdle()
        assertEquals("hi xx", c.state.value.chat!!.messages[1].text)
    }
    @Test fun ordinaryScriptChangesStoredReply() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "xx", 2))); c.open(ch)
        c.send("hi"); advanceUntilIdle()
        assertEquals("reply xx", c.state.value.chat!!.messages[2].text)
    }
    @Test fun depthLimitsPromptRewrite() {
        val out = prompt(hist, listOf(sc("/SECRET/g", "***", 1, 2) { copy(promptOnly = true, maxDepth = 1) }))
        val joined = out.joinToString("\n") { it.content }
        assertTrue(joined.contains("a0 SECRET")); assertTrue(joined.contains("u1 ***")); assertTrue(joined.contains("a2 ***"))
    }
    @Test fun globalSwitchOff() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "xx", 1, 2)), regexEnabled = false); c.open(ch)
        c.send("hi SECRET"); advanceUntilIdle()
        assertEquals("hi SECRET", c.state.value.chat!!.messages[1].text)
    }
    @Test fun displayTextAppliesMarkdownScripts() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "***", 2) { copy(markdownOnly = true) })); c.open(ch)
        c.send("hi"); advanceUntilIdle()
        assertEquals("reply ***", c.displayText(c.state.value.chat!!.messages[2])); assertEquals("reply SECRET", c.state.value.chat!!.messages[2].text)
    }
    @Test fun editAppliesScriptsWithEditFlag() = runTest {
        val c = controller(listOf(sc("/SECRET/g", "xx", 1) { copy(runOnEdit = false) })); c.open(ch)
        c.send("hi"); advanceUntilIdle()
        c.edit(1, "again SECRET")
        assertEquals("again SECRET", c.state.value.chat!!.messages[1].text)
    }
    @Test fun streamingChunksNotRegexed() = runTest {
        val slow = Backend { flow { emit("part SECRET"); kotlinx.coroutines.awaitCancellation() } }
        val c = ChatController(ChatRepository(paths), { slow }, { AppSettings(connections = listOf(ConnectionProfile(name = "c"))) }, { "k" }, this,
            regex = { listOf(sc("/SECRET/g", "xx", 2)) })
        c.open(ch); c.send("hi"); runCurrent()
        assertEquals("part SECRET", c.state.value.chat!!.messages.last().text)
        c.stop(); advanceUntilIdle()
    }
}
