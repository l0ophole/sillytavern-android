package app.sillytavern.android.extensions

import app.cash.quickjs.QuickJs
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.concurrent.Executors

/** Native → Kotlin entry point; must be a public interface for QuickJS to bind it. */
interface HostBridge { fun call(name: String, args: String): String }

/** Kotlin → JS entry point (the `__d` object created by the prelude). */
interface JsDispatcher { fun dispatch(kind: String, json: String): String }

class ScriptFault(message: String) : Exception(message)

/**
 * One QuickJS runtime on its own thread (QuickJS is not thread safe). The runtime cannot be interrupted, so a call that runs past
 * [timeoutMs] marks the engine [faulted]: it is never used again and its (daemon) thread is abandoned.
 */
class QuickJsEngine(
    private val label: String,
    private val timeoutMs: Long = 5_000,
    private val host: (name: String, args: JsonElement) -> JsonElement,
) : AutoCloseable {
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ext-$label").apply { isDaemon = true } }
    private val dispatcher = thread.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var engineThread: Thread? = null
    private var js: QuickJs? = null
    private var d: JsDispatcher? = null
    @Volatile var faulted = false
        private set

    private val bridge = object : HostBridge {
        override fun call(name: String, args: String): String = try {
            val result = host(name, Json.parseToJsonElement(args))
            buildJsonObject { put("value", result) }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: e.javaClass.simpleName) }.toString()
        }
    }

    /** Runs the prelude and then [source]; a script that throws at load time makes the engine unusable. */
    suspend fun load(source: String, fileName: String) {
        runOnEngine {
            engineThread = Thread.currentThread()
            val q = QuickJs.create()
            js = q
            q.set("__host", HostBridge::class.java, bridge)
            q.evaluate(Prelude.SOURCE, "prelude.js")
            d = q.get("__d", JsDispatcher::class.java)
            q.evaluate(source, fileName)
        }
    }

    /** Calls a handler; returns its value, or throws [ScriptFault] with the script's error message. */
    suspend fun dispatch(kind: String, payload: JsonElement): JsonElement? {
        val raw = runOnEngine { (d ?: throw ScriptFault("The script is not loaded")).dispatch(kind, payload.toString()) }
        val o = Json.parseToJsonElement(raw).jsonObject
        (o["error"] as? JsonPrimitive)?.contentOrNull?.let { throw ScriptFault(it) }
        return o["value"]
    }

    private suspend fun <T> runOnEngine(block: () -> T): T {
        if (faulted) throw ScriptFault("The extension was stopped after a timeout")
        // A script calling a command of its own re-enters on the engine thread: run it directly (the thread is already ours).
        if (Thread.currentThread() === engineThread) return block()
        // A detached scope: a structured parent would wait for the stuck, uninterruptible block.
        return run {
            val work = scope.async { try { block() } catch (e: ScriptFault) { throw e } catch (e: app.cash.quickjs.QuickJsException) { throw ScriptFault(e.message ?: "Script error") } }
            val done = withTimeoutOrNull(timeoutMs) { work.await() }
            if (done == null && !work.isCompleted) {
                faulted = true
                work.cancel()
                throw ScriptFault("The script took longer than ${timeoutMs / 1000}s and was stopped")
            }
            @Suppress("UNCHECKED_CAST") (done ?: work.await()) as T
        }
    }

    override fun close() {
        if (faulted) return
        val q = js
        js = null; d = null
        thread.submit { q?.close() }
        thread.shutdown()
    }
}
