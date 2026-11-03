package app.sillytavern.android.command

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.chat.ChatController
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.data.*
import app.sillytavern.android.quickreply.*
import app.sillytavern.android.regex.Placement
import app.sillytavern.android.regex.RegexScript
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The set 5 pieces working together: command -> variable -> macro in prompt -> QR -> prompt-only regex. */
@OptIn(ExperimentalCoroutinesApi::class)
class EndToEndTest {
    @Test fun commandsMacrosQuickRepliesAndRegexCompose() = runTest {
        val paths = StoragePaths(Files.createTempDirectory("e2e").toFile()).ensure()
        var settings = AppSettings(connections = listOf(ConnectionProfile(name = "c")))
        var last: GenerationRequest? = null
        val backend = Backend { req -> last = req; flow { emit("ok") } }
        val ch = StoredCharacter("Ann", CharacterCard(name = "Ann", description = "Mood: {{getvar::mood}}.", firstMessage = "Hi"), File("x"))
        val prompt = RegexScript(scriptName = "p", findRegex = "/secret/gi", replaceString = "[redacted]", placement = listOf(Placement.USER_INPUT), promptOnly = true)
        val c = ChatController(ChatRepository(paths), { backend }, { settings }, { "k" }, this, regex = { listOf(prompt) },
            updateSettings = { f -> settings = f(settings) })
        val qrRepo = QuickReplyRepository(paths)
        qrRepo.save(QuickReplySet(name = "Q").withAdded(QuickReply(label = "mood", message = "/setvar key=mood {{input}}")))
        settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("Q")))
        val svc = QuickReplyService(qrRepo, { settings }, { f -> settings = f(settings) }, { null }, { }, { null })
        c.quickReplies = svc
        c.open(ch)

        c.submit("/setvar key=mood happy"); advanceUntilIdle()
        c.send("a secret plan"); advanceUntilIdle()
        val sys = last!!.messages.first { it.content.contains("Mood:") }.content
        assertTrue(sys, sys.contains("Mood: happy."))
        assertTrue(last!!.messages.any { it.content == "a [redacted] plan" })
        assertEquals("a secret plan", c.state.value.chat!!.messages[1].text)

        val (set, qr) = svc.bar().single()
        val o = c.pressQuickReply(set, qr, "angry"); advanceUntilIdle()
        assertNull(o.error)
        assertEquals("angry", c.variableStore().get("mood", false))
        c.submit("/sys {{getvar::mood}}!"); advanceUntilIdle()
        assertEquals("angry!", c.state.value.chat!!.messages.last().text)
    }
}
