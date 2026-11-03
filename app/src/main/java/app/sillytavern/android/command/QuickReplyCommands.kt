package app.sillytavern.android.command

import app.sillytavern.android.quickreply.QuickReply
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

object QuickReplyCommands {
    private fun access(c: CommandContext) = c.host.quickReplies ?: throw CommandError("Quick Replies are not available here")
    private fun list(items: List<String>) = JsonArray(items.map(::JsonPrimitive)).toString()

    fun register() {
        cmd("qr", help = "qr set=Name label|index - runs a Quick Reply (or Set.Label).", usesPipe = false) { c ->
            val ref = c.unnamed.trim().ifEmpty { throw CommandError("Quick Reply label required") }
            val svc = c.host.quickReplies as? app.sillytavern.android.quickreply.QuickReplyService
                ?: throw CommandError("Quick Replies are not available here")
            svc.runStored(ref, c.executor, c.named["set"]) ?: throw CommandError("No Quick Reply \"$ref\"")
        }
        cmd("qr-list", help = "qr-list Set - labels as JSON array.") { c ->
            list((access(c).load(c.unnamed.trim()) ?: throw CommandError("No set \"${c.unnamed.trim()}\"")).qrList.map { it.label })
        }
        cmd("qr-set-list", help = "Names of all sets.", usesPipe = false) { c -> list(access(c).setNames()) }
        cmd("qr-set-on", help = "Enable a set globally.") { c -> access(c).setGlobal(c.unnamed.trim(), true); "" }
        cmd("qr-set-off", help = "Disable a global set.") { c -> access(c).setGlobal(c.unnamed.trim(), false); "" }
        cmd("qr-chat-set", help = "Use a set for this chat.") { c -> access(c).setChat(c.unnamed.trim().ifEmpty { null }); "" }
        cmd("qr-chat-set-off", help = "Stop using the chat's set.", usesPipe = false) { c -> access(c).setChat(null); "" }
        cmd("qr-create", help = "qr-create set= label= message") { c ->
            val a = access(c); val name = c.named["set"] ?: throw CommandError("set= required")
            val set = a.load(name) ?: throw CommandError("No set \"$name\"")
            a.save(set.withAdded(QuickReply(label = c.named["label"].orEmpty(), message = c.unnamed, title = c.named["title"].orEmpty()))); ""
        }
        cmd("qr-update", help = "qr-update set= label= [newlabel=] message") { c ->
            val a = access(c); val name = c.named["set"] ?: throw CommandError("set= required")
            val set = a.load(name) ?: throw CommandError("No set \"$name\"")
            val label = c.named["label"] ?: throw CommandError("label= required")
            val idx = set.qrList.indexOfFirst { it.label == label }.takeIf { it >= 0 } ?: throw CommandError("No Quick Reply \"$label\"")
            val old = set.qrList[idx]
            val upd = old.copy(label = c.named["newlabel"] ?: old.label, message = c.unnamed.ifEmpty { old.message }, title = c.named["title"] ?: old.title)
            a.save(set.copy(qrList = set.qrList.toMutableList().also { it[idx] = upd })); ""
        }
        cmd("qr-delete", help = "qr-delete set= label", usesPipe = false) { c ->
            val a = access(c); val name = c.named["set"] ?: throw CommandError("set= required")
            val set = a.load(name) ?: throw CommandError("No set \"$name\"")
            val label = c.unnamed.trim().ifEmpty { c.named["label"].orEmpty() }
            a.save(set.copy(qrList = set.qrList.filterNot { it.label == label })); ""
        }
    }
}
