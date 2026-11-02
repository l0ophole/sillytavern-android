package app.sillytavern.android.worldinfo

import org.junit.Assert.*
import org.junit.Test

class WorldEditingTest {
    private val b = WorldBook("b", listOf(WorldEntry(uid = 0, content = "a"), WorldEntry(uid = 5, content = "b")))

    @Test fun addEntryUsesNextFreeUid() = assertEquals(6, b.addEntry().entries.last().uid)
    @Test fun addEntryOnEmptyBookStartsAtZero() = assertEquals(0, WorldBook("x").addEntry().entries.single().uid)
    @Test fun duplicateKeepsContentNewUid() {
        val d = b.duplicateEntry(0)
        assertEquals(3, d.entries.size); assertEquals("a", d.entries[1].content); assertEquals(6, d.entries[1].uid)
    }
    @Test fun deleteRemoves() = assertEquals(listOf(5), b.deleteEntry(0).entries.map { it.uid })
    @Test fun updateReplacesByUid() = assertEquals("z", b.updateEntry(b.entries[1].copy(content = "z")).entries[1].content)
    @Test fun moveClamps() {
        assertEquals(listOf(5, 0), b.moveEntry(5, -1).entries.map { it.uid })
        assertEquals(listOf(0, 5), b.moveEntry(5, 3).entries.map { it.uid })
        assertEquals(listOf(5, 0), b.moveEntry(0, 9).entries.map { it.uid })
    }
    @Test fun parseKeysTrimsAndDropsBlank() = assertEquals(listOf("a", "b", "c"), parseKeys("a, b ,,c"))
    @Test fun parseKeysKeepsCommaInsideRegex() = assertEquals(listOf("/a,b/i", "c"), parseKeys("/a,b/i, c"))
    @Test fun formatKeysRoundTrip() = assertEquals(listOf("x y", "/a,b/i"), parseKeys(formatKeys(listOf("x y", "/a,b/i"))))
}
