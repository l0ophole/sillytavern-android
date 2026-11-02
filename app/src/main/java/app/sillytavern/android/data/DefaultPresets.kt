package app.sillytavern.android.data

import app.sillytavern.android.prompt.model.*

/** Built-in presets mirroring SillyTavern's defaults (default/content/presets). */
object DefaultPresets {
    private fun marker(id: String, name: String) = PromptDefinition(id, name, systemPrompt = true, marker = true)

    fun promptManager(name: String = "Default"): PromptManagerPreset = PromptManagerPreset(
        name = name,
        prompts = listOf(
            PromptDefinition("main", "Main Prompt", content = "Write {{char}}'s next reply in a fictional chat between {{char}} and {{user}}.", systemPrompt = true),
            PromptDefinition("nsfw", "Auxiliary Prompt", systemPrompt = true),
            marker("dialogueExamples", "Chat Examples"),
            PromptDefinition("jailbreak", "Post-History Instructions", systemPrompt = true),
            marker("chatHistory", "Chat History"),
            marker("worldInfoAfter", "World Info (after)"),
            marker("worldInfoBefore", "World Info (before)"),
            PromptDefinition(
                "enhanceDefinitions", "Enhance Definitions", systemPrompt = true,
                content = "If you have more knowledge of {{char}}, add to the character's lore and personality to enhance them but keep the Character Sheet's definitions absolute.",
            ),
            marker("charDescription", "Char Description"),
            marker("charPersonality", "Char Personality"),
            marker("scenario", "Scenario"),
            marker("personaDescription", "Persona Description"),
        ),
        order = listOf(
            "main" to true, "worldInfoBefore" to true, "personaDescription" to true, "charDescription" to true,
            "charPersonality" to true, "scenario" to true, "enhanceDefinitions" to false, "nsfw" to true,
            "worldInfoAfter" to true, "dialogueExamples" to true, "chatHistory" to true, "jailbreak" to true,
        ).map { PromptOrderEntry(it.first, it.second) },
    )
}
