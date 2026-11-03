package app.sillytavern.android.memory

import app.sillytavern.android.chat.ChatFeaturesTestBase
import app.sillytavern.android.chat.ChatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Counts calls so tests can check that only new text is embedded. */
class CountingEmbedder : Embedder {
    private val inner = HashingEmbedder()
    override val id = inner.id
    var embedded = 0
    override suspend fun embed(texts: List<String>): List<FloatArray> { embedded += texts.size; return inner.embed(texts) }
}

class VectorMemoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val s = VectorSettings(enabled = true, protectRecent = 2, retrieveCount = 2, queryMessages = 1, scoreThreshold = 0.1)
    private fun chat(vararg t: String) = t.mapIndexed { i, x -> if (i % 2 == 0) ChatMessage.user("Me", x) else ChatMessage.assistant("Ann", x) }

    private val history = chat(
        "I found a silver key hidden under the old oak tree",
        "A key! It looks ancient, covered in strange runes",
        "Let us bake bread for the festival tomorrow",
        "Yes, flour and honey are in the pantry",
        "Where should we look for the lock of the silver key?",
        "Perhaps the oak tree holds the answer",
    )

    @Test fun recallsOlderRelatedMessagesInChatOrder() = runBlocking {
        val m = VectorMemory(VectorStore(tmp.newFolder()))
        val text = m.recall("c", history.also { m.sync("c", it, s, HashingEmbedder()) }, s, HashingEmbedder())
        assertNotNull(text)
        assertTrue(text!!, text.contains("silver key hidden under the old oak tree"))
        assertFalse(text.contains("bread"))
    }

    @Test fun protectedMessagesAreNeitherIndexedNorRecalled() = runBlocking {
        val store = VectorStore(tmp.newFolder())
        val m = VectorMemory(store)
        m.sync("c", history, s, HashingEmbedder())
        assertEquals(4, store.size("c")) // 6 messages, newest 2 protected
        val out = m.recall("c", history, s.copy(retrieveCount = 10), HashingEmbedder())!!
        assertFalse(out.contains("Perhaps the oak tree"))
    }

    @Test fun syncOnlyEmbedsNewTextAndDropsEditedOnes() = runBlocking {
        val store = VectorStore(tmp.newFolder())
        val m = VectorMemory(store); val e = CountingEmbedder()
        m.sync("c", history, s, e); assertEquals(4, e.embedded)
        m.sync("c", history, s, e); assertEquals(4, e.embedded)
        m.sync("c", history + chat("x", "y", "later message one", "later message two").drop(2), s, e)
        assertTrue(e.embedded in 5..6)
        val edited = history.toMutableList().also { it[0] = it[0].withText("The key was actually gold") }
        m.sync("c", edited, s, e)
        val texts = store.query("c", e.id, HashingEmbedder().vector("gold key"), 10, 0.0).map { it.entry.text }
        assertTrue(texts.any { it.contains("gold") })
        assertFalse(texts.any { it.contains("silver key hidden") })
    }

    @Test fun regeneratingOlderMessageKeepsLaterEntries() = runBlocking {
        val store = VectorStore(tmp.newFolder())
        val m = VectorMemory(store); val e = HashingEmbedder()
        m.sync("c", history, s, e)
        m.sync("c", history.take(4), s, e) // history for a re-roll of message 4
        assertEquals(4, store.size("c"))
    }

    @Test fun systemAndHiddenMessagesAreSkipped() = runBlocking {
        val store = VectorStore(tmp.newFolder())
        val h = listOf(ChatMessage.narrator("narration"), ChatMessage.user("Me", "hidden one").withHidden(true), ChatMessage.user("Me", "visible one"), ChatMessage.assistant("Ann", "a"), ChatMessage.user("Me", "b"))
        VectorMemory(store).sync("c", h, s.copy(protectRecent = 0), HashingEmbedder())
        assertEquals(3, store.size("c"))
    }

    @Test fun promptUsesTemplateAndSettings() = runBlocking {
        val m = VectorMemory(VectorStore(tmp.newFolder()))
        val p = m.prompt("c", history, s.copy(template = "MEMORY:\n{{text}}", depth = 7, role = 1), HashingEmbedder())!!
        assertTrue(p.content.startsWith("MEMORY:\nMe: I found a silver key"))
        assertEquals(7, p.depth); assertEquals(1, p.role)
        assertNull(m.prompt("c", history, s.copy(enabled = false), HashingEmbedder()))
    }

    @Test fun nothingRelevantGivesNull() = runBlocking {
        val m = VectorMemory(VectorStore(tmp.newFolder()))
        val h = history.dropLast(1) + ChatMessage.assistant("Ann", "zzzz qqqq")
        assertNull(m.prompt("c", h, s.copy(scoreThreshold = 0.9), HashingEmbedder()))
    }

    @Test fun longMessagesAreChunked() = runBlocking {
        val store = VectorStore(tmp.newFolder())
        val long = (1..30).joinToString(" ") { "Sentence $it of the long story." }
        VectorMemory(store).sync("c", listOf(ChatMessage.user("Me", long), ChatMessage.assistant("Ann", "ok"), ChatMessage.user("Me", "b")), s.copy(protectRecent = 1, chunkSize = 100), HashingEmbedder())
        assertTrue(store.size("c") > 3)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryControllerTest : ChatFeaturesTestBase() {
    private fun service(settings: MemorySettings) = MemoryService(VectorStore(paths.vectors), paths.dataBank, { settings }, { null })

    @Test fun recalledPastEventsReachThePrompt() = runTest {
        val vs = VectorSettings(enabled = true, protectRecent = 2, queryMessages = 1, scoreThreshold = 0.1)
        memoryService = service(MemorySettings(vector = vs))
        val c = controller(); c.open(ann); advanceUntilIdle()
        for (t in listOf("My dog Biscuit loves the beach", "unrelated filler one", "unrelated filler two", "More filler here", "Tell me about Biscuit the dog at the beach")) {
            c.send(t); advanceUntilIdle()
        }
        val sent = requests.last().messages.joinToString("\n") { it.content }
        assertTrue(sent, sent.contains("Past events:"))
        assertTrue(sent, sent.contains("My dog Biscuit loves the beach"))
        assertTrue(paths.vectors.listFiles()!!.isNotEmpty())
    }

    @Test fun disabledMemoryAddsNothing() = runTest {
        memoryService = service(MemorySettings())
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hello there"); advanceUntilIdle()
        assertFalse(requests.last().messages.joinToString { it.content }.contains("Past events"))
        assertTrue(paths.vectors.listFiles().orEmpty().isEmpty())
    }

    @Test fun embeddingFailureStillGeneratesAndNotifies() = runTest {
        val broken = object : Embedder { override val id = "x"; override suspend fun embed(texts: List<String>): List<FloatArray> = error("boom") }
        memoryService = MemoryService(VectorStore(paths.vectors), paths.dataBank, { MemorySettings(embedder = EmbedderSettings(EmbedderKind.OPENAI), vector = VectorSettings(enabled = true, protectRecent = 0)) }, { null }, { _, _ -> broken })
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("hello there"); advanceUntilIdle()
        assertEquals("reply", c.state.value.chat!!.messages.last().text)
        assertTrue(c.state.value.notice!!.contains("boom"))
    }
}
