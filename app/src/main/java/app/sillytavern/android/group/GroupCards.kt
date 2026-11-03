package app.sillytavern.android.group

import app.sillytavern.android.card.CharacterCard

data class GroupCardMember(val avatar: String, val name: String, val card: CharacterCard)

data class MergedCard(val description: String, val personality: String, val scenario: String, val mesExamples: String)

/** Combines member cards for APPEND / APPEND_DISABLED generation modes (`getGroupCharacterCardsLazy`). */
object GroupCards {
    /** Null in SWAP mode. [expand] resolves macros for a given member name. */
    fun merge(group: Group, members: List<GroupCardMember>, currentAvatar: String, expand: (String, String) -> String): MergedCard? {
        if (group.generationMode == GenerationMode.SWAP) return null
        fun field(fieldName: String, get: (CharacterCard) -> String, pre: (String) -> String = { it }): String =
            members.filter { m ->
                m.avatar in group.members &&
                    (!group.isMuted(m.avatar) || m.avatar == currentAvatar || group.generationMode == GenerationMode.APPEND_DISABLED)
            }.map { m ->
                val v = get(m.card).trim()
                if (v.isEmpty()) return@map ""
                fun tr(t: String) = if (t.isEmpty()) "" else expand(t.replace("<FIELDNAME>", fieldName, ignoreCase = true), m.name)
                tr(group.joinPrefix) + expand(pre(v).trim(), m.name) + tr(group.joinSuffix)
            }.filter { it.isNotEmpty() }.joinToString("\n")
        return MergedCard(
            description = field("Description", { it.description }),
            personality = field("Personality", { it.personality }),
            scenario = field("Scenario", { it.scenario }),
            mesExamples = field("Example Messages", { it.messageExample }) { if (it.startsWith("<START>")) it else "<START>\n$it" },
        )
    }
}
