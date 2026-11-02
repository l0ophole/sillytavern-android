package app.sillytavern.android.worldinfo

enum class ScanState { INITIAL, RECURSION, MIN_ACTIVATIONS }

data class GlobalScanData(
    val personaDescription: String = "",
    val characterDescription: String = "",
    val characterPersonality: String = "",
    val characterDepthPrompt: String = "",
    val scenario: String = "",
    val creatorNotes: String = "",
    val trigger: String = "normal",
)

/** Text a scan matches against: recent chat (newest first), opted-in card fields, and recursion text. */
class ScanBuffer(messagesNewestFirst: List<String>, private val global: GlobalScanData, private val settings: WorldInfoSettings) {
    private val chat = messagesNewestFirst.take(MAX_SCAN_DEPTH).map { it.trim() }
    private val recurse = mutableListOf<String>()
    private var skew = 0

    fun depth() = settings.depth + skew
    fun advance() { skew++ }
    fun chatLength() = chat.size
    fun addRecurse(text: String) { recurse += text }
    fun hasRecurse() = recurse.isNotEmpty()

    fun text(entry: WorldEntry, state: ScanState): String {
        val depth = (entry.scanDepth ?: depth()).coerceAtMost(MAX_SCAN_DEPTH)
        if (depth <= 0) return ""
        val sep = "\n\u0001"
        val sb = StringBuilder("\u0001").append(chat.take(depth).joinToString(sep))
        fun opt(on: Boolean, v: String) { if (on && v.isNotEmpty()) sb.append(sep).append(v) }
        opt(entry.matchPersonaDescription, global.personaDescription)
        opt(entry.matchCharacterDescription, global.characterDescription)
        opt(entry.matchCharacterPersonality, global.characterPersonality)
        opt(entry.matchCharacterDepthPrompt, global.characterDepthPrompt)
        opt(entry.matchScenario, global.scenario)
        opt(entry.matchCreatorNotes, global.creatorNotes)
        if (recurse.isNotEmpty() && state != ScanState.MIN_ACTIVATIONS) sb.append(sep).append(recurse.joinToString(sep))
        return sb.toString()
    }
}
