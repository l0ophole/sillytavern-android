package app.sillytavern.android.media

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.Backends
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeneratedImage(val bytes: ByteArray, val extension: String = "png")

/** What `/sd` can draw (SillyTavern's generation "triggers"). */
enum class ImageMode(val words: List<String>) {
    CHARACTER(listOf("you", "yourself", "char", "character")),
    USER(listOf("me", "user", "myself")),
    SCENE(listOf("scene", "story", "raw")),
    LAST(listOf("last", "message")),
    RAW_LAST(listOf("raw_last", "raw-last")),
    FACE(listOf("face")),
    BACKGROUND(listOf("background", "bg"));

    companion object {
        fun parse(word: String): ImageMode? = entries.firstOrNull { m -> word.trim().lowercase() in m.words }
            ?: entries.firstOrNull { it.name.equals(word.trim(), true) }
    }
}

object ImagePrompts {
    private const val KEYWORDS = "Reply with ONLY a comma-separated list of keywords and short phrases. No full sentences."

    /** Instruction sent to the main model so it writes the image prompt; null = use the text directly. */
    fun instruction(mode: ImageMode): String? = when (mode) {
        ImageMode.CHARACTER -> "[Pause your roleplay. $KEYWORDS Describe {{char}}'s visual appearance in this order: name, species and race, gender, age, clothing, occupation, physical features. Include only what could be seen in a photograph, no personality or thoughts. Begin the list with 'full body portrait,'.]"
        ImageMode.USER -> "[Pause your roleplay. $KEYWORDS Describe {{user}}'s visual appearance in this order: name, species and race, gender, age, clothing, occupation, physical features. Include only what could be seen in a photograph. Begin the list with 'full body portrait,'.]"
        ImageMode.FACE -> "[Pause your roleplay. $KEYWORDS Describe {{char}}'s face and expression: name, species and race, gender, age, hair, eyes, facial features, expression. Begin the list with 'close up face portrait,'.]"
        ImageMode.SCENE -> "[Pause your roleplay. $KEYWORDS Describe the current scene as a single still image: who is present and how they look, their poses and actions, the setting, lighting and mood.]"
        ImageMode.LAST -> "[Pause your roleplay. $KEYWORDS Describe what the last chat message shows visually: the people, their poses and actions, the setting, lighting and mood.]"
        ImageMode.BACKGROUND -> "[Pause your roleplay. $KEYWORDS Describe only the current location as a background image with no people in it: place, objects, time of day, weather, lighting. Begin the list with 'background,'.]"
        ImageMode.RAW_LAST -> null
    }

    /** Cleans a model-written prompt: first block, no wrapping quotes/brackets, newlines to commas. */
    fun clean(raw: String): String {
        var t = raw.trim().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        t = t.trim('"', '\'', '[', ']', '`', '“', '”', ' ').trimEnd('.', ',', ' ')
        return t.replace(Regex("\\s*,\\s*(,\\s*)+"), ", ")
    }

    /** Applies the prefix: `{prompt}` inside it marks where the generated text goes, otherwise it is prepended. */
    fun compose(prefix: String, prompt: String): String {
        if (prefix.isBlank()) return prompt
        return if (prefix.contains("{prompt}")) prefix.replace("{prompt}", prompt) else prefix.trimEnd().trimEnd(',') + ", " + prompt
    }
}

/** Image generation back ends. */
interface ImageBackend {
    suspend fun generate(s: ImageGenSettings, prompt: String, negative: String, apiKey: String?): GeneratedImage
}

class ImageGenerator(
    private val client: OkHttpClient = Backends.defaultClient,
    /** Delay between ComfyUI history polls. */
    private val pollMs: Long = 1000,
    private val comfyTimeoutMs: Long = 300_000,
) : ImageBackend {
    private val json = "application/json".toMediaType()

    override suspend fun generate(s: ImageGenSettings, prompt: String, negative: String, apiKey: String?): GeneratedImage =
        try {
            when (s.source) {
                ImageSource.A1111 -> a1111(s, prompt, negative)
                ImageSource.COMFY -> comfy(s, prompt, negative)
                ImageSource.NOVELAI -> novelAi(s, prompt, negative, apiKey)
                ImageSource.OPENAI -> openAi(s, prompt, apiKey)
            }
        } catch (e: java.io.IOException) {
            throw BackendException("Network error: ${e.message}", e)
        }

    // ------------------------------------------------------------ AUTOMATIC1111 / Forge

    fun a1111Body(s: ImageGenSettings, prompt: String, negative: String): JsonObject = buildJsonObject {
        put("prompt", prompt); put("negative_prompt", negative)
        put("steps", s.steps); put("cfg_scale", s.cfg); put("width", s.width); put("height", s.height)
        put("seed", s.seed); put("sampler_name", s.sampler)
        if (s.scheduler.isNotBlank()) put("scheduler", s.scheduler)
        put("batch_size", 1); put("n_iter", 1)
        if (s.model.isNotBlank()) put("override_settings", buildJsonObject { put("sd_model_checkpoint", s.model) })
    }

    private suspend fun a1111(s: ImageGenSettings, prompt: String, negative: String): GeneratedImage {
        val req = Request.Builder().url(s.url.trimEnd('/') + "/sdapi/v1/txt2img")
            .post(a1111Body(s, prompt, negative).toString().toRequestBody(json)).build()
        val o = callJson(req)
        val b64 = (o["images"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull ?: throw BackendException("The server returned no image")
        return GeneratedImage(decodeB64(b64))
    }

    // ------------------------------------------------------------ ComfyUI

    /** Substitutes the placeholders of an API-format workflow; text values are JSON-escaped. */
    fun comfyWorkflow(s: ImageGenSettings, prompt: String, negative: String): JsonObject {
        if (s.comfyWorkflow.isBlank()) throw BackendException("Paste a ComfyUI workflow (API format) in the image settings")
        fun esc(t: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(t)).let { it.substring(1, it.length - 1) }
        val seed = if (s.seed < 0) kotlin.random.Random.nextLong(0, 1L shl 40) else s.seed
        val text = s.comfyWorkflow
            .replace("%prompt%", esc(prompt)).replace("%negative_prompt%", esc(negative))
            .replace("%seed%", seed.toString()).replace("%steps%", s.steps.toString()).replace("%scale%", s.cfg.toString())
            .replace("%width%", s.width.toString()).replace("%height%", s.height.toString())
            .replace("%model%", esc(s.model)).replace("%sampler%", esc(s.sampler)).replace("%scheduler%", esc(s.scheduler.ifBlank { "normal" }))
        return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse { throw BackendException("The ComfyUI workflow is not valid JSON: ${it.message}") }
    }

    private suspend fun comfy(s: ImageGenSettings, prompt: String, negative: String): GeneratedImage {
        val base = s.url.trimEnd('/')
        val workflow = comfyWorkflow(s, prompt, negative)
        val queued = callJson(Request.Builder().url("$base/prompt").post(buildJsonObject { put("prompt", workflow) }.toString().toRequestBody(json)).build())
        val id = queued["prompt_id"]?.jsonPrimitive?.contentOrNull ?: throw BackendException("ComfyUI did not return a prompt id")
        var waited = 0L
        while (true) {
            val hist = callJson(Request.Builder().url("$base/history/$id").build())
            val entry = hist[id] as? JsonObject
            if (entry != null) {
                (entry["status"] as? JsonObject)?.takeIf { it["status_str"]?.jsonPrimitive?.contentOrNull == "error" }
                    ?.let { throw BackendException("ComfyUI failed to run the workflow") }
                val image = (entry["outputs"] as? JsonObject)?.values.orEmpty()
                    .firstNotNullOfOrNull { out -> ((out as? JsonObject)?.get("images") as? JsonArray)?.firstOrNull() as? JsonObject }
                if (image != null) {
                    val url = "$base/view".toHttpUrlOrNull()!!.newBuilder()
                        .addQueryParameter("filename", image["filename"]!!.jsonPrimitive.content)
                        .addQueryParameter("subfolder", image["subfolder"]?.jsonPrimitive?.contentOrNull.orEmpty())
                        .addQueryParameter("type", image["type"]?.jsonPrimitive?.contentOrNull ?: "output").build()
                    val bytes = callBytes(Request.Builder().url(url).build())
                    return GeneratedImage(bytes, image["filename"]!!.jsonPrimitive.content.substringAfterLast('.', "png"))
                }
            }
            if (waited >= comfyTimeoutMs) throw BackendException("ComfyUI timed out")
            delay(pollMs); waited += pollMs
        }
    }

    // ------------------------------------------------------------ NovelAI

    fun novelBody(s: ImageGenSettings, prompt: String, negative: String): JsonObject = buildJsonObject {
        put("input", prompt); put("model", s.novelModel); put("action", "generate")
        put("parameters", buildJsonObject {
            put("width", s.width); put("height", s.height); put("scale", s.cfg); put("steps", s.steps)
            put("sampler", if (s.sampler.startsWith("k_")) s.sampler else "k_euler")
            put("seed", if (s.seed < 0) kotlin.random.Random.nextLong(0, 4_294_967_295L) else s.seed)
            put("n_samples", 1); put("negative_prompt", negative); put("qualityToggle", true); put("ucPreset", 0)
        })
    }

    private suspend fun novelAi(s: ImageGenSettings, prompt: String, negative: String, key: String?): GeneratedImage {
        if (key.isNullOrBlank()) throw BackendException("Enter your NovelAI API key in the image settings")
        val req = Request.Builder().url(s.novelUrl.trimEnd('/') + "/ai/generate-image")
            .header("Authorization", "Bearer $key").header("Accept", "application/zip")
            .post(novelBody(s, prompt, negative).toString().toRequestBody(json)).build()
        return unzipFirstImage(callBytes(req))
    }

    // ------------------------------------------------------------ OpenAI-compatible

    fun openAiBody(s: ImageGenSettings, prompt: String): JsonObject = buildJsonObject {
        put("model", s.openAiModel); put("prompt", prompt); put("n", 1); put("size", "${s.width}x${s.height}")
        if (s.openAiModel.startsWith("dall-e")) {
            put("response_format", "b64_json")
            if (s.openAiModel == "dall-e-3") put("quality", s.openAiQuality)
        } else if (s.openAiQuality.isNotBlank() && s.openAiQuality != "standard") put("quality", s.openAiQuality)
    }

    private suspend fun openAi(s: ImageGenSettings, prompt: String, key: String?): GeneratedImage {
        val req = Request.Builder().url(s.openAiUrl.trimEnd('/') + "/images/generations")
            .apply { key?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
            .post(openAiBody(s, prompt).toString().toRequestBody(json)).build()
        val item = ((callJson(req)["data"] as? JsonArray)?.firstOrNull() as? JsonObject) ?: throw BackendException("The server returned no image")
        item["b64_json"]?.jsonPrimitive?.contentOrNull?.let { return GeneratedImage(decodeB64(it)) }
        val url = item["url"]?.jsonPrimitive?.contentOrNull ?: throw BackendException("The server returned no image")
        return GeneratedImage(callBytes(Request.Builder().url(url).build()))
    }

    // ------------------------------------------------------------ HTTP helpers

    private suspend fun callBytes(req: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw BackendException("HTTP ${r.code}: ${bytes.toString(Charsets.UTF_8).take(300)}")
            bytes
        }
    }

    private suspend fun callJson(req: Request): JsonObject {
        val bytes = callBytes(req)
        return runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            .getOrElse { throw BackendException("Unreadable response from the image server") }
    }

    companion object {
        fun decodeB64(s: String): ByteArray = java.util.Base64.getMimeDecoder().decode(s.substringAfter("base64,"))

        /** NovelAI answers with a zip holding `image_0.png`. */
        fun unzipFirstImage(zip: ByteArray): GeneratedImage {
            java.util.zip.ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (!e.isDirectory && e.name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp"))
                        return GeneratedImage(z.readBytes(), e.name.substringAfterLast('.').lowercase())
                }
            }
            throw BackendException("NovelAI returned no image")
        }
    }
}
