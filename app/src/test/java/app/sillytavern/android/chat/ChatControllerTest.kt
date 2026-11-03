package app.sillytavern.android.chat

import app.sillytavern.android.api.Backend
import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.GenerationRequest
import app.sillytavern.android.card.CharacterCard
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.data.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatControllerTest {
    private val paths = StoragePaths(Files.createTempDirectory("cc").toFile()).ensure()
    private val ch = StoredCharacter("Ann", CharacterCard(name = "Ann", firstMessage = "Hello"), File("x"))
    private val withConn = AppSettings(connections = listOf(ConnectionProfile(name = "c")))
    var lastReq: GenerationRequest? = null
    private val fake = Backend { req -> lastReq = req; flow { emit("par"); emit("tial") } }

    private fun TestScope.controller(settings: AppSettings = withConn, backend: Backend = fake) =
        ChatController(ChatRepository(paths), { backend }, { settings }, { "key" }, this, worlds = app.sillytavern.android.worldinfo.WorldBookRepository(paths))

    @Test fun sendStreamsAndPersists() = runTest {
        val c = controller(); c.open(ch)
        c.send("hi"); advanceUntilIdle()
        val msgs = c.state.value.chat!!.messages
        assertEquals(listOf("Hello", "hi", "partial"), msgs.map { it.text })
        assertFalse(c.state.value.generating)
        assertEquals(3, ChatRepository(paths).load(c.state.value.chat!!.file!!).messages.size)
    }

    @Test fun regenerateAddsSwipe() = runTest {
        val c = controller(); c.open(ch); c.send("hi"); advanceUntilIdle()
        c.regenerate(); advanceUntilIdle()
        val last = c.state.value.chat!!.messages.last()
        assertEquals(2, last.swipes.size); assertEquals(1, last.swipeId)
        c.swipe(2, -1)
        assertEquals(0, c.state.value.chat!!.messages.last().swipeId)
    }

    @Test fun errorRemovesPlaceholderAndReports() = runTest {
        val c = controller(backend = Backend { flow { throw BackendException("HTTP 500") } })
        c.open(ch); c.send("hi"); advanceUntilIdle()
        assertEquals(listOf("Hello", "hi"), c.state.value.chat!!.messages.map { it.text })
        assertEquals("HTTP 500", c.state.value.error)
    }

    @Test fun noConnectionGivesHelpfulError() = runTest {
        val c = controller(AppSettings()); c.open(ch); c.send("hi"); advanceUntilIdle()
        assertTrue(c.state.value.error!!.contains("connection"))
        assertEquals(2, c.state.value.chat!!.messages.size)
    }

    @Test fun editDeleteAndImpersonate() = runTest {
        val c = controller(); c.open(ch)
        c.edit(0, "Edited"); assertEquals("Edited", c.state.value.chat!!.messages[0].text)
        c.impersonate(); advanceUntilIdle()
        assertEquals("partial", c.state.value.impersonation)
        assertEquals(1, c.state.value.chat!!.messages.size)
        c.delete(0); assertTrue(c.state.value.chat!!.messages.isEmpty())
    }

    @Test fun continueAppends() = runTest {
        val c = controller(); c.open(ch)
        c.continueLast(); advanceUntilIdle()
        assertEquals("Hellopartial", c.state.value.chat!!.messages.last().text)
    }

    @Test fun cancelKeepsPartial() = runTest {
        val slow = Backend { flow { emit("abc"); awaitCancellation() } }
        val c = controller(backend = slow); c.open(ch); c.send("hi"); runCurrent()
        c.stop(); advanceUntilIdle()
        assertEquals("abc", c.state.value.chat!!.messages.last().text)
        assertFalse(c.state.value.generating)
    }

    @Test fun controllerUsesInstructForTextCompletion() = runTest {
        val st = AppSettings(
            connections = listOf(ConnectionProfile(name = "t", type = BackendType.TEXT_COMPLETION)),
            instructEnabled = true, activeInstructName = "ChatML", activeContextName = "ChatML",
        )
        val c = controller(st); c.open(ch); c.send("hi"); advanceUntilIdle()
        val req = lastReq!!
        assertTrue(req.textPrompt.startsWith("<|im_start|>system"))
        assertTrue(req.stopSequences.contains("<|im_end|>"))
        assertTrue(req.textPrompt.endsWith("<|im_start|>assistant\nAnn:"))
    }

    private fun lore(content: String = "DRAGONLORE", block: app.sillytavern.android.worldinfo.WorldEntry.() -> app.sillytavern.android.worldinfo.WorldEntry = { this }) {
        app.sillytavern.android.worldinfo.WorldBookRepository(paths).save(app.sillytavern.android.worldinfo.WorldBook("G",
            listOf(app.sillytavern.android.worldinfo.WorldEntry(uid = 1, key = listOf("dragon"), content = content).block())))
    }

    @Test fun worldInfoReachesRequest() = runTest {
        lore()
        val c = controller(withConn.copy(globalWorldBooks = listOf("G"))); c.open(ch)
        c.send("I see a dragon"); advanceUntilIdle()
        assertTrue(lastReq!!.messages.any { it.content.contains("DRAGONLORE") })
    }

    @Test fun noBooksNoChange() = runTest {
        val c = controller(); c.open(ch); c.send("dragon"); advanceUntilIdle()
        assertTrue(lastReq!!.messages.none { it.content.contains("DRAGONLORE") })
    }

    @Test fun timedStatePersistedAndRegenerateDoesNotAdvance() = runTest {
        lore { copy(sticky = 5) }
        val c = controller(withConn.copy(globalWorldBooks = listOf("G"))); c.open(ch)
        c.send("a dragon"); advanceUntilIdle()
        val file = c.state.value.chat!!.file!!
        val saved = ChatRepository(paths).load(file)
        assertNotNull(app.sillytavern.android.worldinfo.WorldInfoRunner.timedOf(saved).sticky["G.1"])
        val before = app.sillytavern.android.worldinfo.WorldInfoRunner.timedOf(c.state.value.chat).sticky
        c.regenerate(); advanceUntilIdle()
        assertEquals(before, app.sillytavern.android.worldinfo.WorldInfoRunner.timedOf(c.state.value.chat).sticky)
    }

    private val thinker = object : app.sillytavern.android.api.ChunkBackend {
        override fun chunks(req: GenerationRequest): Flow<app.sillytavern.android.api.Chunk> = flow {
            emit(app.sillytavern.android.api.Chunk.Reasoning("let me "))
            emit(app.sillytavern.android.api.Chunk.Reasoning("think"))
            emit(app.sillytavern.android.api.Chunk.Text("answer"))
        }
    }

    @Test fun reasoningStoredInExtraNotText() = runTest {
        val c = controller(backend = thinker); c.open(ch); c.send("hi"); advanceUntilIdle()
        val last = c.state.value.chat!!.messages.last()
        assertEquals("answer", last.text)
        assertEquals("let me think", last.reasoning)
        assertEquals("let me think", ChatRepository(paths).load(c.state.value.chat!!.file!!).messages.last().reasoning)
    }

    @Test fun regenerateReplacesReasoning() = runTest {
        var first = true
        val be = object : app.sillytavern.android.api.ChunkBackend {
            override fun chunks(req: GenerationRequest): Flow<app.sillytavern.android.api.Chunk> = flow {
                if (first) { first = false; emit(app.sillytavern.android.api.Chunk.Reasoning("old")) }
                emit(app.sillytavern.android.api.Chunk.Text("t"))
            }
        }
        val c = controller(backend = be); c.open(ch); c.send("hi"); advanceUntilIdle()
        assertEquals("old", c.state.value.chat!!.messages.last().reasoning)
        c.regenerate(); advanceUntilIdle()
        assertNull(c.state.value.chat!!.messages.last().reasoning)
    }

    @Test fun plainBackendStillWorksWithoutReasoning() = runTest {
        val c = controller(); c.open(ch); c.send("hi"); advanceUntilIdle()
        assertNull(c.state.value.chat!!.messages.last().reasoning)
    }

    @Test fun koboldUsesTextPrompt() = runTest {
        val s = AppSettings(connections = listOf(ConnectionProfile(name = "k", type = BackendType.KOBOLD)))
        val c = controller(s); c.open(ch); c.send("hi"); advanceUntilIdle()
        assertTrue(lastReq!!.textPrompt.contains("hi"))
        assertTrue(lastReq!!.messages.isEmpty())
    }

    // --- set 5: commands ---
    @Test fun unknownCommandNotSentToModel() = runTest {
        val c = controller(); c.open(ch)
        c.submit("/nonsense x"); advanceUntilIdle()
        assertNull(lastReq); assertEquals(1, c.state.value.chat!!.messages.size)
        assertTrue(c.state.value.error!!.contains("Unknown command"))
    }
    @Test fun slashSpaceSentAsChat() = runTest {
        val c = controller(); c.open(ch)
        c.submit("/ shrug"); advanceUntilIdle()
        assertEquals("/ shrug", c.state.value.chat!!.messages[1].text)
    }
    @Test fun sysCommandAddsNarratorAndPromptIncludesIt() = runTest {
        val c = controller(); c.open(ch)
        c.submit("/sys The wind howls."); advanceUntilIdle()
        val m = c.state.value.chat!!.messages.last()
        assertTrue(m.isSystem && m.isNarrator)
        c.send("go on"); advanceUntilIdle()
        assertTrue(lastReq!!.messages.any { it.content.contains("The wind howls.") })
    }
    @Test fun hiddenMessageExcludedFromPrompt() = runTest {
        val c = controller(); c.open(ch)
        c.send("secret one"); advanceUntilIdle()
        c.submit("/hide 1"); advanceUntilIdle()
        c.send("next"); advanceUntilIdle()
        assertFalse(lastReq!!.messages.any { it.content.contains("secret one") })
        c.submit("/unhide 1"); advanceUntilIdle()
        c.send("again"); advanceUntilIdle()
        assertTrue(lastReq!!.messages.any { it.content.contains("secret one") })
    }
    @Test fun triggerGeneratesAndPipelineWaits() = runTest {
        val c = controller(); c.open(ch)
        c.submit("/send hello there | /trigger | /setvar key=done yes"); advanceUntilIdle()
        assertEquals(listOf("Hello", "hello there", "partial"), c.state.value.chat!!.messages.map { it.text })
        assertEquals("yes", c.variableStore().get("done", false))
    }
    @Test fun genRawDoesNotTouchChat() = runTest {
        val c = controller(); c.open(ch)
        c.submit("/genraw tell me | /setvar key=out"); advanceUntilIdle()
        assertEquals("partial", c.variableStore().get("out", false)); assertEquals(1, c.state.value.chat!!.messages.size)
    }
    @Test fun cutAndSetInput() = runTest {
        val c = controller(); c.open(ch)
        c.send("one"); advanceUntilIdle()
        c.submit("/cut 1-2 | /setinput draft"); advanceUntilIdle()
        assertEquals(1, c.state.value.chat!!.messages.size); assertEquals("draft", c.state.value.impersonation)
    }
}
