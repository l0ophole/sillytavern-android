package app.sillytavern.android.worldinfo

import app.sillytavern.android.card.CardCodec
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorldRepositoryTest {
    private val paths = StoragePaths(Files.createTempDirectory("wi").toFile()).ensure()
    private val repo = WorldBookRepository(paths)
    private fun book(name: String, vararg entries: WorldEntry) = WorldBook(name, entries.toList()).also(repo::save)
    private fun e(uid: Int, order: Int = 100, content: String = "c$uid", block: WorldEntry.() -> WorldEntry = { this }) =
        WorldEntry(uid = uid, order = order, content = content, key = listOf("k")).block()
    private fun card(extra: String = "") = CardCodec.parse("""{"name":"Ann"$extra}""")
    private fun settings(global: List<String> = emptyList(), strat: Int = 1, extra: Map<String, List<String>> = emptyMap()) =
        AppSettings(globalWorldBooks = global, characterExtraBooks = extra,
            worldInfo = WorldInfoSettings(characterStrategy = strat))
    private fun ids(l: List<ScanEntry>) = l.map { it.id }

    @Test fun saveLoadRoundTrip() {
        book("Lore", e(1)); assertEquals("c1", repo.load("Lore")!!.entries.single().content); assertEquals(listOf("Lore"), repo.list())
    }
    @Test fun importWorldJson() {
        val r = repo.import("My Lore.json", """{"entries":{"0":{"uid":0,"key":["a"],"content":"x"}}}""")
        assertEquals("My Lore", r.getOrThrow()); assertEquals(1, repo.load("My Lore")!!.entries.size)
    }
    @Test fun importCardWithCharacterBook() {
        val txt = """{"name":"Ann","character_book":{"name":"AnnLore","entries":[{"keys":["a"],"content":"x","insertion_order":1,"enabled":true}]}}"""
        assertEquals("AnnLore", repo.import("ann.json", txt).getOrThrow())
        assertEquals(listOf("a"), repo.load("AnnLore")!!.entries.single().key)
    }
    @Test fun importGarbageFails() {
        assertTrue(repo.import("x.json", "nope").isFailure)
        assertTrue(repo.import("x.json", """{"name":"NoBook"}""").isFailure)
    }
    @Test fun importSameNameReplaces() {
        repo.import("a.json", """{"entries":{"0":{"key":["a"],"content":"old"}}}""")
        repo.import("a.json", """{"entries":{"0":{"key":["a"],"content":"new"}}}""")
        assertEquals("new", repo.load("a")!!.entries.single().content); assertEquals(1, repo.list().size)
    }
    @Test fun deleteRemoves() { book("x", e(1)); repo.delete("x"); assertNull(repo.load("x")) }
    @Test fun fileNamesSanitized() {
        book("../evil", e(1))
        assertTrue(paths.worlds.listFiles()!!.all { it.parentFile == paths.worlds })
        assertNotNull(repo.load("../evil"))
    }
    @Test fun resolveDedupesGlobalAndCharacter() {
        book("G", e(1))
        val c = card(""","extensions":{"world":"G"}""")
        assertEquals(listOf("G.1"), ids(WorldInfoSources.resolve(c, "ann", null, settings(listOf("G")), repo)))
    }
    @Test fun personaBookComesAfterChatBookAndBeforeGlobal() {
        book("Chat", e(1)); book("P", e(2)); book("G", e(3))
        assertEquals(listOf("Chat.1", "P.2", "G.3"), ids(WorldInfoSources.resolve(card(), "ann", "Chat", settings(listOf("G")), repo, "P")))
    }
    @Test fun personaBookAlreadyGlobalIsNotDuplicated() {
        book("G", e(3))
        assertEquals(listOf("G.3"), ids(WorldInfoSources.resolve(card(), "ann", null, settings(listOf("G")), repo, "G")).distinct())
        assertEquals(1, WorldInfoSources.resolve(card(), "ann", null, settings(listOf("G")), repo, "G").size)
    }
    @Test fun resolveStrategyOrders() {
        book("G", e(1, order = 10), e(2, order = 90)); book("C", e(3, order = 50))
        val c = card(""","extensions":{"world":"C"}""")
        fun r(s: Int) = ids(WorldInfoSources.resolve(c, "ann", null, settings(listOf("G"), s), repo))
        assertEquals(listOf("C.3", "G.2", "G.1"), r(1)); assertEquals(listOf("G.2", "G.1", "C.3"), r(2)); assertEquals(listOf("G.2", "C.3", "G.1"), r(0))
    }
    @Test fun embeddedCharacterBookIncluded() {
        val c = card(""","character_book":{"entries":[{"keys":["a"],"content":"x","insertion_order":1,"enabled":true}]}""")
        assertEquals(1, WorldInfoSources.resolve(c, "ann", null, settings(), repo).size)
    }
    @Test fun extraBooksForCharacter() {
        book("X", e(1))
        assertEquals(listOf("X.1"), ids(WorldInfoSources.resolve(card(), "ann", null, settings(extra = mapOf("ann" to listOf("X"))), repo)))
        assertTrue(WorldInfoSources.resolve(card(), "bob", null, settings(extra = mapOf("ann" to listOf("X"))), repo).isEmpty())
    }
    @Test fun characterFilterByNameAndExclude() {
        book("G", e(1) { copy(characterFilterNames = listOf("ann")) }, e(2) { copy(characterFilterNames = listOf("ann"), characterFilterExclude = true) })
        assertEquals(listOf("G.1"), ids(WorldInfoSources.resolve(card(), "ann", null, settings(listOf("G")), repo)))
        assertEquals(listOf("G.2"), ids(WorldInfoSources.resolve(card(), "bob", null, settings(listOf("G")), repo)).filter { it == "G.2" })
    }
    @Test fun chatBookFirst() {
        book("G", e(1, 500)); book("Chat", e(2, 1))
        assertEquals(listOf("Chat.2", "G.1"), ids(WorldInfoSources.resolve(card(), "ann", "Chat", settings(listOf("G")), repo)))
    }
    @Test fun missingBookSkipped() = assertTrue(WorldInfoSources.resolve(card(), "ann", "nope", settings(listOf("gone")), repo).isEmpty())
}
