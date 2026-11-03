package app.sillytavern.android.media

import kotlinx.serialization.Serializable

@Serializable
enum class TtsProvider { SYSTEM, OPENAI, ELEVENLABS }

@Serializable
enum class SttProvider { SYSTEM, WHISPER }

@Serializable
enum class ImageSource { A1111, COMFY, NOVELAI, OPENAI }

/** Which parts of a message are read aloud. */
@Serializable
enum class NarrateMode { ALL, QUOTED, NON_QUOTED }

/** How an image attachment reaches the model: as an image part, or as a caption text. */
@Serializable
enum class ImagePromptMode { SEND_IMAGE, USE_CAPTION }

@Serializable
data class AttachmentSettings(
    val mode: ImagePromptMode = ImagePromptMode.SEND_IMAGE,
    /** Caption new image attachments right away (needs a vision-capable main connection). */
    val autoCaption: Boolean = false,
    val captionPrompt: String = "What's in this image?",
    /** Longest side an attached image is scaled to before saving; 0 keeps the original. */
    val maxImageSide: Int = 1568,
)

@Serializable
data class TtsSettings(
    val enabled: Boolean = false,
    val provider: TtsProvider = TtsProvider.SYSTEM,
    /** Speak new character replies as soon as they are complete. */
    val autoPlay: Boolean = false,
    val narrate: NarrateMode = NarrateMode.ALL,
    val skipCodeBlocks: Boolean = true,
    /** Read `*action*` text; when off, asterisk-wrapped text is not spoken. */
    val speakActions: Boolean = true,
    val rate: Double = 1.0,
    val pitch: Double = 1.0,
    /** Voice used when the speaker has no entry in [voiceMap]. Provider specific (system voice name, OpenAI voice, ElevenLabs voice id). */
    val defaultVoice: String = "",
    /** Speaker name -> voice. */
    val voiceMap: Map<String, String> = emptyMap(),
    val openAiUrl: String = "https://api.openai.com/v1",
    val openAiModel: String = "tts-1",
    val elevenUrl: String = "https://api.elevenlabs.io",
    val elevenModel: String = "eleven_multilingual_v2",
)

@Serializable
data class SttSettings(
    val enabled: Boolean = false,
    val provider: SttProvider = SttProvider.SYSTEM,
    /** BCP-47 tag (system) or ISO-639-1 code (Whisper); empty = automatic. */
    val language: String = "",
    val whisperUrl: String = "https://api.openai.com/v1",
    val whisperModel: String = "whisper-1",
    /** Send the transcript immediately instead of putting it in the input box. */
    val autoSend: Boolean = false,
)

@Serializable
data class ImageGenSettings(
    val source: ImageSource = ImageSource.A1111,
    val url: String = "http://127.0.0.1:7860",
    val model: String = "",
    val sampler: String = "Euler a",
    val scheduler: String = "",
    val steps: Int = 20,
    val cfg: Double = 7.0,
    val width: Int = 512,
    val height: Int = 512,
    /** -1 = random. */
    val seed: Long = -1,
    val prefix: String = "",
    val negative: String = "",
    /** Ask the main model to write the image prompt (otherwise the raw template text is used). */
    val llmPrompt: Boolean = true,
    /** ComfyUI API-format workflow with %prompt%, %negative_prompt%, %seed%, %steps%, %scale%, %width%, %height%, %model%, %sampler%, %scheduler% placeholders. */
    val comfyWorkflow: String = "",
    val novelModel: String = "nai-diffusion-3",
    val openAiModel: String = "dall-e-3",
    val openAiQuality: String = "standard",
    /** Place generated images in the chat as a message from the system. */
    val showAsMessage: Boolean = true,
)

@Serializable
data class MediaSettings(
    val attachments: AttachmentSettings = AttachmentSettings(),
    val tts: TtsSettings = TtsSettings(),
    val stt: SttSettings = SttSettings(),
    val imageGen: ImageGenSettings = ImageGenSettings(),
) {
    companion object {
        /** Secret-store keys for the services' API keys. */
        const val KEY_TTS = "media:tts"
        const val KEY_STT = "media:stt"
        const val KEY_IMAGE = "media:image"
    }
}
