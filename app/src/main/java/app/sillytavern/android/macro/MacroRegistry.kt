package app.sillytavern.android.macro

import app.sillytavern.android.chat.MacroContext
import java.util.concurrent.ConcurrentHashMap

class MacroCall(val ctx: MacroContext, val args: List<String>, val scopeContent: String?, val name: String = "") {
    fun arg(i: Int) = args.getOrElse(i) { "" }
}

/**
 * A macro definition. [eval] returns null to leave the macro text untouched.
 * [scoped] macros wrap content up to a matching `{{/name}}`; without a closer they stay literal.
 * [spaceArg] lets `{{name arg}}` pass "arg" as first argument (used by scoped macros like `if`).
 */
class MacroDef(
    val names: List<String>,
    val minArgs: Int = 0,
    val maxArgs: Int = Int.MAX_VALUE,
    val scoped: Boolean = false,
    val eval: (MacroCall) -> String?,
)

object MacroRegistry {
    private val defs = ConcurrentHashMap<String, MacroDef>()
    @Volatile private var loaded = false

    fun register(def: MacroDef) { def.names.forEach { defs[it.lowercase()] = def } }

    private val dynamic = java.util.concurrent.CopyOnWriteArrayList<(String) -> MacroDef?>()
    fun registerDynamic(f: (String) -> MacroDef?) { dynamic.add(f) }

    fun find(name: String): MacroDef? {
        ensureLoaded()
        return defs[name.lowercase()] ?: dynamic.firstNotNullOfOrNull { it(name) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            MacroDefinitions.registerAll()
        }
    }
}
