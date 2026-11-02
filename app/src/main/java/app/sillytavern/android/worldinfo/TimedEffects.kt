package app.sillytavern.android.worldinfo

import kotlinx.serialization.json.*

enum class TimedKind { STICKY, COOLDOWN, DELAY }

/**
 * Per-chat sticky/cooldown state (SillyTavern's `chat_metadata.timedWorldInfo`), keyed by "book.uid".
 * [prepare] prunes expired effects for the current chat length; [record] adds effects for newly activated entries.
 */
class TimedState(
    val sticky: Map<String, Effect> = emptyMap(),
    val cooldown: Map<String, Effect> = emptyMap(),
) {
    data class Effect(val start: Int, val end: Int, val protected: Boolean = false)

    private var activeSticky: Set<String> = emptySet()
    private var activeCooldown: Set<String> = emptySet()
    private var delayed: Set<String> = emptySet()

    fun isActive(kind: TimedKind, entry: ScanEntry, chatLength: Int): Boolean = when (kind) {
        TimedKind.STICKY -> entry.id in activeSticky
        TimedKind.COOLDOWN -> entry.id in activeCooldown
        TimedKind.DELAY -> entry.id in delayed
    }

    private fun copyWith(sticky: Map<String, Effect>, cooldown: Map<String, Effect>) = TimedState(sticky, cooldown)

    fun prepare(entries: List<ScanEntry>, chatLength: Int, dryRun: Boolean): TimedState {
        val byId = entries.associateBy { it.id }
        val sticky = sticky.toMutableMap()
        val cooldown = cooldown.toMutableMap()
        val aSticky = mutableSetOf<String>()
        val aCool = mutableSetOf<String>()
        if (!dryRun) {
            fun process(map: MutableMap<String, Effect>, out: MutableSet<String>, value: (WorldEntry) -> Int?, ended: (String, WorldEntry) -> Unit) {
                for ((key, eff) in map.toMap()) {
                    val e = byId[key]?.entry
                    if (chatLength <= eff.start && !eff.protected) { map.remove(key); continue }
                    if (e == null) { if (chatLength >= eff.end) map.remove(key); continue }
                    if ((value(e) ?: 0) <= 0) { map.remove(key); continue }
                    if (chatLength >= eff.end) { map.remove(key); ended(key, e); continue }
                    out += key
                }
            }
            process(sticky, aSticky, { it.sticky }) { key, e ->
                val cd = e.cooldown ?: 0
                if (cd > 0) cooldown[key] = Effect(chatLength, chatLength + cd, true)
            }
            process(cooldown, aCool, { it.cooldown }) { _, _ -> }
        }
        val out = copyWith(sticky, cooldown)
        out.activeSticky = aSticky
        out.activeCooldown = aCool
        out.delayed = entries.filter { (it.entry.delay ?: 0) > 0 && chatLength < it.entry.delay!! }.map { it.id }.toSet()
        return out
    }

    fun record(activated: List<ScanEntry>, chatLength: Int, dryRun: Boolean = false): TimedState {
        if (dryRun) return this
        val sticky = sticky.toMutableMap()
        val cooldown = cooldown.toMutableMap()
        for (a in activated) {
            val s = a.entry.sticky ?: 0
            val c = a.entry.cooldown ?: 0
            if (s > 0 && a.id !in sticky) sticky[a.id] = Effect(chatLength, chatLength + s)
            // With sticky, the cooldown starts when the sticky window ends.
            if (s <= 0 && c > 0 && a.id !in cooldown) cooldown[a.id] = Effect(chatLength, chatLength + c)
        }
        return copyWith(sticky, cooldown)
    }

    fun toJson(): JsonObject {
        fun m(map: Map<String, Effect>) = buildJsonObject {
            map.forEach { (k, v) -> put(k, buildJsonObject { put("start", v.start); put("end", v.end); put("protected", v.protected) }) }
        }
        return buildJsonObject { put("sticky", m(sticky)); put("cooldown", m(cooldown)) }
    }

    companion object {
        fun fromJson(o: JsonObject?): TimedState {
            fun m(e: JsonElement?): Map<String, Effect> = (e as? JsonObject)?.mapNotNull { (k, v) ->
                val x = v as? JsonObject ?: return@mapNotNull null
                val s = (x["start"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                val en = (x["end"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
                k to Effect(s, en, (x["protected"] as? JsonPrimitive)?.booleanOrNull ?: false)
            }?.toMap().orEmpty()
            return TimedState(m(o?.get("sticky")), m(o?.get("cooldown")))
        }
    }
}
