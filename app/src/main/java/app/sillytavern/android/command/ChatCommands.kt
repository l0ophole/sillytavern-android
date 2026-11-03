package app.sillytavern.android.command

object ChatCommands {
    /** "3" or "2-5" -> range clamped to existing messages; null when unparsable or empty after clamping. */
    fun range(text: String, count: Int): IntRange? {
        val m = Regex("""^\s*(\d+)\s*(?:-\s*(\d+))?\s*$""").matchEntire(text) ?: return null
        val a = m.groupValues[1].toInt(); val b = m.groupValues[2].ifEmpty { m.groupValues[1] }.toInt()
        val r = minOf(a, b)..minOf(maxOf(a, b), count - 1)
        return r.takeIf { !it.isEmpty() && it.first < count }
    }

    fun register() {
        cmd("sys", "nar", help = "Adds a narrator (system) message.") { c -> c.host.addSystemMessage(c.unnamed); c.unnamed }
        cmd("send", help = "Adds a user message without generating a reply.") { c -> c.host.sendUser(c.unnamed); c.unnamed }
        cmd("sendas", help = "sendas name=X text - adds a message from another character.") { c ->
            c.host.addMessageAs(c.named["name"] ?: throw CommandError("name= required"), c.unnamed); c.unnamed
        }
        cmd("trigger", help = "Generates a reply.", usesPipe = false) { c -> c.host.generateReply(); c.pipe }
        cmd("continue", "cont", help = "Continues the last reply.", usesPipe = false) { c -> c.host.continueLast(); c.pipe }
        cmd("regenerate", "regen", help = "Regenerates the last reply as a new swipe.", usesPipe = false) { c -> c.host.regenerate(); c.pipe }
        cmd("swipe", help = "swipe left|right", usesPipe = false) { c ->
            when (c.unnamed.trim().lowercase()) { "left" -> c.host.swipeBy(-1); "right", "" -> c.host.swipeBy(1); else -> throw CommandError("Use left or right") }
            c.pipe
        }
        cmd("impersonate", "imp", help = "Writes the next user message with the model.", usesPipe = false) { c -> c.host.impersonate() }
        cmd("delete", "del", help = "delete N - removes the last N messages.") { c ->
            val n = c.unnamed.trim().toIntOrNull()?.coerceAtMost(c.host.messageCount()) ?: 1
            if (n > 0) c.host.deleteMessages((c.host.messageCount() - n) until c.host.messageCount())
            c.pipe
        }
        cmd("cut", help = "cut N or N-M - removes messages by id.", usesPipe = false) { c ->
            range(c.unnamed, c.host.messageCount())?.let { c.host.deleteMessages(it) }; c.pipe
        }
        cmd("hide", help = "hide N or N-M - excludes messages from the prompt.", usesPipe = false) { c ->
            range(c.unnamed, c.host.messageCount())?.let { c.host.hideMessages(it, true) }; c.pipe
        }
        cmd("unhide", help = "unhide N or N-M", usesPipe = false) { c ->
            range(c.unnamed, c.host.messageCount())?.let { c.host.hideMessages(it, false) }; c.pipe
        }
        cmd("setinput", help = "Puts text into the input box.") { c -> c.host.setInput(c.unnamed); c.unnamed }
        cmd("gen", help = "gen prompt - generates text using the chat as context (not added to the chat).") { c ->
            c.host.generateQuiet(c.unnamed, true)
        }
        cmd("genraw", help = "genraw prompt - generates text with no chat context.") { c -> c.host.generateQuiet(c.unnamed, false) }
        cmd("messages", help = "messages names=on|off range - returns chat text.", usesPipe = false) { c ->
            val r = c.unnamed.takeIf { it.isNotBlank() }?.let { range(it, c.host.messageCount()) ?: return@cmd "" }
            c.host.chatText(r, c.named["names"]?.lowercase() != "off")
        }
        cmd("newchat", help = "Starts a new chat.", usesPipe = false) { c -> c.host.newChat(); c.pipe }
        cmd("renamechat", help = "renamechat name", usesPipe = false) { c -> c.host.renameChat(c.unnamed.trim()); c.pipe }
        cmd("getchatname", help = "Returns the chat file name.", usesPipe = false) { c -> c.host.chatName() }
        cmd("world", help = "world name - binds a lorebook to this chat (no name clears it).", usesPipe = false) { c ->
            val n = c.unnamed.trim().takeIf { it.isNotEmpty() && !it.equals("none", true) }
            c.host.setChatBook(n); n.orEmpty()
        }
        cmd("persona", help = "persona name - switches persona.", usesPipe = false) { c ->
            if (!c.host.switchPersona(c.unnamed.trim())) throw CommandError("No persona named \"${c.unnamed.trim()}\""); c.unnamed.trim()
        }
    }
}
