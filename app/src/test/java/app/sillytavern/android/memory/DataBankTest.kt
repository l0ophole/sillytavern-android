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

class DataBankTest {
    @get:Rule val tmp = TemporaryFolder()
    private val s = DataBankSettings(chunkSize = 120, overlapPercent = 0, scoreThreshold = 0.1, retrieveCount = 2)
    private val global = BankScopeKey(BankScope.GLOBAL)
    private val char = BankScopeKey(BankScope.CHARACTER, "Ann")
    private val lore = "The city of Veyra sits on a cliff above the northern sea. Its harbor is guarded by iron chains.\n\n" +
        "Veyra's founders were exiled sailors who carried salt and lanterns. The lantern festival honors them every autumn."
    private val cooking = "Sourdough needs a starter fed with flour and water every day. Bake it in a very hot oven with steam."

    private fun bank(): Pair<DataBank, VectorStore> {
        val store = VectorStore(tmp.newFolder("v")); return DataBank(tmp.newFolder("b"), store) to store
    }
    private fun hist(t: String) = listOf(ChatMessage.user("Me", t))

    @Test fun addListReadRemoveWithSafeNames() {
        val (b, _) = bank()
        val d = b.add(global, "../lore.md", "x")
        assertEquals("_lore.md".takeIf { d.name == it } ?: d.name, d.name)
        assertTrue(d.file.parentFile!!.name == "global")
        val d2 = b.add(global, d.name, "y")
        assertNotEquals(d.name, d2.name)
        assertEquals(2, b.list(global).size)
        assertEquals("y", b.read(d2))
        assertTrue(b.remove(d))
        assertEquals(1, b.list(global).size)
        assertEquals("txt", b.add(global, "notes.exe", "z").file.extension)
    }

    @Test fun scopesAreSeparate() {
        val (b, _) = bank()
        b.add(global, "g.txt", "g"); b.add(char, "c.txt", "c")
        assertEquals(listOf("g.txt"), b.list(global).map { it.name })
        assertEquals(listOf("c.txt"), b.list(char).map { it.name })
        assertTrue(b.list(BankScopeKey(BankScope.CHARACTER, "Bob")).isEmpty())
    }

    @Test fun retrievesTheRelevantDocumentChunk() = runBlocking {
        val (b, _) = bank()
        b.add(global, "veyra.txt", lore); b.add(global, "bread.txt", cooking)
        val p = b.prompt(listOf(global), hist("When is the lantern festival in Veyra?"), s, HashingEmbedder())!!
        assertTrue(p.content, p.content.contains("lantern festival honors them"))
        assertTrue(p.content.startsWith("Information that might be related"))
        val other = b.prompt(listOf(global), hist("How do I bake sourdough with a starter?"), s, HashingEmbedder())!!
        assertTrue(other.content, other.content.contains("Sourdough"))
    }

    @Test fun editedDocumentIsReindexedAndDeletedOnesForgotten() = runBlocking {
        val (b, store) = bank(); val e = CountingEmbedder()
        val d = b.add(global, "veyra.txt", lore)
        b.sync(global, s, e); val first = e.embedded
        assertTrue(first >= 2)
        b.sync(global, s, e); assertEquals(first, e.embedded)
        d.file.writeText("Completely different text about volcanoes and ash clouds.")
        b.sync(global, s, e)
        assertEquals(setOf("veyra.txt"), store.query("bank_global", e.id, e.inner(), 10, -1.0).map { it.entry.meta }.toSet())
        assertEquals(1, store.size("bank_global"))
        b.remove(b.list(global).single())
        assertEquals(0, store.size("bank_global"))
    }

    @Test fun noDocumentsOrBlankQueryOrDisabledGivesNothing() = runBlocking {
        val (b, _) = bank(); val e = HashingEmbedder()
        assertNull(b.prompt(listOf(global), hist("hello"), s, e))
        b.add(global, "a.txt", lore)
        assertNull(b.prompt(listOf(global), emptyList(), s, e))
        assertNull(b.prompt(listOf(global), hist("Veyra harbor"), s.copy(enabled = false), e))
    }

    @Test fun mergesScopesByScore() = runBlocking {
        val (b, _) = bank()
        b.add(global, "bread.txt", cooking); b.add(char, "veyra.txt", lore)
        val hits = b.retrieve(listOf(global, char), "iron chains guard the harbor of Veyra", s, HashingEmbedder())
        assertEquals("veyra.txt", hits.first().entry.meta)
    }

    private fun CountingEmbedder.inner() = HashingEmbedder().vector("volcanoes")
}

@OptIn(ExperimentalCoroutinesApi::class)
class DataBankControllerTest : ChatFeaturesTestBase() {
    @Test fun characterDocumentReachesThePrompt() = runTest {
        val svc = MemoryService(VectorStore(paths.vectors), paths.dataBank, { MemorySettings(dataBank = DataBankSettings(scoreThreshold = 0.1)) }, { null })
        svc.dataBank.add(BankScopeKey(BankScope.CHARACTER, ann.fileName), "facts.txt", "Ann was born in the lighthouse of Cape Marrow and fears thunderstorms.")
        memoryService = svc
        val c = controller(); c.open(ann); advanceUntilIdle()
        c.send("Where is the lighthouse of Cape Marrow, Ann?"); advanceUntilIdle()
        val sent = requests.last().messages.joinToString("\n") { it.content }
        assertTrue(sent, sent.contains("born in the lighthouse of Cape Marrow"))
    }
}
