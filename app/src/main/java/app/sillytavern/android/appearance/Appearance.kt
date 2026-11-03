package app.sillytavern.android.appearance

import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** SillyTavern `avatar_style`: 0 round, 1 rectangular (portrait), 2 square, 3 rounded square. */
@Serializable enum class AvatarStyle { ROUND, RECTANGULAR, SQUARE, ROUNDED }

/** SillyTavern `chat_display`: 0 flat, 1 bubbles, 2 document. */
@Serializable enum class MessageStyle { FLAT, BUBBLES, DOCUMENT }

/**
 * Look and feel. Colors are ARGB ints; null keeps the Material color for the current theme mode.
 * Names follow SillyTavern's theme keys (see [ThemeIo]).
 */
@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeName: String = "",
    val mainText: Int? = null,
    val italicsText: Int? = null,
    val underlineText: Int? = null,
    val quoteText: Int? = null,
    /** App surface (ST `blur_tint_color`). */
    val surface: Int? = null,
    /** Chat area behind the messages (ST `chat_tint_color`). */
    val chatBackground: Int? = null,
    val userBubble: Int? = null,
    val botBubble: Int? = null,
    val fontScale: Float = 1f,
    val avatarStyle: AvatarStyle = AvatarStyle.ROUND,
    val messageStyle: MessageStyle = MessageStyle.BUBBLES,
    /** Chat column width in percent of the screen on wide screens (ST `chat_width`); phones always use the full width. */
    val chatWidth: Int = 50,
    val showTimestamps: Boolean = false,
    val showMessageNumbers: Boolean = false,
    val hideAvatars: Boolean = false,
    val renderMarkdown: Boolean = true,
    val allowHtml: Boolean = true,
    /** File name inside `backgrounds/`, or empty. */
    val background: String = "",
    /** Black overlay on the background image, 0..0.9. */
    val backgroundDim: Float = 0.35f,
    /** Locale code (see [Translator]); "en" = no translation. */
    val language: String = "en",
) {
    companion object {
        const val WIDE_SCREEN_DP = 600
        const val MIN_COLUMN_DP = 560

        /** Width of the chat column. Screens under [WIDE_SCREEN_DP] use all of it; wider ones use [chatWidth] percent, at least [MIN_COLUMN_DP]. */
        fun chatColumnDp(screenDp: Int, chatWidth: Int): Int {
            if (screenDp < WIDE_SCREEN_DP) return screenDp
            val wanted = screenDp * chatWidth.coerceIn(25, 100) / 100
            return wanted.coerceAtLeast(MIN_COLUMN_DP).coerceAtMost(screenDp)
        }
    }
}
