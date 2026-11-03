package app.sillytavern.android.appearance

import kotlinx.serialization.json.*

/**
 * UI translation in SillyTavern's style: locale files map the English text to the translated text,
 * and anything without an entry shows the English text. Files live in `assets/locales/<code>.json`.
 */
class Translator(private val table: Map<String, String> = emptyMap()) {
    operator fun invoke(text: String): String = table[text] ?: text
    val isEmpty get() = table.isEmpty()

    companion object {
        val NONE = Translator()

        /** Languages with a bundled file, as code to native name. "en" needs no file. */
        val languages: List<Pair<String, String>> = listOf("en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch")

        /** Parses a locale file; entries that are not string pairs are skipped. A broken file gives an empty translator. */
        fun fromJson(text: String): Translator {
            val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return NONE
            return Translator(o.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }?.let { k to it } }.toMap())
        }
    }
}

/** Formats a chat line's `send_date` for display: ISO instants in the device's time zone, SillyTavern's own text format as it is. */
object SendDate {
    fun format(raw: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), locale: java.util.Locale = java.util.Locale.getDefault()): String {
        if (raw.isBlank()) return ""
        return runCatching {
            java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT).withLocale(locale)
                .format(java.time.Instant.parse(raw).atZone(zone))
        }.getOrDefault(raw)
    }
}
