package app.sillytavern.android.worldinfo

import org.junit.Assert.*
import org.junit.Test

class WorldInfoAssemblerTest {
    private fun se(content: String, order: Int = 100, position: Int = 0, block: WorldEntry.() -> WorldEntry = { this }) =
        ScanEntry("b", WorldEntry(uid = content.hashCode(), content = content, order = order, position = position).block())
    private fun asm(vararg e: ScanEntry) = WorldInfoAssembler.assemble(e.toList()) { it.replace("{{char}}", "Ann") }

    @Test fun beforeAfterJoinedInEmissionOrder() {
        val r = asm(se("c10", 10), se("c20", 20), se("a1", 5, 1))
        assertEquals("c10\nc20", r.before); assertEquals("a1", r.after)
    }
    @Test fun depthGroupedByDepthAndRole() {
        val r = asm(
            se("x", 1, 4) { copy(depth = 2, role = 0) }, se("y", 2, 4) { copy(depth = 2, role = 0) },
            se("z", 3, 4) { copy(depth = 2, role = 1) }, se("w", 4, 4) { copy(depth = 0, role = 0) },
        )
        assertEquals(3, r.depth.size)
        assertEquals("x\ny", r.depth.first { it.depth == 2 && it.role == 0 }.content)
        assertEquals("z", r.depth.first { it.role == 1 }.content)
    }
    @Test fun blankContentSkipped() { val r = asm(se("  "), se("k")); assertEquals("k", r.before) }
    @Test fun outletWithoutNameSkipped() {
        val r = asm(se("lost", 1, 7), se("kept", 1, 7) { copy(outletName = "o") })
        assertEquals(mapOf("o" to "kept"), r.outlets)
    }
    @Test fun macrosExpanded() = assertEquals("Hi Ann", asm(se("Hi {{char}}")).before)
    @Test fun examplesAndNoteBuckets() {
        val r = asm(se("t", 1, 5), se("b", 1, 6), se("nt", 1, 2), se("nb", 1, 3))
        assertEquals(listOf("t"), r.exampleTop); assertEquals(listOf("b"), r.exampleBottom)
        assertEquals(listOf("nt"), r.noteTop); assertEquals(listOf("nb"), r.noteBottom)
    }
}
