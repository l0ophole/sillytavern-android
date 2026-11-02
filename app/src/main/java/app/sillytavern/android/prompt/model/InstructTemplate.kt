package app.sillytavern.android.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** SillyTavern instruct-mode template (default/content/presets/instruct). */
@Serializable
data class InstructTemplate(
    val name: String = "Instruct",
    @SerialName("input_sequence") val inputSequence: String = "",
    @SerialName("output_sequence") val outputSequence: String = "",
    @SerialName("system_sequence") val systemSequence: String = "",
    @SerialName("last_output_sequence") val lastOutputSequence: String = "",
    @SerialName("first_output_sequence") val firstOutputSequence: String = "",
    @SerialName("first_input_sequence") val firstInputSequence: String = "",
    @SerialName("last_input_sequence") val lastInputSequence: String = "",
    @SerialName("last_system_sequence") val lastSystemSequence: String = "",
    @SerialName("input_suffix") val inputSuffix: String = "",
    @SerialName("output_suffix") val outputSuffix: String = "",
    @SerialName("system_suffix") val systemSuffix: String = "",
    @SerialName("stop_sequence") val stopSequence: String = "",
    val wrap: Boolean = true,
    val macro: Boolean = true,
    @SerialName("names_behavior") val namesBehavior: String = "force",
    @SerialName("sequences_as_stop_strings") val sequencesAsStopStrings: Boolean = true,
    @SerialName("system_same_as_user") val systemSameAsUser: Boolean = false,
    @SerialName("skip_examples") val skipExamples: Boolean = false,
    @SerialName("story_string_prefix") val storyStringPrefix: String = "",
    @SerialName("story_string_suffix") val storyStringSuffix: String = "",
    @SerialName("user_alignment_message") val userAlignmentMessage: String = "",
    @SerialName("activation_regex") val activationRegex: String = "",
    val extra: JsonObject = JsonObject(emptyMap()),
)
