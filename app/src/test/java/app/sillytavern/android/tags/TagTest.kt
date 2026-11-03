package app.sillytavern.android.tags

import app.sillytavern.android.data.AppSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TagTest {
    private val empty = TagState()

    @Test fun ensureReusesCaseInsensitive() {
        val (s1, a) = TagOps.ensure(empty, "Fantasy")!!
        val (s2, b) = TagOps.ensure(s1, "  fantasy ")!!
        assertEquals(a.id, b.id)
        assertEquals(1, s2.tags.size)
    }

    @Test fun blankTagRejected() = assertNull(TagOps.ensure(empty, "  "))

    @Test fun renameToExistingRejected() {
        val (s1, a) = TagOps.ensure(empty, "A")!!
        val (s2, _) = TagOps.ensure(s1, "B")!!
        assertEquals(s2, TagOps.rename(s2, a.id, "b"))
        assertEquals("C", TagOps.rename(s2, a.id, "C").tags.first { it.id == a.id }.name)
    }

    @Test fun deleteRemovesFromMap() {
        val (s1, a) = TagOps.ensure(empty, "A")!!
        val s2 = TagOps.assign(s1, "x.png", a.id)
        val s3 = TagOps.delete(s2, a.id)
        assertTrue(s3.tags.isEmpty())
        assertTrue(s3.map["x.png"].orEmpty().isEmpty())
    }

    @Test fun assignIsIdempotent() {
        val (s1, a) = TagOps.ensure(empty, "A")!!
        val s2 = TagOps.assign(TagOps.assign(s1, "x.png", a.id), "x.png", a.id)
        assertEquals(listOf(a.id), s2.map["x.png"])
        assertEquals(emptyList<String>(), TagOps.unassign(s2, "x.png", a.id).map["x.png"])
    }

    @Test fun importCardTagsCreatesAndAssigns() {
        val s = TagOps.importCardTags(empty, "x.png", listOf("Elf", "elf", "Mage", ""))
        assertEquals(listOf("Elf", "Mage"), TagOps.tagsOf(s, "x.png").map { it.name })
        // importing again does not duplicate
        assertEquals(s, TagOps.importCardTags(s, "x.png", listOf("Elf", "Mage")))
    }

    private fun state(): TagState {
        var s = empty
        val (s1, fan) = TagOps.ensure(s, "Fantasy")!!; s = s1
        val (s2, scifi) = TagOps.ensure(s, "SciFi")!!; s = s2
        val (s3, folder) = TagOps.ensure(s, "Hidden")!!; s = TagOps.setFolder(s3, folder.id, "CLOSED")
        s = TagOps.assign(s, "a.png", fan.id)
        s = TagOps.assign(s, "b.png", fan.id); s = TagOps.assign(s, "b.png", scifi.id)
        s = TagOps.assign(s, "c.png", folder.id)
        return s
    }
    private val ents = listOf(
        TagEntity("a.png", "Alice", fav = true), TagEntity("b.png", "Bob", fav = false),
        TagEntity("c.png", "Cara", fav = false), TagEntity("d.png", "Dan", fav = false),
    )
    private fun id(s: TagState, n: String) = s.tags.first { it.name == n }.id
    private fun keys(l: List<TagEntity>) = l.map { it.key }

    @Test fun filterIncludeAnd() {
        val s = state()
        assertEquals(listOf("b.png"), keys(TagFilter.apply(ents, s, setOf(id(s, "Fantasy"), id(s, "SciFi")), emptySet(), false, "")))
    }

    @Test fun filterExclude() {
        val s = state()
        assertEquals(listOf("a.png", "d.png"), keys(TagFilter.apply(ents, s, emptySet(), setOf(id(s, "SciFi")), false, "")).filter { it != "c.png" })
    }

    @Test fun closedFolderHiddenUnlessSelected() {
        val s = state()
        assertFalse("c.png" in keys(TagFilter.apply(ents, s, emptySet(), emptySet(), false, "")))
        assertEquals(listOf("c.png"), keys(TagFilter.apply(ents, s, setOf(id(s, "Hidden")), emptySet(), false, "")))
    }

    @Test fun favoritesOnly() = assertEquals(listOf("a.png"), keys(TagFilter.apply(ents, state(), emptySet(), emptySet(), true, "")))

    @Test fun queryMatchesTagName() {
        assertEquals(listOf("a.png", "b.png"), keys(TagFilter.apply(ents, state(), emptySet(), emptySet(), false, "fantas")))
        assertEquals(listOf("b.png"), keys(TagFilter.apply(ents, state(), emptySet(), emptySet(), false, "bob")))
    }

    @Test fun settingsRoundTripKeepsTags() {
        val s = state()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val a = AppSettings(tags = s.tags, tagMap = s.map)
        val back = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), a))
        assertEquals(a.tags, back.tags); assertEquals(a.tagMap, back.tagMap)
        assertTrue(json.decodeFromString(AppSettings.serializer(), "{}").tags.isEmpty())
    }
}
