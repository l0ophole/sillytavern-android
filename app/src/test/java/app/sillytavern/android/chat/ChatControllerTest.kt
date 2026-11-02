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
        ChatController(ChatRepository(paths), { backend }, { settings }, { "key" }, this)

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
}
