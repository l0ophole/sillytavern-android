package app.sillytavern.android.worldinfo

import app.sillytavern.android.prompt.TokenCounter
import kotlin.random.Random

data class ScanEntry(val book: String, val entry: WorldEntry) {
    val id get() = "$book.${entry.uid}"
}

data class ScanResult(val activated: List<ScanEntry>, val timed: TimedState)

/**
 * Port of SillyTavern's checkWorldInfo scan loop. [expand] is applied to keys and content (macro substitution);
 * [entries] must already be in priority order.
 */
class WorldInfoScanner(
    private val settings: WorldInfoSettings,
    private val counter: TokenCounter,
    private val random: Random = Random.Default,
    private val expand: (String) -> String = { it },
) {
    private class Item(val src: ScanEntry, val content: String, val decorators: Set<String>) {
        val e get() = src.entry
        val id get() = src.id
    }

    private fun item(s: ScanEntry): Item {
        val lines = s.entry.content.split('\n')
        val deco = mutableSetOf<String>()
        var i = 0
        while (i < lines.size && lines[i].trim() in KNOWN_DECORATORS) { deco += lines[i].trim(); i++ }
        val body = if (i == 0) s.entry.content else lines.drop(i).joinToString("\n")
        return Item(s, expand(body), deco)
    }

    fun scan(
        entries: List<ScanEntry>, chatNewestFirst: List<String>, maxContext: Int, global: GlobalScanData,
        timed: TimedState = TimedState(), dryRun: Boolean = false,
    ): ScanResult {
        val items = entries.map(::item)
        if (items.isEmpty()) return ScanResult(emptyList(), timed)
        val order = items.withIndex().associate { it.value to it.index }
        val buffer = ScanBuffer(chatNewestFirst, global, settings)
        val chatLen = chatNewestFirst.size
        var budget = Math.round(settings.budget * maxContext / 100.0).toInt().coerceAtLeast(1)
        if (settings.budgetCap > 0 && budget > settings.budgetCap) budget = settings.budgetCap

        fun active(k: TimedKind, i: Item) = timed.isActive(k, i.src, chatLen)
        val levels = items.map { it.e.delayUntilRecursion }.filter { it > 0 }.distinct().sorted().toMutableList()
        var curLevel = if (levels.isEmpty()) 0 else levels.removeAt(0)
        var state: ScanState? = ScanState.INITIAL
        var overflowed = false
        var count = 0
        var allText = ""
        val activated = LinkedHashMap<String, Item>()
        val failed = HashSet<String>()

        fun hit(text: String, key: String, i: Item): Boolean {
            val k = expand(key).trim()
            return k.isNotEmpty() && KeyMatcher.matches(text, k, i.e, settings)
        }

        fun evaluate(i: Item, st: ScanState): Boolean {
            val e = i.e
            if (i.id in failed || i.id in activated) return false
            if (e.disable) return false
            if (e.triggers.isNotEmpty() && global.trigger !in e.triggers) return false
            val sticky = active(TimedKind.STICKY, i)
            if (active(TimedKind.DELAY, i)) return false
            if (active(TimedKind.COOLDOWN, i) && !sticky) return false
            if (st != ScanState.RECURSION && e.delayUntilRecursion > 0 && !sticky) return false
            if (st == ScanState.RECURSION && e.delayUntilRecursion > curLevel && !sticky) return false
            if (st == ScanState.RECURSION && settings.recursive && e.excludeRecursion && !sticky) return false
            if ("@@activate" in i.decorators) return true
            if ("@@dont_activate" in i.decorators) return false
            if (e.constant || sticky) return true
            if (e.key.isEmpty()) return false
            val text = buffer.text(e, st)
            if (e.key.none { hit(text, it, i) }) return false
            if (!(e.selective && e.keysecondary.isNotEmpty())) return true
            var any = false
            var all = true
            for (k in e.keysecondary) {
                val h = hit(text, k, i)
                if (h) any = true else all = false
                if (e.selectiveLogic == WiLogic.AND_ANY && h) return true
                if (e.selectiveLogic == WiLogic.NOT_ALL && !h) return true
            }
            return (e.selectiveLogic == WiLogic.NOT_ANY && !any) || (e.selectiveLogic == WiLogic.AND_ALL && all)
        }

        fun score(i: Item, st: ScanState): Int {
            val e = i.e
            if (e.key.isEmpty()) return 0
            val text = buffer.text(e, st)
            val p = e.key.count { hit(text, it, i) }
            val s = e.keysecondary.count { hit(text, it, i) }
            if (e.keysecondary.isNotEmpty()) when (e.selectiveLogic) {
                WiLogic.AND_ANY -> return p + s
                WiLogic.AND_ALL -> return if (s == e.keysecondary.size) p + s else p
            }
            return p
        }

        fun filterGroups(list: MutableList<Item>, st: ScanState) {
            val grouped = LinkedHashMap<String, MutableList<Item>>()
            for (i in list) if (i.e.group.isNotBlank())
                i.e.group.split(Regex(",\\s*")).filter { it.isNotEmpty() }.forEach { grouped.getOrPut(it) { mutableListOf() } += i }
            if (grouped.isEmpty()) return
            fun removeAllBut(g: List<Item>, chosen: Item?) { for (i in g) if (i !== chosen) list.remove(i) }
            val hasSticky = HashMap<String, Boolean>()
            for ((key, g) in grouped) {
                val sticky = g.filter { active(TimedKind.STICKY, it) }
                hasSticky[key] = sticky.isNotEmpty()
                if (sticky.isNotEmpty()) g.filter { it !in sticky }.forEach { list.remove(it) }
                g.filter { active(TimedKind.COOLDOWN, it) || active(TimedKind.DELAY, it) }.forEach { list.remove(it) }
            }
            for ((key, g) in grouped) {
                if (!settings.useGroupScoring && g.none { it.e.useGroupScoring == true }) continue
                if (hasSticky[key] == true) continue
                val scores = g.map { score(it, st) }.toMutableList()
                val max = scores.maxOrNull() ?: continue
                var idx = 0
                while (idx < g.size) {
                    val scored = g[idx].e.useGroupScoring ?: settings.useGroupScoring
                    if (scored && scores[idx] < max) { list.remove(g[idx]); g.removeAt(idx); scores.removeAt(idx) } else idx++
                }
            }
            for ((key, g) in grouped) {
                if (hasSticky[key] == true) continue
                if (activated.values.any { it.e.group == key }) { removeAllBut(g, null); continue }
                if (g.size <= 1) continue
                val prio = g.filter { it.e.groupOverride }.sortedByDescending { it.e.order }
                if (prio.isNotEmpty()) { removeAllBut(g, prio[0]); continue }
                val total = g.sumOf { it.e.groupWeight }
                val roll = random.nextDouble() * total
                var cum = 0
                var winner: Item? = null
                for (i in g) { cum += i.e.groupWeight; if (roll <= cum) { winner = i; break } }
                if (winner == null) continue
                removeAllBut(g, winner)
            }
        }

        while (state != null) {
            if (settings.maxRecursionSteps > 0 && settings.maxRecursionSteps <= count) break
            count++
            val st = state
            val now = LinkedHashSet<Item>()
            for (i in items) if (evaluate(i, st)) now += i
            val list = now.sortedWith(
                compareByDescending<Item> { if (active(TimedKind.STICKY, it)) 1 else 0 }.thenBy { order[it] ?: 0 },
            ).toMutableList()
            filterGroups(list, st)

            val textTokens = counter.count(allText)
            var newContent = ""
            var ignores = list.count { it.e.ignoreBudget }
            for (i in list.toList()) {
                ignores -= if (i.e.ignoreBudget) 1 else 0
                if (overflowed && !i.e.ignoreBudget) { if (ignores > 0) continue else break }
                val passes = !i.e.useProbability || i.e.probability == 100 || active(TimedKind.STICKY, i) ||
                    random.nextDouble() * 100 <= i.e.probability
                if (!passes) { failed += i.id; continue }
                newContent += i.content + "\n"
                if (!i.e.ignoreBudget && textTokens + counter.count(newContent) >= budget) { overflowed = true; continue }
                activated[i.id] = i
            }

            val successful = list.filter { it.id !in failed }
            val forRecursion = successful.filter { !it.e.preventRecursion }
            var next: ScanState? = null
            if (settings.recursive && !overflowed && forRecursion.isNotEmpty()) next = ScanState.RECURSION
            if (settings.recursive && !overflowed && st == ScanState.MIN_ACTIVATIONS && buffer.hasRecurse()) next = ScanState.RECURSION
            if (next == null && !overflowed && settings.minActivations > 0 && activated.size < settings.minActivations) {
                val over = (settings.minActivationsDepthMax > 0 && buffer.depth() > settings.minActivationsDepthMax) || buffer.depth() > chatLen
                if (!over) { next = ScanState.MIN_ACTIVATIONS; buffer.advance() }
            }
            if (next == null && levels.isNotEmpty()) { next = ScanState.RECURSION; curLevel = levels.removeAt(0) }
            state = next
            if (next != null) {
                val text = forRecursion.joinToString("\n") { it.content }
                if (text.isNotEmpty()) { buffer.addRecurse(text); allText = text + "\n" + allText }
            }
        }
        val result = activated.values.map { ScanEntry(it.src.book, it.e.copy(content = it.content)) }
        return ScanResult(result, timed)
    }

    private companion object { val KNOWN_DECORATORS = setOf("@@activate", "@@dont_activate") }
}
