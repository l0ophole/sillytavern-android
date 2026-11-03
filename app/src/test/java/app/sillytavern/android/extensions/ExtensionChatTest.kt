package app.sillytavern.android.extensions

import app.sillytavern.android.chat.ChatFeaturesTestBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExtensionChatTest : ChatFeaturesTestBase() {
    private suspend fun TestScope.eventually(what: String, cond: () -> Boolean) {
        repeat(150) { advanceUntilIdle(); if (cond()) return; Thread.sleep(20) }
        fail("Timed out waiting for $what")
    }

    @Test fun eventsPromptsAndCommandsWorkAgainstARealChat() = runTest {
        val repo = ExtensionRepository(java.nio.file.Files.createTempDirectory("exts").toFile())
        java.io.File(repo.root, "demo").apply { mkdirs() }.let {
            it.resolve("manifest.json").writeText("""{"id":"demo","permissions":["prompt","commands","chat"]}""")
            it.resolve("index.js").writeText("""
                st.on('message_sent', function (m) { st.setPrompt('SAW:' + m.text, { position: 1, depth: 0 }); });
                st.on('message_received', function (m) { st.storage.set('last', m.name + '=' + m.text); });
                st.registerCommand('xwho', function () { return st.context().character + '/' + st.context().user + '/' + st.messages(1).length; });
            """.trimIndent())
        }
        val storage = ExtensionSettings(setOf("demo"), mapOf("demo" to setOf("prompt", "commands", "chat")))
        val host = ExtensionHost(repo, { storage }, this)
        val c = controller()
        c.extensions = host
        c.open(ann)
        host.sync()

        c.send("one"); eventually("first reply ${host.status.value} ${host.logs.value} ${c.state.value.error} ${c.state.value.chat?.messages?.map { it.text }}") { repo.readStorage("demo")["last"] == "Ann=reply" }
        c.send("two"); eventually("second request") { requests.size == 2 }
        assertTrue(lastContents().any { it.contains("SAW:one") })

        c.submit("/xwho"); eventually("command") { c.state.value.error == null && c.state.value.chat!!.messages.size >= 4 }
        host.stopAll()
    }
}
