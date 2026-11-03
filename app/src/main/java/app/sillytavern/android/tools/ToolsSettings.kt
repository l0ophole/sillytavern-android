package app.sillytavern.android.tools

import kotlinx.serialization.Serializable

@Serializable
enum class TranslateMode { OFF, RESPONSES, INPUTS, BOTH }

@Serializable
enum class TranslateProvider { GOOGLE, LIBRETRANSLATE, DEEPL, LLM }

/** SillyTavern's Chat Translation extension: replies are shown in [target], your messages are sent in [internal]. */
@Serializable
data class TranslateSettings(
    val mode: TranslateMode = TranslateMode.OFF,
    val provider: TranslateProvider = TranslateProvider.GOOGLE,
    /** Language replies are shown in (ISO code such as `en`, `de`, `ja`, `zh-CN`). */
    val target: String = "en",
    /** Language the model reads and writes; your messages are translated into it when input translation is on. */
    val internal: String = "en",
    /** LibreTranslate server, DeepL API root (`https://api-free.deepl.com` or `https://api.deepl.com`). */
    val libreUrl: String = "https://libretranslate.com",
    val deeplUrl: String = "https://api-free.deepl.com",
) {
    val translatesResponses get() = mode == TranslateMode.RESPONSES || mode == TranslateMode.BOTH
    val translatesInputs get() = mode == TranslateMode.INPUTS || mode == TranslateMode.BOTH
}

@Serializable
enum class SearchProvider { SEARXNG, BRAVE, TAVILY, SERPER }

/** Web search results put into the prompt (SillyTavern's Web Search extension, without page scraping). */
@Serializable
data class SearchSettings(
    val enabled: Boolean = false,
    val provider: SearchProvider = SearchProvider.SEARXNG,
    /** SearXNG instance (must allow `format=json`). The other providers use fixed API addresses. */
    val searxUrl: String = "http://127.0.0.1:8080",
    val resultCount: Int = 5,
    /** If not empty, only messages containing one of these (comma separated) trigger a search; empty = every message. */
    val triggers: String = "",
    /** Longest snippet kept per result, in characters. */
    val snippetLength: Int = 400,
    val template: String = "Web search results for \"{{query}}\":\n{{text}}",
    val position: Int = 1,
    val depth: Int = 2,
    val role: Int = 0,
)

@Serializable
data class ToolsSettings(
    val translate: TranslateSettings = TranslateSettings(),
    val search: SearchSettings = SearchSettings(),
    /** Catalog address shown in the content manager. */
    val catalogUrl: String = "",
) {
    companion object {
        const val KEY_TRANSLATE = "tools:translate"
        const val KEY_SEARCH = "tools:search"
    }
}
