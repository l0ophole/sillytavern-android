package app.sillytavern.android.appearance

import app.sillytavern.android.data.StoragePaths
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BackgroundRepositoryTest {
    private fun repo() = BackgroundRepository(StoragePaths(Files.createTempDirectory("bg").toFile()).ensure())

    @Test fun importAndList() {
        val r = repo()
        assertEquals("forest.png", r.import("forest.png", byteArrayOf(1)).getOrThrow())
        assertEquals(listOf("forest.png"), r.list())
        assertNotNull(r.file("forest.png"))
    }
    @Test fun duplicateNamesGetSuffix() {
        val r = repo(); r.import("a.jpg", byteArrayOf(1)); assertEquals("a (2).jpg", r.import("a.jpg", byteArrayOf(2)).getOrThrow())
    }
    @Test fun rejectsNonImagesAndEmpty() {
        val r = repo()
        assertTrue(r.import("a.txt", byteArrayOf(1)).isFailure); assertTrue(r.import("a.png", byteArrayOf()).isFailure); assertTrue(r.list().isEmpty())
    }
    @Test fun pathTraversalStaysInFolder() {
        val r = repo()
        val n = r.import("../../evil.png", byteArrayOf(1)).getOrThrow()
        assertEquals("evil.png", n); assertNull(r.file("../x.png"))
    }
    @Test fun deleteRemoves() { val r = repo(); r.import("a.png", byteArrayOf(1)); assertTrue(r.delete("a.png")); assertTrue(r.list().isEmpty()); assertFalse(r.delete("a.png")) }
    @Test fun blankNameHasNoFile() = assertNull(repo().file(""))
}
