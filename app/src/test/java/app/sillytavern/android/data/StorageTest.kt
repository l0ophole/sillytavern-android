package app.sillytavern.android.data

import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class StorageTest {
    private fun tmp(): File = Files.createTempDirectory("st").toFile()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun crypto() = AesGcmCrypto { key }

    @Test fun secretsRoundTripAndAreNotPlaintext() {
        val f = File(tmp(), "s.json")
        val s = SecretStore(f, crypto())
        s.set("a", "sk-supersecret")
        assertEquals("sk-supersecret", s.get("a"))
        assertFalse(f.readText().contains("supersecret"))
        assertEquals("sk-supersecret", SecretStore(f, crypto()).get("a"))
        s.remove("a")
        assertNull(s.get("a"))
    }

    @Test fun settingsPersist() {
        val f = File(tmp(), "settings.json")
        val repo = SettingsRepository(f)
        repo.update { it.copy(connections = listOf(ConnectionProfile(name = "x"))) }
        assertEquals("x", SettingsRepository(f).settings.value.connections.single().name)
    }

    @Test fun corruptSettingsFallBackToDefaults() {
        val f = File(tmp(), "settings.json").apply { writeText("{not json") }
        assertTrue(SettingsRepository(f).settings.value.connections.isEmpty())
    }

    @Test fun sanitizeBlocksTraversal() {
        assertFalse(StoragePaths.sanitizeFileName("../../etc/passwd").contains("/"))
        assertEquals("unnamed", StoragePaths.sanitizeFileName("..."))
    }
}
