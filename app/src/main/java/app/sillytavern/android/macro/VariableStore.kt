package app.sillytavern.android.macro

import kotlinx.serialization.json.*

/**
 * Local (per chat, `chat_metadata.variables`) and global variables. Values are JSON primitives;
 * numeric-looking strings are stored as numbers, as SillyTavern does.
 */
class VariableStore(
    private val getLocal: () -> JsonObject,
    private val setLocal: (JsonObject) -> Unit,
    private val getGlobal: () -> Map<String, String>,
    private val setGlobal: (Map<String, String>) -> Unit,
) {
    private val json = Json

    fun get(name: String, global: Boolean, index: String? = null): String {
        val el = element(name, global) ?: return ""
        val target = if (index == null) el else when (el) {
            is JsonArray -> index.trim().toIntOrNull()?.let { el.getOrNull(it) }
            is JsonObject -> el[index.trim()]
            else -> null
        } ?: return ""
        return render(target)
    }

    fun has(name: String, global: Boolean) = element(name, global) != null

    fun set(name: String, value: String, global: Boolean, index: String? = null) {
        if (name.isBlank()) return
        if (index != null) {
            val cur = element(name, global)
            val idx = index.trim()
            val updated: JsonElement = when {
                cur is JsonArray && idx.toIntOrNull() != null && idx.toInt() in cur.indices ->
                    JsonArray(cur.toMutableList().also { it[idx.toInt()] = parse(value) })
                cur is JsonObject -> JsonObject(cur + (idx to parse(value)))
                else -> return
            }
            put(name, updated, global); return
        }
        put(name, parse(value), global)
    }

    fun delete(name: String, global: Boolean) {
        if (global) setGlobal(getGlobal() - name) else setLocal(JsonObject(getLocal() - name))
    }

    fun add(name: String, value: String, global: Boolean) {
        val cur = element(name, global)
        val v = parse(value)
        val next: JsonElement = when {
            cur == null -> v
            cur is JsonArray -> JsonArray(cur + v)
            cur is JsonPrimitive && !cur.isString && v is JsonPrimitive && !v.isString -> num(cur.double + v.double)
            else -> JsonPrimitive(render(cur) + value)
        }
        put(name, next, global)
    }

    fun inc(name: String, global: Boolean): String = step(name, global, 1.0)
    fun dec(name: String, global: Boolean): String = step(name, global, -1.0)

    private fun step(name: String, global: Boolean, d: Double): String {
        val cur = (element(name, global) as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val n = num(cur + d)
        put(name, n, global)
        return render(n)
    }

    private fun element(name: String, global: Boolean): JsonElement? =
        if (global) getGlobal()[name]?.let { parse(it) } else getLocal()[name]

    private fun put(name: String, v: JsonElement, global: Boolean) {
        if (global) setGlobal(getGlobal() + (name to render(v))) else setLocal(JsonObject(getLocal() + (name to v)))
    }

    private fun num(d: Double): JsonPrimitive =
        if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

    private fun parse(s: String): JsonElement {
        val t = s.trim()
        if (t.isNotEmpty() && Regex("-?\\d+(\\.\\d+)?").matches(t)) {
            t.toLongOrNull()?.let { return JsonPrimitive(it) }
            t.toDoubleOrNull()?.let { return JsonPrimitive(it) }
        }
        if ((t.startsWith("[") && t.endsWith("]")) || (t.startsWith("{") && t.endsWith("}")))
            runCatching { return json.parseToJsonElement(t) }
        return JsonPrimitive(s)
    }

    private fun render(e: JsonElement): String = when (e) {
        is JsonPrimitive -> if (e is JsonNull) "" else e.content
        else -> e.toString()
    }

    companion object {
        fun inMemory(): VariableStore {
            var local = JsonObject(emptyMap()); var global = emptyMap<String, String>()
            return VariableStore({ local }, { local = it }, { global }, { global = it })
        }
    }
}
