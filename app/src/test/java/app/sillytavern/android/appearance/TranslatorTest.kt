package app.sillytavern.android.appearance

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TranslatorTest {
    @Test fun lookupAndFallback() {
        val t = Translator.fromJson("""{"Send":"Enviar","Bad":1,"Empty":""}""")
        assertEquals("Enviar", t("Send")); assertEquals("Other", t("Other")); assertEquals("Bad", t("Bad")); assertEquals("Empty", t("Empty"))
    }
    @Test fun brokenFileIsEmpty() { assertTrue(Translator.fromJson("not json").isEmpty); assertTrue(Translator.fromJson("[1]").isEmpty) }
    @Test fun noneReturnsKey() = assertEquals("Send", Translator.NONE("Send"))

    private val dir = File("src/main/assets/locales")
    private fun load(code: String) = Translator.fromJson(File(dir, "$code.json").readText())
    private fun keys(code: String) = (kotlinx.serialization.json.Json.parseToJsonElement(File(dir, "$code.json").readText()) as kotlinx.serialization.json.JsonObject).keys

    @Test fun everyListedLanguageHasAFile() {
        Translator.languages.filter { it.first != "en" }.forEach { (code, _) -> assertTrue("$code.json missing", File(dir, "$code.json").exists()); assertFalse(load(code).isEmpty) }
    }
    @Test fun localesHaveTheSameKeys() {
        val codes = Translator.languages.map { it.first }.filter { it != "en" }
        val base = keys(codes.first())
        codes.drop(1).forEach { assertEquals("keys of $it", base, keys(it)) }
    }
    @Test fun noTranslationIsBlankOrKeepsOnlyPlaceholdersWrong() {
        Translator.languages.filter { it.first != "en" }.forEach { (code, _) ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(File(dir, "$code.json").readText()) as kotlinx.serialization.json.JsonObject
            o.forEach { (k, v) -> assertTrue("$code: $k", (v as kotlinx.serialization.json.JsonPrimitive).content.isNotBlank()) }
        }
    }
}
