package app.sillytavern.android.regex

import java.util.UUID
import kotlinx.serialization.json.*

/** Regex placements, as numbered by SillyTavern. */
object Placement {
    const val USER_INPUT = 1
    const val AI_OUTPUT = 2
    const val SLASH_COMMAND = 3
    const val WORLD_INFO = 5
    const val REASONING = 6
}

/** One regex script in SillyTavern's JSON shape. Unknown keys are preserved in [rest]. */
data class RegexScript(
    val id: String = UUID.randomUUID().toString(),
    val scriptName: String = "",
    val findRegex: String = "",
    val replaceString: String = "",
    val trimStrings: List<String> = emptyList(),
    val placement: List<Int> = emptyList(),
    val disabled: Boolean = false,
    val markdownOnly: Boolean = false,
    val promptOnly: Boolean = false,
    val runOnEdit: Boolean = true,
    /** 0 = none, 1 = raw macro substitution in the pattern, 2 = escaped. */
    val substituteRegex: Int = 0,
    val minDepth: Int? = null,
    val maxDepth: Int? = null,
    val rest: JsonObject = JsonObject(emptyMap()),
) {
    fun toJson(): JsonObject = buildJsonObject {
        rest.forEach { (k, v) -> put(k, v) }
        put("id", id); put("scriptName", scriptName); put("findRegex", findRegex); put("replaceString", replaceString)
        put("trimStrings", JsonArray(trimStrings.map(::JsonPrimitive)))
        put("placement", JsonArray(placement.map(::JsonPrimitive)))
        put("disabled", disabled); put("markdownOnly", markdownOnly); put("promptOnly", promptOnly); put("runOnEdit", runOnEdit)
        put("substituteRegex", substituteRegex)
        put("minDepth", minDepth?.let(::JsonPrimitive) ?: JsonNull)
        put("maxDepth", maxDepth?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        private val known = setOf("id", "scriptName", "findRegex", "replaceString", "trimStrings", "placement", "disabled",
            "markdownOnly", "promptOnly", "runOnEdit", "substituteRegex", "minDepth", "maxDepth")

        private fun JsonElement?.str() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        private fun JsonElement?.bool(def: Boolean) = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.intOrNull?.let { n -> n != 0 } } ?: def
        private fun JsonElement?.int() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { it.intOrNull ?: it.doubleOrNull?.toInt() ?: it.booleanOrNull?.let { b -> if (b) 1 else 0 } }

        /** Lenient: ST files have bools as numbers, missing keys, null depths. */
        fun fromJson(o: JsonObject) = RegexScript(
            id = o["id"].str() ?: UUID.randomUUID().toString(),
            scriptName = o["scriptName"].str().orEmpty(),
            findRegex = o["findRegex"].str().orEmpty(),
            replaceString = o["replaceString"].str().orEmpty(),
            trimStrings = (o["trimStrings"] as? JsonArray)?.mapNotNull { it.str() }.orEmpty(),
            placement = (o["placement"] as? JsonArray)?.mapNotNull { it.int() }.orEmpty(),
            disabled = o["disabled"].bool(false),
            markdownOnly = o["markdownOnly"].bool(false),
            promptOnly = o["promptOnly"].bool(false),
            runOnEdit = o["runOnEdit"].bool(true),
            substituteRegex = o["substituteRegex"].int() ?: 0,
            minDepth = o["minDepth"].int(),
            maxDepth = o["maxDepth"].int(),
            rest = JsonObject(o.filterKeys { it !in known }),
        )
    }
}
