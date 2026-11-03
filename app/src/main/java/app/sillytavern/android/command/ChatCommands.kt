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
        cmd("trigger", help = "trigger [member] - generates a reply (in a group: optionally by the given member).", usesPipe = false) { c ->
            val arg = c.unnamed.trim()
            if (arg.isNotEmpty() && c.host.groupMembers() != null) c.host.groupSpeak(GroupCommands.find(c.host, arg).avatar)
            else c.host.generateReply()
            c.pipe
        }
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
        fun mesId(c: CommandContext): Int {
            val n = c.host.messageCount()
            val id = (c.named["mes"] ?: c.unnamed.takeIf { it.isNotBlank() && it.trim().all(Char::isDigit) })?.trim()?.toIntOrNull() ?: (n - 1)
            if (id !in 0 until n) throw CommandError("Message $id not found")
            return id
        }
        cmd("branch-create", help = "branch-create [mesId] - copies the chat up to a message into a new branch and opens it; returns its name.", usesPipe = false) { c ->
            c.host.createBranch(mesId(c)).orEmpty()
        }
        cmd("checkpoint-create", "checkpoint", help = "checkpoint-create [mes=N] [name] - saves a checkpoint chat linked from the message; returns its name.", usesPipe = false) { c ->
            val id = c.named["mes"]?.trim()?.toIntOrNull() ?: (c.host.messageCount() - 1)
            if (id !in 0 until c.host.messageCount()) throw CommandError("Message $id not found")
            c.host.createCheckpoint(id, c.unnamed.takeIf { it.isNotBlank() }).orEmpty()
        }
        cmd("checkpoint-go", "checkpoint-open", help = "checkpoint-go [mesId] - opens the checkpoint linked from a message.", usesPipe = false) { c ->
            val id = mesId(c)
            if (!c.host.openCheckpoint(id)) throw CommandError("No checkpoint is linked to message $id")
            c.host.chatName()
        }
        cmd("checkpoint-exit", "checkpoint-close", help = "Returns to the chat this checkpoint or branch was made from.", usesPipe = false) { c ->
            if (c.host.mainChatName() == null) throw CommandError("This chat is not a checkpoint or branch")
            c.host.backToMain(); c.host.chatName()
        }
        cmd("checkpoint-get", help = "checkpoint-get [mesId] - name of the checkpoint linked from a message (empty if none).", usesPipe = false) { c ->
            c.host.checkpointOf(mesId(c)).orEmpty()
        }
        cmd("checkpoint-list", "checkpoints", help = "checkpoint-list [links=true] - message ids (or chat names) that have checkpoints.", usesPipe = false) { c ->
            val links = c.named["links"]?.lowercase() in setOf("true", "on", "1")
            c.host.checkpoints().joinToString(",") { if (links) it.second else it.first.toString() }
        }
        cmd("checkpoint-parent", "parent-chat", help = "Name of the chat this checkpoint or branch came from.", usesPipe = false) { c -> c.host.mainChatName().orEmpty() }
        cmd("persona", help = "persona name - switches persona.", usesPipe = false) { c ->
            if (!c.host.switchPersona(c.unnamed.trim())) throw CommandError("No persona named \"${c.unnamed.trim()}\""); c.unnamed.trim()
        }
    }
}
