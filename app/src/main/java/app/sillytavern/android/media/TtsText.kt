package app.sillytavern.android.media

/** Decides what part of a message is spoken (SillyTavern's TTS "narrate" options). */
object TtsText {
    private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val inlineCode = Regex("`[^`]*`")
    private val tag = Regex("<[^>]+>")
    private val action = Regex("\\*[^*]+\\*")
    private val quoted = Regex("\"[^\"]*\"|“[^”]*”|«[^»]*»")
    private val urlRx = Regex("https?://\\S+")

    fun prepare(raw: String, s: TtsSettings): String {
        var t = raw
        if (s.skipCodeBlocks) t = codeBlock.replace(t, " ")
        t = tag.replace(t, " ")
        if (!s.speakActions) t = action.replace(t, " ")
        t = when (s.narrate) {
            NarrateMode.ALL -> t
            NarrateMode.QUOTED -> quoted.findAll(t).joinToString(" ") { it.value.trim('"', '“', '”', '«', '»') }
            NarrateMode.NON_QUOTED -> quoted.replace(t, " ")
        }
        t = inlineCode.replace(t) { it.value.trim('`') }
        t = urlRx.replace(t, " ")
        t = t.replace(Regex("[*_~#>]+"), " ").replace(Regex("\\s+"), " ").trim()
        return if (t.any { it.isLetterOrDigit() }) t else ""
    }

    /** Voice for [speaker]: exact name, case-insensitive name, then the default voice. */
    fun voiceFor(speaker: String, s: TtsSettings): String =
        s.voiceMap[speaker] ?: s.voiceMap.entries.firstOrNull { it.key.equals(speaker, true) }?.value ?: s.defaultVoice

    /** Splits long text at sentence ends so each request stays within provider limits (OpenAI: 4096 chars). */
    fun chunks(text: String, max: Int = 3500): List<String> {
        if (text.length <= max) return listOf(text)
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (sentence in Regex("(?<=[.!?。])\\s+").split(text)) {
            if (cur.isNotEmpty() && cur.length + sentence.length + 1 > max) { out += cur.toString(); cur = StringBuilder() }
            if (sentence.length > max) { sentence.chunked(max).forEach { out += it } } else { if (cur.isNotEmpty()) cur.append(' '); cur.append(sentence) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
