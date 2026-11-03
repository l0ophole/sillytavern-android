package app.sillytavern.android.extensions

import app.sillytavern.android.command.*
import app.sillytavern.android.macro.MacroRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtensionHostTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var repo: ExtensionRepository
    private var cfg = ExtensionSettings()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sent = mutableListOf<String>()
    private val env = object : ExtensionEnv {
        override suspend fun echo(text: String) { sent += "echo:$text" }
        override suspend fun sendMessage(text: String) { sent += "send:$text" }
        override suspend fun addMessage(name: String, text: String) { sent += "add:$name:$text" }
        override suspend fun generateQuiet(prompt: String, useHistory: Boolean) = "gen($prompt,$useHistory)"
        override suspend fun runCommand(text: String) = "ran:$text"
        override fun context() = ExtContext("Seraphina", "Me", "chat1", 3, false)
        override fun messages(n: Int) = listOf(ExtLine("Me", "hello", true, false)).take(n)
    }
    private lateinit var host: ExtensionHost

    @Before fun setUp() { repo = ExtensionRepository(tmp.newFolder("ext")); host = ExtensionHost(repo, { cfg }, scope, timeoutMs = 1500).also { it.env = env } }
    @After fun tearDown() = runBlocking { host.stopAll(); scope.cancel(); Unit }

    private fun install(id: String, script: String, perms: List<String> = emptyList(), confirm: Boolean = true) {
        val dir = java.io.File(repo.root, id).also { it.mkdirs() }
        dir.resolve("manifest.json").writeText("""{"id":"$id","name":"$id","permissions":[${perms.joinToString(",") { "\"$it\"" }}]}""")
        dir.resolve("index.js").writeText(script)
        cfg = cfg.copy(enabled = cfg.enabled + id, confirmed = cfg.confirmed + (id to if (confirm) perms.toSet() else emptySet()))
    }
    private fun exec(line: String) = runBlocking { CommandExecutor(object : CommandHost { 
        override val variables get() = app.sillytavern.android.macro.VariableStore({ JsonObject(emptyMap()) }, {}, { emptyMap() }, {})
        override fun macroContext() = app.sillytavern.android.chat.MacroContext(user = "Me", char = "C")
        override suspend fun echo(text: String, severity: String) {}
    }).runText(line) }

    @Test fun commandRunsScriptAndIsUnregisteredOnDisable() = runBlocking {
        install("e1", "st.registerCommand('xshout', function (c) { return c.text.toUpperCase() + c.args.n; });", listOf("commands"))
        host.sync()
        assertEquals(ExtensionState.RUNNING, host.status.value["e1"]!!.state)
        assertEquals("HI2", exec("/xshout n=2 hi").pipe)
        cfg = cfg.copy(enabled = emptySet()); host.sync()
        assertNotNull(exec("/xshout hi").error)
        assertEquals(ExtensionState.STOPPED, host.status.value["e1"]!!.state)
    }

    @Test fun cannotShadowBuiltInCommandOrMacro() = runBlocking {
        install("e2", "try { st.registerCommand('echo', function(){}); } catch (e) { console.error('cmd:' + e.message); } try { st.registerMacro('user', function(){}); } catch (e) { console.error('mac:' + e.message); }", listOf("commands", "macros"))
        host.sync()
        val log = host.logs.value["e2"]!!.map { it.text }
        assertTrue(log.any { it.contains("built-in command") }); assertTrue(log.any { it.contains("built-in macro") })
    }

    @Test fun unconfirmedPermissionIsRefused() = runBlocking {
        install("e3", "try { st.setPrompt('x'); } catch (e) { console.error(e.message); }", listOf("prompt"), confirm = false)
        host.sync()
        assertTrue(host.logs.value["e3"]!!.single().text.contains("Permission \"prompt\""))
        assertTrue(host.prompts().isEmpty())
    }

    @Test fun macroIsExpandedThroughRegistry() = runBlocking {
        install("e4", "st.registerMacro('xdouble', function (a) { return a[0] + a[0]; });", listOf("macros"))
        host.sync()
        val def = MacroRegistry.find("xdouble")!!
        assertEquals("abab", def.eval(app.sillytavern.android.macro.MacroCall(app.sillytavern.android.chat.MacroContext(user = "u", char = "c"), listOf("ab"), null, "xdouble")))
    }

    @Test fun macrosCannotUseBlockingCalls() = runBlocking {
        install("e5", "st.registerMacro('xgen', function () { try { return st.generateQuiet('p'); } catch (e) { return 'denied:' + e.message; } });", listOf("macros", "generate"))
        host.sync()
        val out = MacroRegistry.find("xgen")!!.eval(app.sillytavern.android.macro.MacroCall(app.sillytavern.android.chat.MacroContext(user = "u", char = "c"), emptyList(), null, "xgen"))
        assertTrue(out!!.startsWith("denied:generateQuiet is not available inside a macro"))
    }

    @Test fun eventsPromptAndEnvCalls() = runBlocking {
        install("e6", """
            st.on('message_received', function (m) {
              st.setPrompt('last reply was ' + m.text.length + ' chars', { position: 1, depth: 2, role: 1 });
              st.addMessage('Bot', 'noted ' + st.context().character + ' ' + st.messages(5).length);
              st.echo(st.generateQuiet('summarize', true));
            });
        """.trimIndent(), listOf("prompt", "chat", "generate"))
        host.sync()
        host.fire("message_received", buildJsonObject { put("text", "hello") })
        assertEquals(listOf("add:Bot:noted Seraphina 1", "echo:gen(summarize,true)"), sent)
        val p = host.prompts().single()
        assertEquals("last reply was 5 chars", p.content); assertEquals(1, p.position); assertEquals(2, p.depth); assertEquals(1, p.role)
    }

    @Test fun storagePersistsAcrossRestart() = runBlocking {
        install("e7", "var n = Number(st.storage.get('n') || 0) + 1; st.storage.set('n', String(n)); st.registerCommand('xcount', function () { return String(n); });", listOf("commands"))
        host.sync()
        assertEquals("1", exec("/xcount").pipe)
        host.sync(reload = setOf("e7"))
        assertEquals("2", exec("/xcount").pipe)
        assertEquals("2", repo.readStorage("e7")["n"])
    }

    @Test fun loadErrorMarksFailedAndOthersKeepRunning() = runBlocking {
        install("bad", "this is not js")
        install("good", "st.registerCommand('xok', function () { return 'ok'; });", listOf("commands"))
        host.sync()
        assertEquals(ExtensionState.FAILED, host.status.value["bad"]!!.state)
        assertEquals(ExtensionState.RUNNING, host.status.value["good"]!!.state)
        assertEquals("ok", exec("/xok").pipe)
    }

    @Test fun hangingHandlerStopsOnlyThatExtension() = runBlocking {
        install("spin", "st.on('chat_changed', function () { while (true) {} });")
        install("calm", "st.on('chat_changed', function () { st.echo('calm'); });")
        host.sync()
        host.fire("chat_changed")
        delay(300)
        assertEquals(ExtensionState.FAILED, host.status.value["spin"]!!.state)
        assertEquals(ExtensionState.RUNNING, host.status.value["calm"]!!.state)
        assertTrue(sent.contains("echo:calm"))
    }

    @Test fun fetchGoesThroughHttpWithPermission() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("pong").setResponseCode(201))
            install("e8", "st.registerCommand('xping', function (c) { var r = st.fetch(c.text, {method: 'POST', body: 'x'}); return r.status + ':' + r.body; });", listOf("commands", "network"))
            host.sync()
            assertEquals("201:pong", exec("/xping ${server.url("/p")}").pipe)
            assertEquals("POST", server.takeRequest().method)
        }
    }

    @Test fun fetchRejectsNonHttpSchemes() = runBlocking {
        install("e9", "st.registerCommand('xf', function () { try { st.fetch('file:///etc/passwd'); } catch (e) { return e.message; } });", listOf("commands", "network"))
        host.sync()
        assertTrue(exec("/xf").pipe.contains("Only http and https"))
    }
}
