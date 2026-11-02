package app.sillytavern.android.worldinfo

data class DepthInjection(val depth: Int, val role: Int, val content: String)

data class WorldInfoPrompt(
    val before: String = "",
    val after: String = "",
    val depth: List<DepthInjection> = emptyList(),
    val exampleTop: List<String> = emptyList(),
    val exampleBottom: List<String> = emptyList(),
    val noteTop: List<String> = emptyList(),
    val noteBottom: List<String> = emptyList(),
    val outlets: Map<String, String> = emptyMap(),
)

/** Sorts activated entries by insertion order and buckets them by position, as SillyTavern does. */
object WorldInfoAssembler {
    fun assemble(activated: List<ScanEntry>, expand: (String) -> String): WorldInfoPrompt {
        val before = ArrayDeque<String>(); val after = ArrayDeque<String>()
        val top = ArrayDeque<String>(); val bottom = ArrayDeque<String>()
        val noteTop = ArrayDeque<String>(); val noteBottom = ArrayDeque<String>()
        val depth = LinkedHashMap<Pair<Int, Int>, ArrayDeque<String>>()
        val outlets = LinkedHashMap<String, MutableList<String>>()
        // Highest order first; "unshift" each so the lowest order ends up first in the prompt.
        for (s in activated.sortedByDescending { it.entry.order }) {
            val e = s.entry
            val c = expand(e.content)
            if (c.isBlank()) continue
            when (e.position) {
                WiPosition.BEFORE -> before.addFirst(c)
                WiPosition.AFTER -> after.addFirst(c)
                WiPosition.EM_TOP -> top.addFirst(c)
                WiPosition.EM_BOTTOM -> bottom.addFirst(c)
                WiPosition.AN_TOP -> noteTop.addFirst(c)
                WiPosition.AN_BOTTOM -> noteBottom.addFirst(c)
                WiPosition.AT_DEPTH -> depth.getOrPut(e.depth to e.role) { ArrayDeque() }.addFirst(c)
                WiPosition.OUTLET -> if (e.outletName.isNotBlank()) outlets.getOrPut(e.outletName) { mutableListOf() } += c
            }
        }
        return WorldInfoPrompt(
            before.joinToString("\n"), after.joinToString("\n"),
            depth.map { (k, v) -> DepthInjection(k.first, k.second, v.joinToString("\n")) },
            top.toList(), bottom.toList(), noteTop.toList(), noteBottom.toList(),
            outlets.mapValues { it.value.joinToString("\n") },
        )
    }
}
