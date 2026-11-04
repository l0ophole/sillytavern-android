package app.sillytavern.android.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private fun tmp(): File = Files.createTempDirectory("bk").toFile()
    private fun paths(): StoragePaths = StoragePaths(File(tmp(), "data")).ensure()
    private fun backup(p: StoragePaths): ByteArray = ByteArrayOutputStream().also { BackupService(p, "u").write(it) }.toByteArray()

    @Test fun roundTripRestoresFilesAndKeepsSecrets() {
        val a = paths()
        File(a.characters, "x.png").writeBytes(byteArrayOf(1, 2, 3))
        File(a.chats, "x").mkdirs(); File(a.chats, "x/c.jsonl").writeText("line")
        a.settingsFile.writeText("{\"a\":1}")
        a.secretsFile.writeText("secret-a")
        File(a.root, "junk.tmp").writeText("t")
        val bytes = backup(a)
        assertFalse("secrets are never exported", String(bytes, Charsets.ISO_8859_1).contains("secret-a"))

        val b = paths()
        File(b.characters, "other.png").writeBytes(byteArrayOf(9))
        b.secretsFile.writeText("secret-b")
        BackupService(b).restore(ByteArrayInputStream(bytes))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(b.characters, "x.png").readBytes())
        assertEquals("line", File(b.chats, "x/c.jsonl").readText())
        assertEquals("{\"a\":1}", b.settingsFile.readText())
        assertFalse(File(b.characters, "other.png").exists())
        assertEquals("secret-b", b.secretsFile.readText())
        assertFalse(File(b.root, "junk.tmp").exists())
        assertTrue(File(b.root.parentFile, "data.before-restore/characters/other.png").exists())
    }

    @Test fun inspectReadsHeader() {
        val a = paths(); File(a.characters, "x.png").writeBytes(byteArrayOf(1))
        val h = BackupService(a, "me", { 42L }).let { s -> ByteArrayOutputStream().also { s.write(it) }.toByteArray() }
            .let { BackupService(a).inspect(ByteArrayInputStream(it)) }!!
        assertEquals("me", h.user); assertEquals(42L, h.createdAt); assertEquals(1, h.files)
        assertNull(BackupService(a).inspect(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
    }

    private fun zip(vararg entries: Pair<String, String>) = ByteArrayOutputStream().also { o ->
        ZipOutputStream(o).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun badZipsChangeNothing() {
        val p = paths(); File(p.characters, "keep.png").writeBytes(byteArrayOf(7))
        val hdr = "{\"format\":1}"
        val cases = listOf(
            zip("data/a" to "x"),                                              // no header first
            zip(BackupService.HEADER to hdr, "data/../evil" to "x"),          // traversal
            zip(BackupService.HEADER to hdr, "other/a" to "x"),               // outside data/
            zip(BackupService.HEADER to "{\"format\":9}", "data/a" to "x"),   // newer format
            byteArrayOf(1, 2, 3),
        )
        for (c in cases) {
            try { BackupService(p).restore(ByteArrayInputStream(c)); fail("accepted") } catch (_: BackupException) {} catch (_: java.io.IOException) {}
            assertTrue(File(p.characters, "keep.png").exists())
            assertFalse(File(p.root.parentFile, "data.before-restore").exists())
            assertFalse(File(p.root.parentFile, "data.restoring").exists())
        }
    }

    @Test fun autoBackupRunsWhenDueAndRotates() {
        val p = paths(); File(p.characters, "x.png").writeBytes(byteArrayOf(1))
        var t = 1_000_000_000_000L
        val dir = File(tmp(), "backups/u")
        val auto = AutoBackup(dir, BackupService(p, "u", { t }), { t })
        val s = DataSettings(autoBackup = true, intervalHours = 24, keep = 2)
        assertNull(auto.runIfDue(DataSettings()))
        assertNotNull(auto.runIfDue(s))
        t += 3_600_000; assertNull("not due yet", auto.runIfDue(s))
        t += 24 * 3_600_000L; assertNotNull(auto.runIfDue(s))
        t += 25 * 3_600_000L; assertNotNull(auto.runIfDue(s))
        assertEquals(2, auto.list().size)
        assertTrue(auto.list().none { it.name.endsWith(".tmp") })
    }
}
