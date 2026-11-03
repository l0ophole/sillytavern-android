package app.sillytavern.android.memory

/** Splits text into chunks of at most [maxChars], on paragraph and sentence boundaries, repeating a short tail between chunks. */
object TextChunker {
    fun chunk(text: String, maxChars: Int, overlapChars: Int = 0): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isEmpty()) return emptyList()
        val max = maxChars.coerceAtLeast(50)
        if (clean.length <= max) return listOf(clean)
        val units = clean.split(Regex("\n{2,}")).flatMap { sentences(it.trim(), max) }.filter { it.isNotBlank() }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (u in units) {
            if (cur.isNotEmpty() && cur.length + 1 + u.length > max) {
                out += cur.toString().trim()
                val tail = tail(cur.toString(), overlapChars.coerceAtMost(max / 2))
                cur.clear(); cur.append(tail)
                if (cur.isNotEmpty() && cur.length + 1 + u.length > max) cur.clear()
            }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(u)
        }
        if (cur.isNotBlank()) out += cur.toString().trim()
        return out
    }

    /** Whole sentences from the end of [s] that fit in [limit] characters. */
    private fun tail(s: String, limit: Int): String {
        if (limit <= 0) return ""
        val parts = sentences(s, limit)
        val sb = ArrayDeque<String>(); var len = 0
        for (p in parts.reversed()) { if (len + p.length + 1 > limit) break; sb.addFirst(p); len += p.length + 1 }
        return sb.joinToString(" ")
    }

    private fun sentences(s: String, max: Int): List<String> {
        if (s.isEmpty()) return emptyList()
        val parts = s.split(Regex("(?<=[.!?…])\\s+|\\n")).filter { it.isNotBlank() }
        return parts.flatMap { p -> if (p.length <= max) listOf(p) else p.chunked(max) }
    }
}
