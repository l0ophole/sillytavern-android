package app.sillytavern.android.command

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.macro.VariableStore

/** What commands need from the app. Chat-related members get defaults so simple hosts (tests) stay small. */
/** A group member as commands see them (group order); [avatar] is the `<file>.png` id. */
data class GroupMemberInfo(val name: String, val avatar: String, val enabled: Boolean)

interface CommandHost {
    val variables: VariableStore
    fun macroContext(): MacroContext
    suspend fun echo(text: String, severity: String = "info")
    /** Runs a stored script (Quick Reply `set.label`); null when no such script exists. */
    suspend fun runStoredScript(name: String, executor: CommandExecutor): String? = null
    val quickReplies: app.sillytavern.android.quickreply.QuickReplyAccess? get() = null

    // --- group chats; groupMembers() is null outside a group chat ---
    suspend fun groupMembers(): List<GroupMemberInfo>? = null
    suspend fun groupSetEnabled(avatar: String, enabled: Boolean): Unit = unavailable()
    suspend fun groupAdd(characterName: String): Boolean = false
    suspend fun groupRemove(avatar: String): Unit = unavailable()
    suspend fun groupMove(avatar: String, delta: Int): Unit = unavailable()
    suspend fun groupSpeak(avatar: String): Unit = unavailable()

    // --- chat access (implemented by ChatController) ---
    suspend fun sendUser(text: String): Unit = unavailable()
    suspend fun addSystemMessage(text: String): Unit = unavailable()
    suspend fun addMessageAs(name: String, text: String): Unit = unavailable()
    suspend fun generateReply(): Unit = unavailable()
    suspend fun continueLast(): Unit = unavailable()
    suspend fun regenerate(): Unit = unavailable()
    suspend fun swipeBy(delta: Int): Unit = unavailable()
    suspend fun impersonate(): String = unavailable()
    suspend fun deleteMessages(range: IntRange): Unit = unavailable()
    suspend fun hideMessages(range: IntRange, hidden: Boolean): Unit = unavailable()
    suspend fun setInput(text: String): Unit = unavailable()
    fun messageCount(): Int = 0
    fun chatText(range: IntRange?, names: Boolean): String = unavailable()
    suspend fun generateQuiet(prompt: String, useHistory: Boolean): String = unavailable()
    suspend fun newChat(): Unit = unavailable()
    suspend fun renameChat(name: String): Unit = unavailable()
    fun chatName(): String = unavailable()
    suspend fun setChatBook(name: String?): Unit = unavailable()
    suspend fun switchPersona(name: String): Boolean = unavailable()
    suspend fun summarize(text: String?): String = unavailable()
    suspend fun translate(text: String, to: String?): String = unavailable()
    suspend fun webSearch(query: String): String = unavailable()
    /** Captions the image of a message (default: the newest with an image) and returns the caption. */
    /** Reads [text] aloud; [voice] is a character name from the voice map or a provider voice. */
    suspend fun speak(text: String, voice: String?): Unit = unavailable()
    /** Generates an image (see ChatController.generateImage); returns its stored path. */
    suspend fun drawImage(subject: String, quiet: Boolean, negative: String?): String = unavailable()
    suspend fun caption(mesId: Int?, prompt: String?): String = unavailable()
    /** Shows the sprite for [label] (false when the speaker has no such label). */
    suspend fun setExpression(label: String): Boolean = unavailable()
    fun expressionLabels(): List<String> = emptyList()
    suspend fun classifyExpression(text: String): String = unavailable()
    fun currentSummary(): String = ""
    suspend fun createBranch(mesId: Int): String? = unavailable()
    suspend fun createCheckpoint(mesId: Int, name: String?): String? = unavailable()
    suspend fun openCheckpoint(mesId: Int): Boolean = unavailable()
    suspend fun backToMain(): Boolean = unavailable()
    fun mainChatName(): String? = null
    fun checkpointOf(mesId: Int): String? = null
    fun checkpoints(): List<Pair<Int, String>> = emptyList()
}

private fun unavailable(): Nothing = throw CommandError("This command needs an open chat")

data class Limits(val maxLoopIterations: Int = 100_000, val maxCallDepth: Int = 32)
data class CommandResult(val pipe: String, val error: String? = null, val aborted: Boolean = false)

/** A scoped variable: either text or a closure. */
class ScopeValue(val text: String?, val closure: CommandScript?)

class CommandScope(val parent: CommandScope?) {
    private val vars = HashMap<String, ScopeValue>()
    fun declare(name: String, v: ScopeValue) { vars[name] = v }
    fun get(name: String): ScopeValue? = vars[name] ?: parent?.get(name)
    /** Assigns to the nearest scope declaring [name]; false when undeclared. */
    fun assign(name: String, v: ScopeValue): Boolean {
        if (vars.containsKey(name)) { vars[name] = v; return true }
        return parent?.assign(name, v) ?: false
    }
}

class CommandContext(
    val host: CommandHost,
    val scope: CommandScope,
    val executor: CommandExecutor,
    val named: Map<String, String>,
    val namedClosures: Map<String, CommandScript>,
    val unnamed: String,
    val closures: List<CommandScript>,
    val pipe: String,
) {
    /** Expands macros/scope variables in [text] now (for raw-argument commands such as /while). */
    fun expand(text: String): String = executor.expandText(text, scope, pipe)
}

class CommandDef(
    val name: String,
    val aliases: List<String> = emptyList(),
    val help: String = "",
    /** Unnamed text is passed through unexpanded (flow commands expand the parts they need). */
    val rawUnnamed: Boolean = false,
    /** Named args passed through unexpanded. */
    val rawNamed: Set<String> = emptySet(),
    /** When no unnamed argument is given, use the previous pipe value. */
    val usesPipe: Boolean = true,
    val run: suspend (CommandContext) -> String,
)

object CommandRegistry {
    private val defs = HashMap<String, CommandDef>()
    private val ordered = ArrayList<CommandDef>()
    @Volatile private var loaded = false

    @Synchronized fun register(def: CommandDef) {
        ordered.removeAll { it.name == def.name }
        ordered.add(def)
        (listOf(def.name) + def.aliases).forEach { defs[it.lowercase()] = def }
    }
    fun find(name: String): CommandDef? { ensureLoaded(); return defs[name.lowercase()] }
    fun all(): List<CommandDef> { ensureLoaded(); return ordered.sortedBy { it.name } }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) { if (loaded) return; loaded = true; CommandLibrary.registerAll() }
    }
}

class CommandError(message: String) : Exception(message)
internal class ReturnSignal(val value: String) : RuntimeException(null, null, false, false)
internal class BreakSignal : RuntimeException(null, null, false, false)
internal class AbortSignal : RuntimeException(null, null, false, false)

class CommandExecutor(val host: CommandHost, val limits: Limits = Limits()) {
    private var depth = 0

    suspend fun runText(text: String): CommandResult {
        val script = try { CommandParser.parse(text) } catch (e: CommandParseError) { return CommandResult("", e.message) }
        return run(script)
    }

    suspend fun run(script: CommandScript, scope: CommandScope = CommandScope(null), pipe: String = ""): CommandResult = try {
        CommandResult(runScript(script, scope, pipe))
    } catch (e: ReturnSignal) { CommandResult(e.value) }
    catch (e: BreakSignal) { CommandResult("") }
    catch (e: AbortSignal) { CommandResult("", aborted = true) }
    catch (e: CommandError) { CommandResult("", e.message) }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { CommandResult("", e.message ?: e.javaClass.simpleName) }

    /** Runs a closure in a child scope; `/return` ends it with a value. */
    suspend fun callClosure(script: CommandScript, parent: CommandScope, pipe: String): String {
        if (depth >= limits.maxCallDepth) throw CommandError("Call depth limit (${limits.maxCallDepth}) exceeded")
        depth++
        try {
            return try { runScript(script, CommandScope(parent), pipe) } catch (e: ReturnSignal) { e.value }
        } finally { depth-- }
    }

    /** Runs raw command text (as given to `/if` without braces). */
    suspend fun callText(text: String, scope: CommandScope, pipe: String): String {
        val script = try { CommandParser.parse(text) } catch (e: CommandParseError) { throw CommandError(e.message ?: "parse error") }
        return callClosure(script, scope, pipe)
    }

    internal suspend fun runScript(script: CommandScript, scope: CommandScope, initialPipe: String): String {
        var pipe = initialPipe
        for (call in script.pipeline) pipe = runCall(call, scope, pipe)
        return pipe
    }

    private suspend fun runCall(call: CommandCall, scope: CommandScope, pipe: String): String {
        val def = CommandRegistry.find(call.name) ?: throw CommandError("Unknown command: /${call.name}")
        val named = LinkedHashMap<String, String>()
        val namedClosures = LinkedHashMap<String, CommandScript>()
        for ((k, v) in call.named) when (v) {
            is Arg.Closure -> namedClosures[k] = v.body
            is Arg.Literal -> named[k] = if (k in def.rawNamed) v.s else expandText(v.s, scope, pipe)
        }
        val closures = call.unnamed.filterIsInstance<Arg.Closure>().map { it.body }
        val rawText = call.unnamed.filterIsInstance<Arg.Literal>().joinToString("") { it.s }.trim()
        var text = if (def.rawUnnamed) rawText else expandText(rawText, scope, pipe)
        if (text.isEmpty() && closures.isEmpty() && def.usesPipe) text = pipe
        val ctx = CommandContext(host, scope, this, named, namedClosures, text, closures, pipe)
        return def.run(ctx)
    }

    private val scopeRe = Regex("""\{\{\s*(pipe|timesIndex|var::([^{}]*?)|globalvar::([^{}]*?))\s*}}""", RegexOption.IGNORE_CASE)

    fun expandText(text: String, scope: CommandScope, pipe: String): String {
        if (!text.contains("{{")) return text
        val pre = scopeRe.replace(text) { m ->
            val whole = m.groupValues[1]
            when {
                whole.equals("pipe", true) -> pipe
                whole.equals("timesIndex", true) -> scope.get("timesIndex")?.text ?: m.value
                m.groupValues[2].isNotEmpty() -> m.groupValues[2].trim().let { n -> scope.get(n)?.text ?: host.variables.get(n, false) }
                else -> host.variables.get(m.groupValues[3].trim(), true)
            }
        }
        return Macros.expand(pre, host.macroContext())
    }
}
