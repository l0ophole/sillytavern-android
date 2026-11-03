package app.sillytavern.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.sillytavern.android.appearance.*

val LocalTranslator = staticCompositionLocalOf { Translator.NONE }

/** Translates a UI string (English text is the key); falls back to the English text. */
@Composable
fun tr(text: String): String = LocalTranslator.current(text)

/** Colors for chat text and message containers, resolved from [AppearanceSettings] and the active theme. */
@Immutable
class ChatColors(
    val text: Color, val italics: Color, val underline: Color, val quote: Color,
    val user: Color, val bot: Color, val narrator: Color, val chatBackground: Color?,
)

val LocalChatColors = staticCompositionLocalOf {
    ChatColors(Color.Unspecified, Color.Gray, Color.Unspecified, Color(0xFFE18A24), Color.Unspecified, Color.Unspecified, Color.Unspecified, null)
}
val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

internal fun Int.toColor() = Color(this)
internal fun Int.opaque() = Color(this or 0xFF000000.toInt())

@Composable
fun SillyTheme(a: AppearanceSettings = AppearanceSettings(), translator: Translator = Translator.NONE, content: @Composable () -> Unit) {
    val dark = when (a.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    var scheme = if (dark) darkColorScheme() else lightColorScheme()
    if (a.themeMode == ThemeMode.AMOLED) scheme = scheme.copy(background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF141414), surfaceContainer = Color(0xFF0A0A0A))
    a.surface?.let { scheme = scheme.copy(background = it.opaque(), surface = it.opaque(), surfaceContainer = it.opaque()) }
    a.mainText?.let { scheme = scheme.copy(onSurface = it.opaque(), onBackground = it.opaque(), onSurfaceVariant = it.opaque()) }
    val chat = ChatColors(
        text = a.mainText?.opaque() ?: scheme.onSurface,
        italics = a.italicsText?.opaque() ?: if (dark) Color(0xFFB4B4B4) else Color(0xFF606060),
        underline = a.underlineText?.opaque() ?: if (dark) Color(0xFFBCE7CF) else Color(0xFF2E7D5B),
        quote = a.quoteText?.opaque() ?: if (dark) Color(0xFFE18A24) else Color(0xFFB45F06),
        user = a.userBubble?.toColor() ?: scheme.primaryContainer,
        bot = a.botBubble?.toColor() ?: scheme.surfaceVariant,
        narrator = scheme.tertiaryContainer,
        chatBackground = a.chatBackground?.opaque() ?: if (a.themeMode == ThemeMode.AMOLED) Color.Black else null,
    )
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalTranslator provides translator, LocalChatColors provides chat, LocalAppearance provides a,
        LocalDensity provides Density(d.density, d.fontScale * a.fontScale),
    ) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** Loads the translator for [code] from the app's assets; "en" or a missing/broken file gives no translation. */
@Composable
fun rememberTranslator(code: String): Translator {
    val ctx = LocalContext.current
    return remember(code) {
        if (code == "en") Translator.NONE
        else runCatching { Translator.fromJson(ctx.assets.open("locales/$code.json").bufferedReader().use { it.readText() }) }.getOrDefault(Translator.NONE)
    }
}
