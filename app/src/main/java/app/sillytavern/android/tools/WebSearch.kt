package app.sillytavern.android.tools

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.prompt.ExtensionPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class SearchResult(val title: String, val url: String, val snippet: String)

/** Runs web searches and turns the newest user message into a prompt with the results. */
class WebSearchService(
    private val settings: () -> SearchSettings,
    private val apiKey: () -> String?,
    private val client: OkHttpClient = Backends.defaultClient,
    private val braveUrl: String = "https://api.search.brave.com/res/v1/web/search",
    private val tavilyUrl: String = "https://api.tavily.com/search",
    private val serperUrl: String = "https://google.serper.dev/search",
) {
    fun request(query: String, s: SearchSettings = settings()): Request {
        val n = s.resultCount.coerceIn(1, 20)
        val key = apiKey().orEmpty()
        return when (s.provider) {
            SearchProvider.SEARXNG -> Request.Builder().url(s.searxUrl.trimEnd('/').toHttpUrlOrFail().newBuilder()
                .addPathSegment("search").addQueryParameter("q", query).addQueryParameter("format", "json").build()).build()
            SearchProvider.BRAVE -> Request.Builder().url(braveUrl.toHttpUrl().newBuilder().addQueryParameter("q", query).addQueryParameter("count", n.toString()).build())
                .header("X-Subscription-Token", key).header("Accept", "application/json").build()
            SearchProvider.TAVILY -> Request.Builder().url(tavilyUrl).header("Authorization", "Bearer $key")
                .post(buildJsonObject { put("query", query); put("max_results", n) }.toString().toRequestBody(JSON)).build()
            SearchProvider.SERPER -> Request.Builder().url(serperUrl).header("X-API-KEY", key)
                .post(buildJsonObject { put("q", query); put("num", n) }.toString().toRequestBody(JSON)).build()
        }
    }

    suspend fun results(query: String): List<SearchResult> {
        val s = settings()
        if (query.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request(query, s)).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw BackendException("Search HTTP ${r.code}: ${body.take(300)}")
                    parse(s.provider, body).take(s.resultCount.coerceIn(1, 20))
                }
            } catch (e: java.io.IOException) {
                throw BackendException("Network error: ${e.message}", e)
            }
        }
    }

    /** Results as numbered text, or a "no results" line. */
    suspend fun search(query: String): String = format(results(query), settings().snippetLength)

    /** The prompt for a generation over [history]; null when search is off, the message is not a trigger, or nothing was found. */
    suspend fun prompt(history: List<ChatMessage>): ExtensionPrompt? {
        val s = settings()
        if (!s.enabled) return null
        val query = queryFor(history, s) ?: return null
        val found = results(query)
        if (found.isEmpty()) return null
        return ExtensionPrompt("websearch", s.template.replace("{{query}}", query).replace("{{text}}", format(found, s.snippetLength)), s.position, s.depth, s.role)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun String.toHttpUrlOrFail() = runCatching { toHttpUrl() }.getOrElse { throw BackendException("Invalid search server URL") }

        /** The search query: the newest user message, unless trigger phrases are set and none appears in it. */
        fun queryFor(history: List<ChatMessage>, s: SearchSettings): String? {
            val last = history.lastOrNull { !it.isSystem || it.isNarrator } ?: return null
            if (!last.isUser) return null
            val text = last.text.trim()
            if (text.isEmpty()) return null
            val triggers = s.triggers.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            if (triggers.isNotEmpty() && triggers.none { it in text.lowercase() }) return null
            return text.take(300)
        }

        fun format(results: List<SearchResult>, snippetLength: Int): String =
            if (results.isEmpty()) "No results."
            else results.mapIndexed { i, r ->
                val snippet = r.snippet.replace(Regex("\\s+"), " ").trim().take(snippetLength.coerceAtLeast(40))
                "${i + 1}. ${r.title.trim()} (${r.url})" + if (snippet.isNotEmpty()) "\n   $snippet" else ""
            }.joinToString("\n")

        fun parse(provider: SearchProvider, raw: String): List<SearchResult> {
            val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: throw BackendException("Unreadable search response")
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            fun list(e: JsonElement?, title: String, url: String, snippet: String) =
                (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { o -> SearchResult(o.str(title), o.str(url), o.str(snippet)) } }
                    .filter { it.url.isNotEmpty() || it.title.isNotEmpty() }
            return when (provider) {
                SearchProvider.SEARXNG -> list(root["results"], "title", "url", "content")
                SearchProvider.BRAVE -> list((root["web"] as? JsonObject)?.get("results"), "title", "url", "description")
                SearchProvider.TAVILY -> list(root["results"], "title", "url", "content")
                SearchProvider.SERPER -> list(root["organic"], "title", "link", "snippet")
            }
        }
    }
}
