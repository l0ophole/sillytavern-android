package app.sillytavern.android.macro

import app.sillytavern.android.chat.ChatMessage

object ChatMacros {
    /** Messages as the user sees them, minus a trailing empty placeholder being generated. */
    private fun visible(c: MacroCall): List<ChatMessage>? =
        c.ctx.chat?.messages?.let { m -> if (m.lastOrNull()?.text.isNullOrEmpty() && m.isNotEmpty()) m.dropLast(1) else m }

    fun register() {
        fun reg(name: String, f: (List<ChatMessage>, MacroCall) -> String) =
            MacroRegistry.register(MacroDef(listOf(name), 0, 0) { c -> visible(c)?.let { f(it, c) } ?: "" })
        reg("lastMessage") { m, _ -> m.lastOrNull()?.text.orEmpty() }
        reg("lastMessageId") { m, _ -> if (m.isEmpty()) "" else (m.size - 1).toString() }
        reg("lastUserMessage") { m, _ -> m.lastOrNull { it.isUser && !it.isSystem }?.text.orEmpty() }
        reg("lastCharMessage") { m, _ -> m.lastOrNull { !it.isUser && !it.isSystem }?.text.orEmpty() }
        reg("firstIncludedMessageId") { m, c -> if (m.isEmpty()) "" else (c.ctx.chat?.firstIncludedIndex ?: 0).toString() }
        reg("firstDisplayedMessageId") { m, _ -> if (m.isEmpty()) "" else "0" }
        reg("lastSwipeId") { m, _ -> m.lastOrNull()?.swipes?.size?.toString().orEmpty() }
        reg("currentSwipeId") { m, _ -> m.lastOrNull()?.let { (it.swipeId + 1).toString() }.orEmpty() }
        reg("allChatRange") { m, _ -> if (m.isEmpty()) "" else "0-${m.size - 1}" }
    }
}
