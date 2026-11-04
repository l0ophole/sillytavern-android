package app.sillytavern.android.quickreply

import app.sillytavern.android.command.*
import app.sillytavern.android.data.AppSettings

/** What commands and the UI need from Quick Replies. */
interface QuickReplyAccess {
    fun setNames(): List<String>
    fun load(name: String): QuickReplySet?
    fun save(set: QuickReplySet)
    fun activeSets(): List<QuickReplySet>
    fun find(ref: String, setName: String? = null): Pair<QuickReplySet, QuickReply>?
    fun setGlobal(name: String, on: Boolean)
    fun setChat(name: String?)
}

data class QuickReplyOutcome(val textToSend: String?, val inputText: String?, val error: String?)

/**
 * Resolves active sets (global + chat + character, deduped) and runs Quick Replies.
 * [chatSet]/[setChatSet] read and write the per-chat set name (chat metadata).
 */
class QuickReplyService(
    private val repo: QuickReplyRepository,
    private val settings: () -> AppSettings,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val chatSet: () -> String?,
    private val setChatSet: (String?) -> Unit,
    private val characterKey: () -> String?,
    private val automationIds: () -> Set<String> = { emptySet() },
) : QuickReplyAccess {
    override fun setNames() = repo.list()
    override fun load(name: String) = repo.load(name)
    override fun save(set: QuickReplySet) = repo.save(set)

    override fun activeSets(): List<QuickReplySet> {
        val st = settings().quickReplies
        val names = buildList {
            if (st.globalEnabled) addAll(st.globalSets)
            if (st.chatEnabled) {
                chatSet()?.let { add(it) }
                characterKey()?.let { k -> st.characterSets[k]?.let { add(it) } }
            }
        }.distinct()
        return names.mapNotNull(repo::load)
    }

    /** `Set.Label`, a bare label (searched in active sets), or a 0-based index when [setName] is given. */
    override fun find(ref: String, setName: String?): Pair<QuickReplySet, QuickReply>? {
        val r = ref.trim()
        if (setName != null) {
            val set = repo.load(setName) ?: return null
            val qr = set.qrList.firstOrNull { it.label == r } ?: r.toIntOrNull()?.let { set.qrList.getOrNull(it) } ?: return null
            return set to qr
        }
        if ('.' in r) {
            val set = repo.load(r.substringBefore('.'))
            val qr = set?.qrList?.firstOrNull { it.label == r.substringAfter('.') }
            if (set != null && qr != null) return set to qr
        }
        for (set in activeSets()) set.qrList.firstOrNull { it.label == r }?.let { return set to it }
        return null
    }

    override fun setGlobal(name: String, on: Boolean) = updateSettings { s ->
        val cur = s.quickReplies.globalSets
        s.copy(quickReplies = s.quickReplies.copy(globalSets = if (on) (cur + name).distinct() else cur - name))
    }

    override fun setChat(name: String?) = setChatSet(name)

    /** Visible (non-hidden) QRs of active sets, for the button bar. */
    fun bar(): List<Pair<QuickReplySet, QuickReply>> = activeSets().flatMap { s -> s.qrList.filterNot { it.isHidden }.map { s to it } }

    /** Runs `set.label` inside an executor (used by `/run` and `/qr`); null when it does not exist. */
    suspend fun runStored(ref: String, executor: CommandExecutor, setName: String? = null): String? {
        val (_, qr) = find(ref, setName) ?: return null
        if (!qr.message.trimStart().startsWith("/")) return qr.message
        return executor.callText(qr.message.replace(Regex("\\{\\{input\\}\\}", RegexOption.IGNORE_CASE), ""), CommandScope(null), "")
    }

    /**
     * Applies a button press with SillyTavern's rules: inject the input box content, then either run it as a
     * command, put it into the input box (disableSend) or return it for sending.
     */
    suspend fun run(
        set: QuickReplySet, qr: QuickReply, currentInput: String, executor: CommandExecutor,
        expand: (String) -> String, isAutoExecute: Boolean = false, isRun: Boolean = false,
    ): QuickReplyOutcome {
        val msg = qr.message
        if (msg.isBlank()) return QuickReplyOutcome(null, null, null)
        val input = if (!isAutoExecute && !isRun && set.injectInput && currentInput.isNotEmpty())
            (if (set.placeBeforeInput) "$msg $currentInput" else "$currentInput $msg") else msg
        if (input.trimStart().startsWith("/") && !set.disableSend) {
            val text = input.replace(Regex("\\{\\{input\\}\\}", RegexOption.IGNORE_CASE), currentInput)
            val r = executor.runText(text.trim())
            return QuickReplyOutcome(null, null, r.error)
        }
        val text = expand(input.replace(Regex("\\{\\{input\\}\\}", RegexOption.IGNORE_CASE), currentInput)).trim()
        return if (set.disableSend) QuickReplyOutcome(null, text, null) else QuickReplyOutcome(text, null, null)
    }

    /** QRs whose auto-execute flag for [trigger] is set, honoring `automationId`. */
    fun autoExecutable(trigger: Trigger): List<Pair<QuickReplySet, QuickReply>> {
        val ids = automationIds()
        return activeSets().flatMap { s -> s.qrList.map { s to it } }.filter { (_, q) ->
            val flag = when (trigger) {
                Trigger.STARTUP -> q.executeOnStartup; Trigger.USER -> q.executeOnUser; Trigger.AI -> q.executeOnAi
                Trigger.CHAT_CHANGE -> q.executeOnChatChange; Trigger.NEW_CHAT -> q.executeOnNewChat
            }
            flag && (q.automationId.isEmpty() || q.automationId in ids)
        }
    }

    enum class Trigger { STARTUP, USER, AI, CHAT_CHANGE, NEW_CHAT }
}
