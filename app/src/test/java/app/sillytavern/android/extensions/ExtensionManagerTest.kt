package app.sillytavern.android.extensions

import app.sillytavern.android.data.AppSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExtensionManagerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun zip(script: String, perms: String = "\"commands\"") = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("""{"id":"m1","permissions":[$perms]}""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("index.js")); z.write(script.toByteArray()); z.closeEntry()
        }
    }.toByteArray().let(::ByteArrayInputStream)

    @Test fun installEnableReinstallDisableRemove() = runBlocking {
        var s = AppSettings()
        val repo = ExtensionRepository(tmp.newFolder("e"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = ExtensionHost(repo, { s.extensions }, scope)
        val m = ExtensionManager(repo, host, { s }, { f -> s = f(s) })
        try {
            m.install(zip("st.registerCommand('xm', function () { return '1'; });"))
            assertFalse(m.isEnabled("m1"))
            assertEquals(ExtensionState.STOPPED, host.status.value["m1"]?.state)

            m.enable("m1")
            assertEquals(ExtensionState.RUNNING, host.status.value["m1"]!!.state)
            assertEquals(setOf("commands"), s.extensions.confirmed["m1"])

            m.install(zip("st.registerCommand('xm', function () { return '2'; });", "\"commands\",\"network\""))
            assertFalse("a reinstall needs fresh approval", m.isEnabled("m1"))
            assertTrue(s.extensions.confirmed["m1"] == null)
            assertEquals(ExtensionState.STOPPED, host.status.value["m1"]!!.state)

            m.enable("m1")
            assertEquals(setOf("commands", "network"), s.extensions.confirmed["m1"])
            m.disable("m1")
            assertEquals(ExtensionState.STOPPED, host.status.value["m1"]!!.state)

            m.remove("m1")
            assertNull(repo.get("m1"))
            assertFalse(s.extensions.enabled.contains("m1"))
        } finally { host.stopAll(); scope.cancel() }
    }
}
