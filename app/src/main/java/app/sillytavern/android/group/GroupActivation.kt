package app.sillytavern.android.group

import app.sillytavern.android.chat.ChatMessage
import kotlin.random.Random

/** A resolved group member as the activation logic sees it. */
data class Member(val avatar: String, val name: String, val talkativeness: Double = 0.5)

/** Ports of SillyTavern's `activate*Order` functions. All return member avatars in speaking order. */
object GroupActivation {
    private val word = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_']*")

    fun extractWords(s: String): List<String> = word.findAll(s.lowercase()).map { it.value }.toList()

    fun natural(
        members: List<Member>, input: String, last: ChatMessage?, allowSelf: Boolean, isUserInput: Boolean,
        random: Random = Random.Default,
    ): List<String> {
        if (members.isEmpty()) return emptyList()
        val banned = if (allowSelf) null else last?.takeIf { !isUserInput && !it.isUser }?.name
        val out = mutableListOf<String>()
        for (w in extractWords(input)) {
            val hit = members.firstOrNull { it.name != banned && w in extractWords(it.name) }
            if (hit != null) out += hit.avatar
        }
        for (m in members.shuffled(random)) {
            if (m.name == banned) continue
            if (m.talkativeness >= random.nextDouble()) out += m.avatar
        }
        if (out.isEmpty()) {
            val pool = members.filter { it.talkativeness > 0 }.ifEmpty { members }
            out += pool.random(random).avatar
        }
        return out.distinct()
    }

    fun list(members: List<Member>): List<String> = members.map { it.avatar }.distinct()

    fun pooled(members: List<Member>, chat: List<ChatMessage>, last: ChatMessage?, isUserInput: Boolean, random: Random = Random.Default): List<String> {
        if (members.isEmpty()) return emptyList()
        val spoken = mutableSetOf<String>()
        for (m in chat.asReversed()) {
            if (m.isUser || isUserInput) break
            if (m.isSystem || m.isNarrator) continue
            m.originalAvatar?.let(spoken::add)
        }
        val fresh = members.filter { it.avatar !in spoken }
        if (fresh.isNotEmpty()) return listOf(fresh.random(random).avatar)
        val lastAvatar = last?.takeIf { !it.isUser && members.size > 1 }?.originalAvatar
        val pool = if (lastAvatar != null) members.filter { it.avatar != lastAvatar } else members
        return listOf(pool.random(random).avatar)
    }

    fun manual(members: List<Member>, random: Random = Random.Default): List<String> =
        if (members.isEmpty()) emptyList() else listOf(members.random(random).avatar)

    fun impersonate(members: List<Member>, random: Random = Random.Default) = manual(members, random)

    /**
     * Speaker for swipe / continue / regenerate: the author of the last reply. [members] are the group's
     * avatars (muted ones included); an author who left the group yields an empty list.
     */
    fun swipe(members: List<Member>, chat: List<ChatMessage>, allowSystem: Boolean = false, random: Random = Random.Default): List<String> {
        val last = chat.lastOrNull() ?: return emptyList()
        fun skip(m: ChatMessage) = m.isUser || (!allowSystem && m.isSystem) || m.isNarrator
        val avatars = members.map { it.avatar }.toSet()
        val found: String? = if (skip(last)) {
            chat.asReversed().firstOrNull { !skip(it) && it.originalAvatar != null }?.originalAvatar
                ?: members.takeIf { it.isNotEmpty() }?.random(random)?.avatar
        } else last.originalAvatar ?: members.firstOrNull { it.name == last.name }?.avatar
        return listOfNotNull(found).filter { it in avatars }
    }
}
