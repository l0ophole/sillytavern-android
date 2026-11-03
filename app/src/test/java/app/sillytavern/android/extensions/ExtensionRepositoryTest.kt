package app.sillytavern.android.extensions

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return ByteArrayInputStream(out.toByteArray())
    }
    private val manifest = """{"id":"hello","name":"Hello","version":"1.0","permissions":["commands","prompt"]}"""
    private fun repo() = ExtensionRepository(tmp.newFolder("ext"))

    @Test fun manifestParsesAndDefaults() {
        val m = ExtensionManifest.parse(manifest)
        assertEquals("index.js", m.script)
        assertEquals(setOf(Permission.COMMANDS, Permission.PROMPT), m.granted)
    }

    @Test fun manifestRejectsBadIdScriptAndPermission() {
        assertThrows(ExtensionException::class.java) { ExtensionManifest.parse("""{"id":"Bad Id"}""") }
        assertThrows(ExtensionException::class.java) { ExtensionManifest.parse("""{"id":"a","script":"../x.js"}""") }
        assertThrows(ExtensionException::class.java) { ExtensionManifest.parse("""{"id":"a","script":"a.txt"}""") }
        assertThrows(ExtensionException::class.java) { ExtensionManifest.parse("""{"id":"a","permissions":["root"]}""") }
        assertThrows(ExtensionException::class.java) { ExtensionManifest.parse("not json") }
    }

    @Test fun installsTopLevelZip() {
        val r = repo()
        val e = r.installZip(zip("manifest.json" to manifest, "index.js" to "1", "lib/util.js" to "2"))
        assertEquals("hello", e.id)
        assertTrue(e.scriptFile().isFile)
        assertTrue(java.io.File(e.dir, "lib/util.js").isFile)
        assertEquals(listOf("hello"), r.list().map { it.id })
    }

    @Test fun installsZipWithSingleTopFolder() {
        val e = repo().installZip(zip("my-ext/manifest.json" to manifest, "my-ext/index.js" to "1", "README" to "x"))
        assertTrue(e.scriptFile().isFile)
        assertFalse(java.io.File(e.dir, "README").exists())
    }

    @Test fun rejectsZipSlipMissingManifestAndMissingScript() {
        val r = repo()
        assertThrows(ExtensionException::class.java) { r.installZip(zip("../evil.js" to "1", "manifest.json" to manifest)) }
        assertThrows(ExtensionException::class.java) { r.installZip(zip("index.js" to "1")) }
        assertThrows(ExtensionException::class.java) { r.installZip(zip("manifest.json" to manifest)) }
        assertTrue(r.list().isEmpty())
    }

    @Test fun reinstallKeepsStorageAndDropsOldFiles() {
        val r = repo()
        r.installZip(zip("manifest.json" to manifest, "index.js" to "1", "old.js" to "x"))
        r.writeStorage("hello", mapOf("k" to "v"))
        val e = r.installZip(zip("manifest.json" to manifest, "index.js" to "2"))
        assertEquals("2", e.scriptFile().readText())
        assertFalse(java.io.File(e.dir, "old.js").exists())
        assertEquals(mapOf("k" to "v"), r.readStorage("hello"))
    }

    @Test fun storageRoundTripAndRemove() {
        val r = repo()
        r.installZip(zip("manifest.json" to manifest, "index.js" to "1"))
        assertTrue(r.readStorage("hello").isEmpty())
        r.writeStorage("hello", mapOf("a" to "1", "b" to "two"))
        assertEquals(mapOf("a" to "1", "b" to "two"), r.readStorage("hello"))
        assertTrue(r.remove("hello"))
        assertNull(r.get("hello"))
        assertFalse(r.remove("../etc"))
    }

    @Test fun oversizedFileIsRejected() {
        val big = "x".repeat(ExtensionRepository.MAX_FILE_BYTES + 1)
        assertThrows(ExtensionException::class.java) { repo().installZip(zip("manifest.json" to manifest, "index.js" to big)) }
    }
}
