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

    private fun resource(name: String): String =
        DefaultPresets::class.java.getResourceAsStream("/presets/$name.json")!!.bufferedReader().use { it.readText() }

    private fun <T> bundle(prefix: String, names: List<String>, decode: (String) -> T): List<T> =
        names.map { decode(resource("$prefix-$it")) }

    fun instructTemplates() = bundle("instruct", listOf("ChatML", "Alpaca", "Llama3", "MistralV7")) {
        PresetJson.decode(InstructTemplate.serializer(), it)
    }

    fun contextTemplates() = bundle("context", listOf("Default", "ChatML", "Alpaca", "Llama3", "MistralV7")) {
        PresetJson.decode(ContextTemplate.serializer(), it)
    }

    fun systemPrompts() = bundle("sysprompt", listOf("RoleplaySimple", "RoleplayDetailed", "NeutralChat", "Blank")) {
        PresetJson.decode(SystemPromptPreset.serializer(), it)
    }

    /** Sampler preset files carry no name; ST names them after the file. */
    fun samplerPresets() = listOf("Default", "Deterministic", "Neutral", "Universal-Creative", "Universal-Light").map {
        PresetJson.decode(TextGenSamplerPreset.serializer(), resource("textgen-$it")).copy(name = it)
    }

    /** MVP [PromptPreset] -> Prompt Manager preset, keeping the user's custom text. */
    fun fromLegacy(p: PromptPreset): PromptManagerPreset {
        val base = promptManager(p.name)
        return base.copy(prompts = base.prompts.map {
            when (it.identifier) {
                "main" -> it.copy(content = p.mainPrompt)
                "nsfw" -> it.copy(content = p.nsfwPrompt)
                "jailbreak" -> it.copy(content = p.jailbreak)
                else -> it
            }
        })
    }
}
