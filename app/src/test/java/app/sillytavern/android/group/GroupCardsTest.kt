package app.sillytavern.android.group

import app.sillytavern.android.card.CharacterCard
import org.junit.Assert.*
import org.junit.Test

class GroupCardsTest {
    private val a = GroupCardMember("A.png", "Ann", CharacterCard(name = "Ann", description = "Tall", scenario = "", messageExample = "ex A"))
    private val b = GroupCardMember("B.png", "Ben", CharacterCard(name = "Ben", description = "Short", messageExample = "<START>\nex B"))
    private fun group(mode: Int, muted: List<String> = emptyList(), pre: String = "", suf: String = "") =
        Group(id = "1", name = "G", members = listOf("A.png", "B.png"), disabledMembers = muted, generationMode = mode, chatId = "c", joinPrefix = pre, joinSuffix = suf)
    private val id: (String, String) -> String = { t, n -> t.replace("{{char}}", n) }

    @Test fun swapReturnsNull() = assertNull(GroupCards.merge(group(GenerationMode.SWAP), listOf(a, b), "A.png", id))

    @Test fun appendSkipsDisabledExceptCurrent() {
        val g = group(GenerationMode.APPEND, muted = listOf("B.png"))
        assertEquals("Tall", GroupCards.merge(g, listOf(a, b), "A.png", id)!!.description)
        assertEquals("Tall\nShort", GroupCards.merge(g, listOf(a, b), "B.png", id)!!.description)
    }

    @Test fun appendDisabledIncludesAll() =
        assertEquals("Tall\nShort", GroupCards.merge(group(GenerationMode.APPEND_DISABLED, muted = listOf("B.png")), listOf(a, b), "A.png", id)!!.description)

    @Test fun fieldNamePlaceholderInPrefix() {
        val m = GroupCards.merge(group(GenerationMode.APPEND, pre = "[{{char}} <fieldname>: ", suf = "]"), listOf(a, b), "A.png", id)!!
        assertEquals("[Ann Description: Tall]\n[Ben Description: Short]", m.description)
    }

    @Test fun examplesGetStartMarker() =
        assertEquals("<START>\nex A\n<START>\nex B", GroupCards.merge(group(GenerationMode.APPEND), listOf(a, b), "A.png", id)!!.mesExamples)

    @Test fun blankFieldsSkipped() = assertEquals("", GroupCards.merge(group(GenerationMode.APPEND), listOf(a, b), "A.png", id)!!.scenario)
}
