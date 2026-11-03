package app.sillytavern.android.tools

import app.sillytavern.android.card.CharacterRepository
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.data.StoragePaths
import java.io.File
import java.time.Instant

/** Counts over a set of chats. */
data class UsageStats(
    val chats: Int = 0,
    val userMessages: Int = 0,
    val characterMessages: Int = 0,
    val userWords: Int = 0,
    val characterWords: Int = 0,
    /** Alternative replies generated beyond the first of each message. */
    val extraSwipes: Int = 0,
    val first: Instant? = null,
    val last: Instant? = null,
) {
    val messages get() = userMessages + characterMessages
    val avgCharacterWords get() = if (characterMessages == 0) 0 else characterWords / characterMessages
    val avgUserWords get() = if (userMessages == 0) 0 else userWords / userMessages

    operator fun plus(o: UsageStats) = UsageStats(
        chats + o.chats, userMessages + o.userMessages, characterMessages + o.characterMessages,
        userWords + o.userWords, characterWords + o.characterWords, extraSwipes + o.extraSwipes,
        listOfNotNull(first, o.first).minOrNull(), listOfNotNull(last, o.last).maxOrNull(),
    )
}

data class StatsRow(val name: String, val stats: UsageStats, val isGroup: Boolean = false)
data class StatsReport(val rows: List<StatsRow>, val total: UsageStats)

object Stats {
    private val ws = Regex("\\s+")

    fun words(text: String) = if (text.isBlank()) 0 else text.trim().split(ws).size

    /** Statistics of one chat's messages (system lines and hidden-from-prompt narration are not counted). */
    fun ofMessages(messages: List<ChatMessage>): UsageStats {
        var s = UsageStats(chats = 1)
        for (m in messages) {
            if (m.isSystem) continue
            val w = words(m.text)
            val t = parseDate(m.sendDate)
            s = if (m.isUser) s.copy(userMessages = s.userMessages + 1, userWords = s.userWords + w)
            else s.copy(characterMessages = s.characterMessages + 1, characterWords = s.characterWords + w, extraSwipes = s.extraSwipes + (m.swipes.size - 1).coerceAtLeast(0))
            if (t != null) s = s.copy(first = listOfNotNull(s.first, t).min(), last = listOfNotNull(s.last, t).max())
        }
        return s
    }

    /** SillyTavern writes ISO instants; older files hold `June 3, 2024 4:05pm`-style text, which is skipped. */
    fun parseDate(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    /** Reads every chat of every character and group in the data folder (unreadable files are skipped). */
    fun collect(paths: StoragePaths, chats: ChatRepository, characters: CharacterRepository): StatsReport {
        fun stats(files: List<File>) = files.mapNotNull { f -> runCatching { ofMessages(chats.load(f).messages) }.getOrNull() }.fold(UsageStats()) { a, b -> a + b }
        val names = characters.list().associate { it.fileName to it.card.name }
        val rows = ArrayList<StatsRow>()
        (paths.chats.listFiles { f -> f.isDirectory } ?: emptyArray()).forEach { dir ->
            val s = stats((dir.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()).toList())
            if (s.chats > 0) rows += StatsRow(names[dir.name] ?: dir.name, s)
        }
        val groupStats = stats((paths.groupChats.listFiles { f -> f.extension == "jsonl" } ?: emptyArray()).toList())
        if (groupStats.chats > 0) rows += StatsRow("Group chats", groupStats, isGroup = true)
        val sorted = rows.sortedByDescending { it.stats.messages }
        return StatsReport(sorted, sorted.fold(UsageStats()) { a, r -> a + r.stats })
    }
}
