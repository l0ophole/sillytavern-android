package app.sillytavern.android.data

import app.sillytavern.android.prompt.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

enum class PresetKind(val label: String, internal val requiredKey: String) {
    PROMPT_MANAGER("Prompt Manager", "prompts"),
    INSTRUCT("Instruct", "input_sequence"),
    CONTEXT("Context", "story_string"),
    SYSTEM_PROMPT("System prompt", "content"),
    SAMPLER("Sampler", "temp"),
}

/** Import/export of SillyTavern preset JSON into/from [AppSettings]; pure so it is unit-testable. */
object PresetIo {
    private fun <T> upsert(list: List<T>, item: T, name: (T) -> String): List<T> =
        if (list.any { name(it) == name(item) }) list.map { if (name(it) == name(item)) item else it } else list + item

    /** Returns the imported preset's name, or a failure with a user-presentable message (settings untouched). */
    fun importPreset(repo: SettingsRepository, kind: PresetKind, fileName: String, text: String): Result<String> = runCatching {
        val obj = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) { error("Not a valid JSON object") }
        require(obj.containsKey(kind.requiredKey)) { "This does not look like a ${kind.label.lowercase()} preset" }
        val base = fileName.substringAfterLast('/').removeSuffix(".json").ifBlank { "Imported" }
        val named = if (obj.containsKey("name")) text else Json.encodeToString(JsonObject.serializer(), JsonObject(obj + ("name" to kotlinx.serialization.json.JsonPrimitive(base))))
        when (kind) {
            PresetKind.PROMPT_MANAGER -> {
                val p = PresetJson.decodePromptManager(named)
                repo.update { it.copy(promptManagerPresets = upsert(it.promptManagerPresets, p) { x -> x.name }, activePromptManagerName = p.name) }; p.name
            }
            PresetKind.INSTRUCT -> {
                val p = PresetJson.decode(InstructTemplate.serializer(), named)
                repo.update { it.copy(instructTemplates = upsert(it.instructTemplates, p) { x -> x.name }, activeInstructName = p.name) }; p.name
            }
            PresetKind.CONTEXT -> {
                val p = PresetJson.decode(ContextTemplate.serializer(), named)
                repo.update { it.copy(contextTemplates = upsert(it.contextTemplates, p) { x -> x.name }, activeContextName = p.name) }; p.name
            }
            PresetKind.SYSTEM_PROMPT -> {
                val p = PresetJson.decode(SystemPromptPreset.serializer(), named)
                repo.update { it.copy(systemPrompts = upsert(it.systemPrompts, p) { x -> x.name }, activeSystemPromptName = p.name) }; p.name
            }
            PresetKind.SAMPLER -> {
                val p = PresetJson.decode(TextGenSamplerPreset.serializer(), named)
                repo.update { it.copy(samplerPresets = upsert(it.samplerPresets, p) { x -> x.name }, activeSamplerPresetName = p.name) }; p.name
            }
        }
    }.recoverCatching { throw IllegalArgumentException(it.message ?: "Import failed") }

    fun exportPreset(s: AppSettings, kind: PresetKind, name: String): String? = when (kind) {
        PresetKind.PROMPT_MANAGER -> s.promptManagerPresets.firstOrNull { it.name == name }?.let(PresetJson::encodePromptManager)
        PresetKind.INSTRUCT -> s.instructTemplates.firstOrNull { it.name == name }?.let { PresetJson.encode(InstructTemplate.serializer(), it) }
        PresetKind.CONTEXT -> s.contextTemplates.firstOrNull { it.name == name }?.let { PresetJson.encode(ContextTemplate.serializer(), it) }
        PresetKind.SYSTEM_PROMPT -> s.systemPrompts.firstOrNull { it.name == name }?.let { PresetJson.encode(SystemPromptPreset.serializer(), it) }
        PresetKind.SAMPLER -> s.samplerPresets.firstOrNull { it.name == name }?.let { PresetJson.encode(TextGenSamplerPreset.serializer(), it) }
    }
}
