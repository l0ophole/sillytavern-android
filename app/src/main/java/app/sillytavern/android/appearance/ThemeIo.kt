package app.sillytavern.android.appearance

import kotlinx.serialization.json.*

/** Reads and writes SillyTavern theme files (the `themes` folder). Unsupported keys are ignored on import. */
object ThemeIo {
    private val rgba = Regex("^rgba?\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*(?:,\\s*([0-9]*\\.?[0-9]+)\\s*)?\\)$", RegexOption.IGNORE_CASE)

    /** `rgba(r, g, b, a)`, `rgb(r, g, b)`, `#rgb`, `#rrggbb` or `#rrggbbaa` to ARGB, or null. */
    fun parseColor(text: String?): Int? {
        val t = text?.trim().orEmpty()
        rgba.find(t)?.let { m ->
            val (r, g, b) = m.groupValues.subList(1, 4).map { it.toInt() }
            if (r > 255 || g > 255 || b > 255) return null
            val a = (m.groupValues[4].ifEmpty { "1" }.toDouble().coerceIn(0.0, 1.0) * 255).let { Math.round(it).toInt() }
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        if (t.startsWith("#") && t.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val h = t.drop(1)
            return when (h.length) {
                3 -> h.map { "$it$it" }.joinToString("").let { 0xFF000000.toInt() or it.toInt(16) }
                6 -> 0xFF000000.toInt() or h.toInt(16)
                8 -> (h.substring(6).toInt(16) shl 24) or h.substring(0, 6).toInt(16)
                else -> null
            }
        }
        return null
    }

    fun formatColor(argb: Int): String {
        val a = (argb ushr 24) / 255.0
        val alpha = if (a >= 0.9995) "1" else "%.2f".format(java.util.Locale.ROOT, a).trimEnd('0').trimEnd('.')
        return "rgba(${(argb shr 16) and 255}, ${(argb shr 8) and 255}, ${argb and 255}, $alpha)"
    }

    /** Perceived brightness 0..1 of an ARGB color (alpha ignored). */
    fun luminance(argb: Int): Double =
        (0.299 * ((argb shr 16) and 255) + 0.587 * ((argb shr 8) and 255) + 0.114 * (argb and 255)) / 255.0

    /** Name of a theme file, or null when [text] is not a JSON object. */
    fun nameOf(text: String): String? = runCatching { (Json.parseToJsonElement(text) as JsonObject)["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()

    /**
     * Applies a theme file onto [base]. Dark/light mode follows the brightness of `blur_tint_color`.
     * Throws [IllegalArgumentException] when the text is not a theme object.
     */
    fun import(text: String, base: AppearanceSettings = AppearanceSettings()): AppearanceSettings {
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: throw IllegalArgumentException("Not a theme file")
        fun color(k: String) = parseColor((o[k] as? JsonPrimitive)?.contentOrNull)
        val surface = color("blur_tint_color")
        val known = listOf("main_text_color", "blur_tint_color", "chat_tint_color", "user_mes_blur_tint_color", "bot_mes_blur_tint_color")
        if (known.none { o.containsKey(it) }) throw IllegalArgumentException("Not a theme file")
        return base.copy(
            themeName = (o["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            themeMode = surface?.let { if (luminance(it) < 0.5) ThemeMode.DARK else ThemeMode.LIGHT } ?: base.themeMode,
            mainText = color("main_text_color"), italicsText = color("italics_text_color"),
            underlineText = color("underline_text_color"), quoteText = color("quote_text_color"),
            surface = surface, chatBackground = color("chat_tint_color"),
            userBubble = color("user_mes_blur_tint_color"), botBubble = color("bot_mes_blur_tint_color"),
            fontScale = (o["font_scale"] as? JsonPrimitive)?.floatOrNull?.coerceIn(0.5f, 2f) ?: base.fontScale,
            avatarStyle = (o["avatar_style"] as? JsonPrimitive)?.intOrNull?.let { AvatarStyle.entries.getOrNull(it) } ?: base.avatarStyle,
            messageStyle = (o["chat_display"] as? JsonPrimitive)?.intOrNull?.let { MessageStyle.entries.getOrNull(it) } ?: base.messageStyle,
            chatWidth = (o["chat_width"] as? JsonPrimitive)?.intOrNull?.coerceIn(25, 100) ?: base.chatWidth,
            showTimestamps = (o["timestamps_enabled"] as? JsonPrimitive)?.booleanOrNull ?: base.showTimestamps,
            showMessageNumbers = (o["mesIDDisplay_enabled"] as? JsonPrimitive)?.booleanOrNull ?: base.showMessageNumbers,
            hideAvatars = (o["hideChatAvatars_enabled"] as? JsonPrimitive)?.booleanOrNull ?: base.hideAvatars,
        )
    }

    /** ST theme JSON for [a]; keys the app does not model get SillyTavern's default values so the file loads in ST. */
    fun export(a: AppearanceSettings, name: String): String {
        val dark = a.themeMode == ThemeMode.DARK || a.themeMode == ThemeMode.AMOLED
        fun c(v: Int?, fallback: String) = JsonPrimitive(v?.let(::formatColor) ?: fallback)
        val o = buildJsonObject {
            put("name", name)
            put("blur_strength", 10)
            put("main_text_color", c(a.mainText, if (dark) "rgba(220, 220, 210, 1)" else "rgba(30, 30, 30, 1)"))
            put("italics_text_color", c(a.italicsText, "rgba(145, 145, 145, 1)"))
            put("underline_text_color", c(a.underlineText, "rgba(188, 231, 207, 1)"))
            put("quote_text_color", c(a.quoteText, "rgba(225, 138, 36, 1)"))
            put("blur_tint_color", c(a.surface, if (dark) "rgba(23, 23, 23, 1)" else "rgba(255, 255, 255, 1)"))
            put("chat_tint_color", c(a.chatBackground, if (dark) "rgba(23, 23, 23, 1)" else "rgba(255, 255, 255, 1)"))
            put("user_mes_blur_tint_color", c(a.userBubble, if (dark) "rgba(30, 30, 30, 0.9)" else "rgba(230, 230, 230, 0.9)"))
            put("bot_mes_blur_tint_color", c(a.botBubble, if (dark) "rgba(30, 30, 30, 0.9)" else "rgba(240, 240, 240, 0.9)"))
            put("shadow_color", "rgba(0, 0, 0, 1)")
            put("shadow_width", 2)
            put("border_color", "rgba(0, 0, 0, 1)")
            put("font_scale", a.fontScale)
            put("fast_ui_mode", true)
            put("avatar_style", a.avatarStyle.ordinal)
            put("chat_display", a.messageStyle.ordinal)
            put("chat_width", a.chatWidth)
            put("timestamps_enabled", a.showTimestamps)
            put("mesIDDisplay_enabled", a.showMessageNumbers)
            put("hideChatAvatars_enabled", a.hideAvatars)
            put("custom_css", "")
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o)
    }
}
