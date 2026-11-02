package app.sillytavern.android.worldinfo

import java.util.regex.Pattern

object KeyMatcher {
    private val cache = HashMap<String, Regex?>()
    private val regexForm = Regex("^/([\\s\\S]+?)/([gimsuy]*)$")

    /** Parses `/pattern/flags` like SillyTavern; null when not regex-shaped or invalid. */
    fun parseRegex(key: String): Regex? = synchronized(cache) {
        cache.getOrPut(key) {
            val m = regexForm.find(key) ?: return@getOrPut null
            val (pattern, flags) = m.destructured
            if (Regex("(^|[^\\\\])/").containsMatchIn(pattern)) return@getOrPut null
            val opts = mutableSetOf<RegexOption>()
            if ('i' in flags) opts += RegexOption.IGNORE_CASE
            if ('m' in flags) opts += RegexOption.MULTILINE
            if ('s' in flags) opts += RegexOption.DOT_MATCHES_ALL
            try { Regex(pattern.replace("\\/", "/"), opts) } catch (e: Exception) { null }
        }
    }

    fun matches(haystack: String, key: String, entry: WorldEntry, settings: WorldInfoSettings): Boolean {
        if (key.isBlank()) return false
        parseRegex(key)?.let { re -> return try { re.containsMatchIn(haystack) } catch (e: Throwable) { false } }
        val cs = entry.caseSensitive ?: settings.caseSensitive
        val h = if (cs) haystack else haystack.lowercase()
        val n = if (cs) key else key.lowercase()
        if (entry.matchWholeWords ?: settings.matchWholeWords) {
            if (n.split(Regex("\\s+")).size > 1) return h.contains(n)
            return Regex("(?:^|\\W)(" + Pattern.quote(n) + ")(?:$|\\W)").containsMatchIn(h)
        }
        return h.contains(n)
    }
}
