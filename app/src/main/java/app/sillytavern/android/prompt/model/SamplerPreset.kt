package app.sillytavern.android.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** SillyTavern text-completion sampler preset (default/content/presets/textgen). */
@Serializable
data class TextGenSamplerPreset(
    val name: String = "Default",
    val temp: Double = 1.0,
    @SerialName("top_p") val topP: Double = 1.0,
    @SerialName("top_k") val topK: Int = 0,
    @SerialName("top_a") val topA: Double = 0.0,
    val tfs: Double = 1.0,
    @SerialName("typical_p") val typicalP: Double = 1.0,
    @SerialName("min_p") val minP: Double = 0.0,
    @SerialName("rep_pen") val repPen: Double = 1.0,
    @SerialName("rep_pen_range") val repPenRange: Int = 0,
    @SerialName("freq_pen") val freqPen: Double = 0.0,
    @SerialName("presence_pen") val presencePen: Double = 0.0,
    @SerialName("dry_multiplier") val dryMultiplier: Double = 0.0,
    @SerialName("dry_base") val dryBase: Double = 1.75,
    @SerialName("dry_allowed_length") val dryAllowedLength: Int = 2,
    @SerialName("dry_penalty_last_n") val dryPenaltyLastN: Int = 0,
    @SerialName("dry_sequence_breakers") val drySequenceBreakers: String = "[\"\\n\", \":\", \"\\\"\", \"*\"]",
    @SerialName("mirostat_mode") val mirostatMode: Int = 0,
    @SerialName("mirostat_tau") val mirostatTau: Double = 5.0,
    @SerialName("mirostat_eta") val mirostatEta: Double = 0.1,
    @SerialName("smoothing_factor") val smoothingFactor: Double = 0.0,
    @SerialName("smoothing_curve") val smoothingCurve: Double = 1.0,
    @SerialName("xtc_threshold") val xtcThreshold: Double = 0.1,
    @SerialName("xtc_probability") val xtcProbability: Double = 0.0,
    val nsigma: Double = 0.0,
    val dynatemp: Boolean = false,
    @SerialName("min_temp") val minTemp: Double = 0.0,
    @SerialName("max_temp") val maxTemp: Double = 2.0,
    @SerialName("dynatemp_exponent") val dynatempExponent: Double = 1.0,
    @SerialName("ban_eos_token") val banEosToken: Boolean = false,
    @SerialName("skip_special_tokens") val skipSpecialTokens: Boolean = true,
    val seed: Long = -1,
    @SerialName("grammar_string") val grammarString: String = "",
    @SerialName("negative_prompt") val negativePrompt: String = "",
    @SerialName("guidance_scale") val guidanceScale: Double = 1.0,
    @SerialName("banned_tokens") val bannedTokens: String = "",
    val extra: JsonObject = JsonObject(emptyMap()),
)
