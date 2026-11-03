package app.sillytavern.android.tools

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Translates text with the provider chosen in the settings. */
class TextTranslator(
    private val settings: () -> TranslateSettings,
    private val apiKey: () -> String?,
    private val client: OkHttpClient = Backends.defaultClient,
    private val googleUrl: String = "https://translate.googleapis.com/translate_a/single",
) {
    /**
     * Translates [text] into [to]. [llm] answers a prompt with the main connection and is needed for the LLM provider.
     * Blank text and text already in the requested language are returned as they are only by the provider's own judgement.
     */
    suspend fun translate(text: String, to: String, llm: (suspend (String) -> String)? = null): String {
        if (text.isBlank()) return text
        val s = settings()
        val lang = to.trim().ifEmpty { "en" }
        return when (s.provider) {
            TranslateProvider.LLM -> {
                val ask = llm ?: throw BackendException("The LLM translator needs an open chat with a connection")
                ask(llmPrompt(text, lang)).trim().ifEmpty { throw BackendException("The model returned no translation") }
            }
            else -> buildString { for (part in chunk(text, MAX_CHUNK)) append(if (part.isBlank()) part else restoreEdges(part, one(s, part, lang))) }
        }
    }

    private suspend fun one(s: TranslateSettings, text: String, to: String): String = withContext(Dispatchers.IO) {
        val req = when (s.provider) {
            TranslateProvider.GOOGLE -> Request.Builder().url("$googleUrl?client=gtx&sl=auto&tl=$to&dt=t")
                .post(FormBody.Builder().add("q", text).build()).build()
            TranslateProvider.LIBRETRANSLATE -> Request.Builder().url(s.libreUrl.trimEnd('/') + "/translate")
                .post(buildJsonObject {
                    put("q", text); put("source", "auto"); put("target", to); put("format", "text")
                    apiKey()?.takeIf { it.isNotBlank() }?.let { put("api_key", it) }
                }.toString().toRequestBody("application/json".toMediaType())).build()
            TranslateProvider.DEEPL -> Request.Builder().url(s.deeplUrl.trimEnd('/') + "/v2/translate")
                .header("Authorization", "DeepL-Auth-Key ${apiKey().orEmpty()}")
                .post(buildJsonObject {
                    put("text", JsonArray(listOf(JsonPrimitive(text)))); put("target_lang", deeplCode(to))
                }.toString().toRequestBody("application/json".toMediaType())).build()
            TranslateProvider.LLM -> error("unreachable")
        }
        try {
            client.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw BackendException("Translation HTTP ${r.code}: ${body.take(300)}")
                parse(s.provider, body)
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }
    }

    companion object {
        const val MAX_CHUNK = 3500

        fun llmPrompt(text: String, lang: String) =
            "Translate the following text into $lang. Keep the formatting, names and any *actions* or \"quotes\" markers. Reply with the translation only.\n\n$text"

        /** DeepL wants upper-case codes; Chinese and Portuguese variants keep their region. */
        fun deeplCode(code: String): String = when (val c = code.trim()) {
            "zh", "zh-CN", "zh-Hans" -> "ZH"
            "zh-TW", "zh-Hant" -> "ZH-HANT"
            "pt" -> "PT-PT"
            "en" -> "EN-US"
            else -> c.uppercase()
        }

        fun parse(provider: TranslateProvider, raw: String): String {
            val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: throw BackendException("Unreadable translation response")
            return try {
                when (provider) {
                    // [[["translated","original",...],...],null,"en"]
                    TranslateProvider.GOOGLE -> (root.jsonArray[0].jsonArray).joinToString("") { it.jsonArray[0].jsonPrimitive.contentOrNull.orEmpty() }
                    TranslateProvider.LIBRETRANSLATE -> root.jsonObject["translatedText"]!!.jsonPrimitive.content
                    TranslateProvider.DEEPL -> root.jsonObject["translations"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                    TranslateProvider.LLM -> raw
                }
            } catch (e: Exception) { throw BackendException("Unreadable translation response") }
        }

        /** Splits at paragraph, then line, then sentence ends so that every piece is at most [max] characters; pieces concatenate back to [text]. */
        fun chunk(text: String, max: Int): List<String> {
            if (text.length <= max) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (rest.length > max) {
                val window = rest.substring(0, max)
                var cut = window.lastIndexOf("\n\n").let { if (it > max / 4) it + 2 else -1 }
                if (cut < 0) cut = window.lastIndexOf('\n').let { if (it > max / 4) it + 1 else -1 }
                if (cut < 0) cut = Regex("[.!?。！？]\\s").findAll(window).lastOrNull()?.let { it.range.last + 1 } ?: -1
                if (cut <= 0) cut = max
                out += rest.substring(0, cut)
                rest = rest.substring(cut)
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }

        /** Providers trim whitespace; keep the original leading/trailing line breaks so chunks join cleanly. */
        private fun restoreEdges(original: String, translated: String): String {
            val lead = original.takeWhile { it.isWhitespace() }
            val trail = original.takeLastWhile { it.isWhitespace() }
            return lead + translated.trim() + trail
        }
    }
}
