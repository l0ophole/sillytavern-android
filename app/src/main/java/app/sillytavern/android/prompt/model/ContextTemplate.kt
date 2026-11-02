package app.sillytavern.android.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** SillyTavern context template (story string etc.). */
@Serializable
data class ContextTemplate(
    val name: String = "Default",
    @SerialName("story_string") val storyString: String =
        "{{#if system}}{{system}}\n{{/if}}{{#if description}}{{description}}\n{{/if}}" +
            "{{#if personality}}{{char}}'s personality: {{personality}}\n{{/if}}" +
            "{{#if scenario}}Scenario: {{scenario}}\n{{/if}}{{#if persona}}{{persona}}\n{{/if}}{{trim}}",
    @SerialName("example_separator") val exampleSeparator: String = "",
    @SerialName("chat_start") val chatStart: String = "",
    @SerialName("use_stop_strings") val useStopStrings: Boolean = false,
    @SerialName("names_as_stop_strings") val namesAsStopStrings: Boolean = true,
    @SerialName("story_string_position") val storyStringPosition: Int = 0,
    @SerialName("story_string_depth") val storyStringDepth: Int = 1,
    @SerialName("story_string_role") val storyStringRole: Int = 0,
    @SerialName("always_force_name2") val alwaysForceName2: Boolean = true,
    @SerialName("trim_sentences") val trimSentences: Boolean = false,
    @SerialName("single_line") val singleLine: Boolean = false,
    val extra: JsonObject = JsonObject(emptyMap()),
)
