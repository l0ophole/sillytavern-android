package app.sillytavern.android.media

import app.sillytavern.android.data.StoragePaths
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun repo() = GalleryRepository(StoragePaths(tmp.newFolder("d")).ensure())

    @Test fun addListAndFolders() {
        val r = repo()
        assertTrue(r.folders().isEmpty())
        val a = r.add("Ann", "one.png", byteArrayOf(1)); a.file.setLastModified(1000)
        val b = r.add("Ann", "one.png", byteArrayOf(2)); b.file.setLastModified(5000)
        r.add("Bob", "x.jpg", byteArrayOf(3))
        assertEquals("user/images/Ann/one.png", a.path); assertEquals("user/images/Ann/one_1.png", b.path)
        assertEquals(listOf("one_1.png", "one.png"), r.list("Ann").map { it.name })
        assertEquals(listOf("Ann" to 2, "Bob" to 1), r.folders())
    }

    @Test fun nonImagesAreIgnoredAndOddExtensionsBecomePng() {
        val r = repo()
        File(r.add("Ann", "a.png", byteArrayOf(1)).file.parentFile, "notes.txt").writeText("t")
        assertEquals(1, r.list("Ann").size)
        assertEquals("weird.png", r.add("Ann", "weird.exe", byteArrayOf(1)).name)
    }

    @Test fun deleteStaysInsideTheImagesFolder() {
        val d = tmp.newFolder("e"); val paths = StoragePaths(d).ensure(); val r = GalleryRepository(paths)
        val img = r.add("Ann", "a.png", byteArrayOf(1))
        File(d, "settings.json").writeText("{}")
        assertFalse(r.delete("settings.json")); assertFalse(r.delete("user/images/../../settings.json"))
        assertTrue(File(d, "settings.json").exists())
        assertTrue(r.delete(img.path)); assertFalse(img.file.exists()); assertFalse(r.delete(img.path))
    }
}
