package app.sillytavern.android.macro

object VariableMacros {
    fun register() {
        for (global in listOf(false, true)) {
            val g = if (global) "global" else ""
            fun reg(vararg names: String, min: Int, max: Int, f: (VariableStore, MacroCall) -> String) =
                MacroRegistry.register(MacroDef(names.toList(), min, max) { c -> c.ctx.vars?.let { f(it, c) } })
            reg("set${g}var", min = 2, max = 3) { v, c ->
                if (c.args.size == 3) v.set(c.arg(0).trim(), c.arg(2), global, c.arg(1)) else v.set(c.arg(0).trim(), c.arg(1), global); ""
            }
            reg("add${g}var", min = 2, max = 2) { v, c -> v.add(c.arg(0).trim(), c.arg(1), global); "" }
            reg("inc${g}var", min = 1, max = 1) { v, c -> v.inc(c.arg(0).trim(), global) }
            reg("dec${g}var", min = 1, max = 1) { v, c -> v.dec(c.arg(0).trim(), global) }
            reg("get${g}var", min = 1, max = 2) { v, c -> v.get(c.arg(0).trim(), global, c.args.getOrNull(1)) }
            reg("has${g}var", if (global) "globalvarexists" else "varexists", min = 1, max = 1) { v, c -> v.has(c.arg(0).trim(), global).toString() }
            reg("delete${g}var", "flush${g}var", min = 1, max = 1) { v, c -> v.delete(c.arg(0).trim(), global); "" }
        }
    }
}
