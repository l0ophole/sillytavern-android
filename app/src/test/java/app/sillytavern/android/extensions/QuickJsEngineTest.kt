package app.sillytavern.android.extensions

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class QuickJsEngineTest {
    private val calls = mutableListOf<Pair<String, JsonElement>>()
    private fun engine(timeout: Long = 5_000, host: (String, JsonElement) -> JsonElement = { n, a -> calls += n to a; JsonPrimitive("host:$n") }) =
        QuickJsEngine("t", timeout, host)

    @Test fun loadsAndDispatchesCommand() = runBlocking {
        engine().use { e ->
            e.load("st.registerCommand('shout', function (c) { return c.text.toUpperCase() + '!' + (c.args.n || ''); });", "index.js")
            val r = e.dispatch("command", buildJsonObject { put("name", "shout"); put("text", "hey"); put("args", buildJsonObject { put("n", "1") }) })
            assertEquals("HEY!1", (r as JsonPrimitive).content)
            assertEquals("registerCommand", calls.first().first)
        }
    }

    @Test fun eventsReachAllHandlersAndErrorsDoNotBreakOthers() = runBlocking {
        engine { n, a -> if (n == "log") calls += n to a; JsonNull }.use { e ->
            e.load("""
                st.on('message_received', function (m) { throw new Error('boom'); });
                st.on('message_received', function (m) { console.log('got ' + m.text); });
            """.trimIndent(), "index.js")
            e.dispatch("event", buildJsonObject { put("name", "message_received"); put("data", buildJsonObject { put("text", "hi") }) })
            val logs = calls.map { it.second.jsonObject["text"]!!.jsonPrimitive.content }
            assertEquals(listOf("boom", "got hi"), logs.map { it.removePrefix("Error: ") })
        }
    }

    @Test fun commandErrorsBecomeScriptFaultMessages() = runBlocking {
        engine().use { e ->
            e.load("st.registerCommand('bad', function () { throw new Error('nope'); });", "index.js")
            val ex = runCatching { e.dispatch("command", buildJsonObject { put("name", "bad") }) }.exceptionOrNull()
            assertTrue(ex is ScriptFault); assertEquals("nope", ex!!.message)
            assertFalse(e.faulted)
        }
    }

    @Test fun hostErrorsAreThrownInJs() = runBlocking {
        engine { n, _ -> if (n == "echo") throw ExtensionException("needs permission") else JsonNull }.use { e ->
            e.load("var msg; try { st.echo('x'); } catch (err) { msg = err.message; } st.registerCommand('m', function () { return msg; });", "index.js")
            assertEquals("needs permission", (e.dispatch("command", buildJsonObject { put("name", "m") }) as JsonPrimitive).content)
        }
    }

    @Test fun syntaxErrorAtLoadIsAFault() = runBlocking {
        engine().use { e ->
            val ex = runCatching { e.load("this is not js", "index.js") }.exceptionOrNull()
            assertTrue(ex is ScriptFault)
        }
    }

    @Test fun infiniteLoopTimesOutAndEngineIsRetired() = runBlocking {
        val e = engine(timeout = 300)
        e.load("st.registerCommand('spin', function () { while (true) {} });", "index.js")
        val ex = runCatching { e.dispatch("command", buildJsonObject { put("name", "spin") }) }.exceptionOrNull()
        assertTrue(ex is ScriptFault)
        assertTrue(e.faulted)
        assertTrue(runCatching { e.dispatch("event", buildJsonObject { put("name", "x") }) }.exceptionOrNull() is ScriptFault)
    }

    @Test fun macrosAndStorageApiShape() = runBlocking {
        engine { n, a -> if (n == "storageGet") JsonPrimitive("saved") else JsonNull }.use { e ->
            e.load("st.registerMacro('stored', function (a) { return st.storage.get(a[0]) + a.length; });", "index.js")
            val r = e.dispatch("macro", buildJsonObject { put("name", "stored"); put("args", buildJsonArray { add("k") }) })
            assertEquals("saved1", (r as JsonPrimitive).content)
        }
    }
}
