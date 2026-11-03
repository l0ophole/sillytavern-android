package app.sillytavern.android.appearance

import org.junit.Assert.*
import org.junit.Test

class ThemeIoTest {
    private val dark = """{"name":"Dark Lite","main_text_color":"rgba(220, 220, 210, 1)","italics_text_color":"rgba(145, 145, 145, 1)",
        "quote_text_color":"rgba(225, 138, 36, 1)","blur_tint_color":"rgba(23, 23, 23, 1)","chat_tint_color":"rgba(23, 23, 23, 1)",
        "user_mes_blur_tint_color":"rgba(30, 30, 30, 0.9)","bot_mes_blur_tint_color":"rgba(30, 30, 30, 0.9)","font_scale":1.2,
        "avatar_style":2,"chat_display":2,"chat_width":70,"timestamps_enabled":true,"mesIDDisplay_enabled":true,"hideChatAvatars_enabled":false}"""

    @Test fun parseRgba() = assertEquals(0xFFDCDCD2.toInt(), ThemeIo.parseColor("rgba(220, 220, 210, 1)"))
    @Test fun parseRgbNoAlpha() = assertEquals(0xFF010203.toInt(), ThemeIo.parseColor("rgb(1,2,3)"))
    @Test fun parseHexForms() { assertEquals(0xFFAABBCC.toInt(), ThemeIo.parseColor("#abc")); assertEquals(0xFF112233.toInt(), ThemeIo.parseColor("#112233")); assertEquals(0x80112233.toInt(), ThemeIo.parseColor("#11223380")) }
    @Test fun parseHalfAlpha() = assertEquals(0x80, ThemeIo.parseColor("rgba(0,0,0,0.5)")!! ushr 24)
    @Test fun badColors() { assertNull(ThemeIo.parseColor("red")); assertNull(ThemeIo.parseColor("rgba(300,0,0,1)")); assertNull(ThemeIo.parseColor("#12")); assertNull(ThemeIo.parseColor(null)) }
    @Test fun formatRoundTrip() { val c = 0xE6141E28.toInt(); assertEquals(c.ushr(24), ThemeIo.parseColor(ThemeIo.formatColor(c))!!.ushr(24)); assertEquals("rgba(10, 20, 30, 1)", ThemeIo.formatColor(0xFF0A141E.toInt())) }

    @Test fun importMapsFields() {
        val a = ThemeIo.import(dark)
        assertEquals("Dark Lite", a.themeName); assertEquals(ThemeMode.DARK, a.themeMode)
        assertEquals(0xFFDCDCD2.toInt(), a.mainText); assertEquals(0xFFE18A24.toInt(), a.quoteText)
        assertEquals(1.2f, a.fontScale); assertEquals(AvatarStyle.SQUARE, a.avatarStyle); assertEquals(MessageStyle.DOCUMENT, a.messageStyle)
        assertEquals(70, a.chatWidth); assertTrue(a.showTimestamps); assertTrue(a.showMessageNumbers); assertFalse(a.hideAvatars)
    }
    @Test fun lightSurfaceGivesLightMode() = assertEquals(ThemeMode.LIGHT, ThemeIo.import("""{"blur_tint_color":"rgba(250,250,250,1)","main_text_color":"#000"}""").themeMode)
    @Test fun importKeepsBaseForMissing() = assertEquals(AvatarStyle.RECTANGULAR, ThemeIo.import("""{"main_text_color":"#fff"}""", AppearanceSettings(avatarStyle = AvatarStyle.RECTANGULAR)).avatarStyle)
    @Test fun importClampsFontAndWidth() { val a = ThemeIo.import("""{"main_text_color":"#fff","font_scale":9,"chat_width":3}"""); assertEquals(2f, a.fontScale); assertEquals(25, a.chatWidth) }
    @Test fun importRejectsNonTheme() { assertThrows(IllegalArgumentException::class.java) { ThemeIo.import("[1]") }; assertThrows(IllegalArgumentException::class.java) { ThemeIo.import("{\"a\":1}") } }
    @Test fun exportThenImport() {
        val a = ThemeIo.import(dark)
        val b = ThemeIo.import(ThemeIo.export(a, "Mine"))
        assertEquals(a.mainText, b.mainText); assertEquals(a.userBubble, b.userBubble); assertEquals(a.messageStyle, b.messageStyle)
        assertEquals(b, ThemeIo.import(ThemeIo.export(b, "Mine")))   // stable after the first trip (defaults filled in)
    }
    @Test fun exportHasStKeys() { val t = ThemeIo.export(AppearanceSettings(), "x"); listOf("main_text_color", "chat_display", "avatar_style", "blur_tint_color", "custom_css").forEach { assertTrue(it, t.contains("\"$it\"")) } }
    @Test fun nameOf() { assertEquals("Dark Lite", ThemeIo.nameOf(dark)); assertNull(ThemeIo.nameOf("nope")) }
    @Test fun columnWidth() {
        assertEquals(400, AppearanceSettings.chatColumnDp(400, 50))
        assertEquals(560, AppearanceSettings.chatColumnDp(700, 50))
        assertEquals(900, AppearanceSettings.chatColumnDp(1200, 75))
        assertEquals(560, AppearanceSettings.chatColumnDp(600, 25))
    }
}
