package app.sillytavern.android.prompt.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SystemPromptPreset(
    val name: String = "Default",
    val content: String = "",
    @SerialName("post_history") val postHistory: String = "",
    val extra: JsonObject = JsonObject(emptyMap()),
)
