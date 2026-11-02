package app.sillytavern.android.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One Prompt Manager entry; markers (chatHistory, charDescription, ...) have no content of their own. */
@Serializable
data class PromptDefinition(
    val identifier: String,
    val name: String = "",
    val role: String = "system",
    val content: String = "",
    @SerialName("system_prompt") val systemPrompt: Boolean = false,
    val marker: Boolean = false,
    @SerialName("injection_position") val injectionPosition: Int = 0,
    @SerialName("injection_depth") val injectionDepth: Int = 4,
    @SerialName("injection_order") val injectionOrder: Int = 100,
    @SerialName("forbid_overrides") val forbidOverrides: Boolean = false,
)

@Serializable
data class PromptOrderEntry(val identifier: String, val enabled: Boolean = true)

/** Chat-completion prompt preset (default/content/presets/openai), reduced to prompt-assembly fields. */
@Serializable
data class PromptManagerPreset(
    val name: String = "Default",
    val prompts: List<PromptDefinition> = emptyList(),
    /** Derived from ST's `prompt_order` (see [PresetJson]); serialized under `order` internally only. */
    val order: List<PromptOrderEntry> = emptyList(),
    @SerialName("impersonation_prompt") val impersonationPrompt: String =
        "[Write your next reply from the point of view of {{user}}, using the chat history so far as a guideline for the writing style of {{user}}. Don't write as {{char}} or system. Don't describe actions of {{char}}.]",
    @SerialName("new_chat_prompt") val newChatPrompt: String = "[Start a new Chat]",
    @SerialName("new_example_chat_prompt") val newExampleChatPrompt: String = "[Example Chat]",
    @SerialName("continue_nudge_prompt") val continueNudgePrompt: String =
        "[Continue your last message without repeating its original content.]",
    @SerialName("group_nudge_prompt") val groupNudgePrompt: String = "[Write the next reply only as {{char}}.]",
    @SerialName("send_if_empty") val sendIfEmpty: String = "",
    @SerialName("names_behavior") val namesBehavior: Int = 0,
    @SerialName("squash_system_messages") val squashSystemMessages: Boolean = false,
    @SerialName("scenario_format") val scenarioFormat: String = "{{scenario}}",
    @SerialName("personality_format") val personalityFormat: String = "{{personality}}",
    @SerialName("wi_format") val wiFormat: String = "{0}",
    val extra: JsonObject = JsonObject(emptyMap()),
)
