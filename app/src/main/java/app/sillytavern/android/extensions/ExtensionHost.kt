package app.sillytavern.android.extensions

import app.sillytavern.android.command.CommandDef
import app.sillytavern.android.command.CommandError
import app.sillytavern.android.command.CommandRegistry
import app.sillytavern.android.macro.MacroDef
import app.sillytavern.android.macro.MacroRegistry
import app.sillytavern.android.prompt.ExtensionPrompt
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** What scripts can reach in the running app; implemented by the chat controller. */
interface ExtensionEnv {
    suspend fun echo(text: String)
    suspend fun sendMessage(text: String)
    suspend fun addMessage(name: String, text: String)
    suspend fun generateQuiet(prompt: String, useHistory: Boolean): String
    suspend fun runCommand(text: String): String
    fun context(): ExtContext
    fun messages(n: Int): List<ExtLine>
}

data class ExtContext(val character: String = "", val user: String = "", val chatName: String = "", val messageCount: Int = 0, val groupChat: Boolean = false)
data class ExtLine(val name: String, val text: String, val isUser: Boolean, val isSystem: Boolean)

data class HttpReply(val status: Int, val body: String)

fun interface ExtensionHttp { fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HttpReply }

class OkHttpExtensionHttp(private val client: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS).followRedirects(true).build()) : ExtensionHttp {
    override fun fetch(url: String, method: String, headers: Map<String, String>, body: String?): HttpReply {
        val u = okhttp3.HttpUrl.Companion.run { url.toHttpUrlOrNull() }
        if (u == null || (u.scheme != "http" && u.scheme != "https")) throw ExtensionException("Only http and https addresses can be fetched")
        val m = method.uppercase()
        val rb = if (m == "GET" || m == "HEAD") null else (body ?: "").toRequestBody((headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "text/plain; charset=utf-8").toMediaTypeOrNull())
        val req = Request.Builder().url(u).method(m, rb).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { r ->
            val src = r.body?.source()
            val bytes = src?.let { s -> s.request(MAX_BODY + 1); s.buffer.readByteArray(minOf(s.buffer.size, MAX_BODY)) } ?: ByteArray(0)
            return HttpReply(r.code, bytes.toString(Charsets.UTF_8))
        }
    }
    companion object { const val MAX_BODY = 1024L * 1024 }
}

enum class ExtensionState { STOPPED, RUNNING, FAILED }
data class ExtensionStatus(val state: ExtensionState, val error: String? = null)
data class LogLine(val level: String, val text: String)

/**
 * Runs enabled extensions, routes chat events, slash commands and macros to their scripts, and holds the prompts and storage they set.
 * Everything a script does is checked against its manifest permissions *and* the permissions the user confirmed.
 */
class ExtensionHost(
    private val repo: ExtensionRepository,
    private val settings: () -> ExtensionSettings,
    private val scope: CoroutineScope,
    private val http: ExtensionHttp = OkHttpExtensionHttp(),
    private val timeoutMs: Long = 5_000,
    private val macroTimeoutMs: Long = 1_000,
) {
    @Volatile var env: ExtensionEnv? = null

    private val _status = MutableStateFlow<Map<String, ExtensionStatus>>(emptyMap())
    val status: StateFlow<Map<String, ExtensionStatus>> = _status
    private val _logs = MutableStateFlow<Map<String, List<LogLine>>>(emptyMap())
    val logs: StateFlow<Map<String, List<LogLine>>> = _logs

    private class Running(val ext: InstalledExtension, val perms: Set<Permission>, val engine: QuickJsEngine) {
        val commands = HashSet<String>()
        val macros = HashSet<String>()
        @Volatile var prompt: ExtensionPrompt? = null
        var macroMode = false          // only touched on the engine thread
        var storage: MutableMap<String, String> = HashMap()
    }

    private val running = java.util.concurrent.ConcurrentHashMap<String, Running>()
    private val lock = Mutex()
    private val macroOwners = java.util.concurrent.ConcurrentHashMap<String, String>() // macro name -> extension id
    private val commandOwners = java.util.concurrent.ConcurrentHashMap<String, String>()

    init {
        current = this
        resolverOnce
    }

    /** Starts enabled extensions that are not running, stops the others, and restarts the ones whose script changed on install. */
    suspend fun sync(reload: Set<String> = emptySet()) = lock.withLock {
        val enabled = settings().enabled
        (running.keys - enabled + running.keys.intersect(reload)).toList().forEach { stopLocked(it) }
        for (id in enabled) {
            if (running.containsKey(id)) continue
            val ext = repo.get(id)
            if (ext == null) { setStatus(id, ExtensionStatus(ExtensionState.FAILED, "Not installed")); continue }
            startLocked(ext)
        }
        _status.update { st -> st.filterKeys { it in enabled || running.containsKey(it) } .toMutableMap().also { m -> repo.list().forEach { e -> if (e.id !in m) m[e.id] = ExtensionStatus(ExtensionState.STOPPED) } } }
    }

    suspend fun stopAll() = lock.withLock { running.keys.toList().forEach { stopLocked(it) } }

    private suspend fun startLocked(ext: InstalledExtension) {
        val perms = ext.manifest.granted.filter { it.key in settings().confirmed[ext.id].orEmpty() }.toSet()
        lateinit var r: Running
        val engine = QuickJsEngine(ext.id, timeoutMs) { name, args -> handle(r, name, args) }
        r = Running(ext, perms, engine)
        r.storage = repo.readStorage(ext.id).toMutableMap()
        try {
            val file = ext.scriptFile()
            if (!file.isFile) throw ScriptFault("Script ${ext.manifest.script} not found")
            running[ext.id] = r
            engine.load(file.readText(), ext.manifest.script)
            setStatus(ext.id, ExtensionStatus(ExtensionState.RUNNING))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            stopLocked(ext.id)
            log(ext.id, "error", e.message ?: e.javaClass.simpleName)
            setStatus(ext.id, ExtensionStatus(ExtensionState.FAILED, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun stopLocked(id: String) {
        val r = running.remove(id) ?: return
        r.commands.forEach { CommandRegistry.unregister(it); commandOwners.remove(it) }
        r.macros.forEach { macroOwners.remove(it) }
        runCatching { r.engine.close() }
        setStatus(id, ExtensionStatus(ExtensionState.STOPPED))
    }

    private fun setStatus(id: String, s: ExtensionStatus) = _status.update { it + (id to s) }

    private fun log(id: String, level: String, text: String) = _logs.update { m -> m + (id to (m[id].orEmpty() + LogLine(level, text.take(2000))).takeLast(MAX_LOG)) }

    fun clearLog(id: String) = _logs.update { it - id }

    /** Prompts currently set by extensions, for the next generation. */
    fun prompts(): List<ExtensionPrompt> = running.values.mapNotNull { it.prompt }

    /** Delivers a chat event to every running extension. A failing or hanging script is logged / stopped, never propagated. */
    suspend fun fire(event: String, data: JsonElement = JsonNull) {
        val payload = buildJsonObject { put("name", event); put("data", data) }
        for (r in running.values.toList()) {
            try { r.engine.dispatch("event", payload) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(r, e) }
        }
    }

    private fun failed(r: Running, e: Exception) {
        log(r.ext.id, "error", e.message ?: e.javaClass.simpleName)
        if (r.engine.faulted) { setStatus(r.ext.id, ExtensionStatus(ExtensionState.FAILED, e.message)); scope.launch { lock.withLock { stopLocked(r.ext.id); setStatus(r.ext.id, ExtensionStatus(ExtensionState.FAILED, e.message)) } } }
    }

    // ------------------------------------------------------------------ commands + macros

    private suspend fun runCommand(r: Running, name: String, named: Map<String, String>, text: String, pipe: String): String = try {
        val args = buildJsonObject { put("name", name); put("text", text); put("pipe", pipe); put("args", JsonObject(named.mapValues { JsonPrimitive(it.value) })) }
        (r.engine.dispatch("command", args) as? JsonPrimitive)?.contentOrNull.orEmpty()
    } catch (e: CancellationException) { throw e } catch (e: Exception) { failed(r, e); throw CommandError("${r.ext.manifest.name}: ${e.message}") }

    private fun runMacro(id: String, name: String, args: List<String>): String? {
        val r = running[id] ?: return null
        return try {
            val payload = buildJsonObject { put("name", name); put("args", JsonArray(args.map { JsonPrimitive(it) })) }
            // Macro expansion is synchronous; bounded by its own short timeout, and blocking calls are refused in macroMode.
            runBlocking { withTimeoutOrNull(macroTimeoutMs) { r.engine.dispatch("macro", payload) } }
                .let { (it as? JsonPrimitive)?.contentOrNull }
        } catch (e: Exception) { failed(r, e); null }
    }

    // ------------------------------------------------------------------ bridge

    private fun need(r: Running, p: Permission) {
        if (p !in r.perms) throw ExtensionException("Permission \"${p.key}\" is not granted to ${r.ext.id}")
    }

    private fun blocking(r: Running, what: String) {
        if (r.macroMode) throw ExtensionException("$what is not available inside a macro")
    }

    private fun str(a: JsonElement, k: String) = (a.jsonObject[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun handle(r: Running, name: String, a: JsonElement): JsonElement {
        fun e() = env ?: throw ExtensionException("No chat is open")
        return when (name) {
            "log" -> { log(r.ext.id, str(a, "level").ifBlank { "info" }, str(a, "text")); JsonNull }
            "macroMode" -> { r.macroMode = a is JsonPrimitive && a.booleanOrNull == true; JsonNull }
            "registerCommand" -> {
                need(r, Permission.COMMANDS)
                val n = str(a, "name").lowercase()
                if (!NAME.matches(n)) throw ExtensionException("Command names are 1-32 characters of a-z, 0-9, '_' or '-'")
                val owner = commandOwners[n]
                if (owner == null && CommandRegistry.find(n) != null) throw ExtensionException("/$n is a built-in command")
                if (owner != null && owner != r.ext.id) throw ExtensionException("/$n is already registered by $owner")
                r.commands += n; commandOwners[n] = r.ext.id
                val id = r.ext.id
                CommandRegistry.register(CommandDef(n, help = "[${r.ext.manifest.name}] " + str(a, "help")) { ctx ->
                    val rr = running[id] ?: throw CommandError("The extension $id is not running")
                    runCommand(rr, n, ctx.named, ctx.unnamed, ctx.pipe)
                })
                JsonNull
            }
            "registerMacro" -> {
                need(r, Permission.MACROS)
                val n = str(a, "name").lowercase()
                if (!NAME.matches(n)) throw ExtensionException("Macro names are 1-32 characters of a-z, 0-9, '_' or '-'")
                val owner = macroOwners[n]
                if (owner == null && MacroRegistry.find(n) != null) throw ExtensionException("{{$n}} is a built-in macro")
                if (owner != null && owner != r.ext.id) throw ExtensionException("{{$n}} is already registered by $owner")
                r.macros += n; macroOwners[n] = r.ext.id
                JsonNull
            }
            "runCommand" -> { need(r, Permission.COMMANDS); blocking(r, "runCommand"); JsonPrimitive(runBlocking { e().runCommand(str(a, "text")) }) }
            "setPrompt" -> {
                need(r, Permission.PROMPT)
                val text = str(a, "text"); val o = a.jsonObject["opts"] as? JsonObject
                fun int(k: String, d: Int) = (o?.get(k) as? JsonPrimitive)?.intOrNull ?: d
                r.prompt = if (text.isBlank()) null else ExtensionPrompt("ext:${r.ext.id}", text.take(MAX_PROMPT),
                    int("position", ExtensionPrompt.IN_CHAT).coerceIn(0, 2), int("depth", 4).coerceIn(0, 100), int("role", 0).coerceIn(0, 2))
                JsonNull
            }
            "generateQuiet" -> { need(r, Permission.GENERATE); blocking(r, "generateQuiet"); JsonPrimitive(runBlocking { e().generateQuiet(str(a, "prompt"), (a.jsonObject["useHistory"] as? JsonPrimitive)?.booleanOrNull == true) }) }
            "sendMessage" -> { need(r, Permission.CHAT); blocking(r, "sendMessage"); runBlocking { e().sendMessage(str(a, "text")) }; JsonNull }
            "addMessage" -> { need(r, Permission.CHAT); blocking(r, "addMessage"); runBlocking { e().addMessage(str(a, "name"), str(a, "text")) }; JsonNull }
            "echo" -> { runBlocking { e().echo(str(a, "text")) }; JsonNull }
            "context" -> e().context().let { c ->
                buildJsonObject { put("character", c.character); put("user", c.user); put("chatName", c.chatName); put("messageCount", c.messageCount); put("groupChat", c.groupChat) } }
            "messages" -> {
                val n = ((a.jsonObject["n"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(0, 200)
                JsonArray(e().messages(n).map { buildJsonObject { put("name", it.name); put("text", it.text); put("isUser", it.isUser); put("isSystem", it.isSystem) } })
            }
            "fetch" -> {
                need(r, Permission.NETWORK); blocking(r, "fetch")
                val o = a.jsonObject["opts"] as? JsonObject
                val headers = (o?.get("headers") as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.contentOrNull.orEmpty() }.orEmpty()
                val reply = http.fetch(str(a, "url"), (o?.get("method") as? JsonPrimitive)?.contentOrNull ?: "GET", headers, (o?.get("body") as? JsonPrimitive)?.contentOrNull)
                buildJsonObject { put("status", reply.status); put("body", reply.body) }
            }
            "storageGet" -> r.storage[str(a, "key")]?.let { JsonPrimitive(it) } ?: JsonNull
            "storageSet" -> { r.storage[str(a, "key")] = str(a, "value"); repo.writeStorage(r.ext.id, r.storage); JsonNull }
            "storageRemove" -> { r.storage.remove(str(a, "key")); repo.writeStorage(r.ext.id, r.storage); JsonNull }
            "storageKeys" -> JsonArray(r.storage.keys.sorted().map { JsonPrimitive(it) })
            else -> throw ExtensionException("Unknown host call $name")
        }
    }

    companion object {
        const val MAX_LOG = 100
        const val MAX_PROMPT = 8000
        private val NAME = Regex("[a-z][a-z0-9_-]{0,31}")
        @Volatile private var current: ExtensionHost? = null

        /** One resolver for the process: extension macros are found after built-ins, so they can never replace one. */
        private val resolverOnce by lazy {
            MacroRegistry.registerDynamic { name ->
                val h = current ?: return@registerDynamic null
                val id = h.macroOwners[name.lowercase()] ?: return@registerDynamic null
                MacroDef(listOf(name)) { call -> h.runMacro(id, name.lowercase(), call.args) }
            }
        }
    }
}
