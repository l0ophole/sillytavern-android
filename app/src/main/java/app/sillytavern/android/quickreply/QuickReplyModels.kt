package app.sillytavern.android.quickreply

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

/** SillyTavern Quick Reply (set file v2) keys, verbatim. */
@Serializable
data class QuickReply(
    val id: Int = 0,
    val icon: String = "",
    val showLabel: Boolean = false,
    val label: String = "",
    val title: String = "",
    val message: String = "",
    val contextList: JsonArray = JsonArray(emptyList()),
    val preventAutoExecute: Boolean = true,
    val isHidden: Boolean = false,
    val executeOnStartup: Boolean = false,
    val executeOnUser: Boolean = false,
    val executeOnAi: Boolean = false,
    val executeOnChatChange: Boolean = false,
    val executeOnGroupMemberDraft: Boolean = false,
    val executeOnNewChat: Boolean = false,
    val executeBeforeGeneration: Boolean = false,
    val automationId: String = "",
)

@Serializable
data class QuickReplySet(
    val version: Int = 2,
    val name: String = "",
    val disableSend: Boolean = false,
    val placeBeforeInput: Boolean = false,
    val injectInput: Boolean = false,
    val color: String = "rgba(0, 0, 0, 0)",
    val onlyBorderColor: Boolean = false,
    val qrList: List<QuickReply> = emptyList(),
    val idIndex: Int = 0,
) {
    /** Adds a QR with a fresh id. */
    fun withAdded(qr: QuickReply): QuickReplySet {
        val id = maxOf(idIndex, qrList.maxOfOrNull { it.id } ?: 0) + 1
        return copy(qrList = qrList + qr.copy(id = id), idIndex = id)
    }
}

/** App-level Quick Reply configuration. */
@Serializable
data class QuickReplySettings(
    val globalSets: List<String> = emptyList(),
    val globalEnabled: Boolean = true,
    val chatEnabled: Boolean = true,
    /** Character file name -> set name. */
    val characterSets: Map<String, String> = emptyMap(),
)
