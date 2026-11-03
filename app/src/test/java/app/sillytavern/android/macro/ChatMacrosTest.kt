package app.sillytavern.android.macro

import app.sillytavern.android.chat.ChatMacroView
import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import org.junit.Assert.*
import org.junit.Test

class ChatMacrosTest {
    private val msgs = listOf(
        ChatMessage.assistant("Bea", "greet"), ChatMessage.user("Al", "question"),
        ChatMessage.assistant("Bea", "answer").addSwipe("answer2"),
    )
    private fun x(s: String, m: List<ChatMessage>) = Macros.expand(s, MacroContext("Al", "Bea", chat = ChatMacroView(m)))

    @Test fun lastMessagesPicked() = assertEquals("answer2|question|answer2|2", x("{{lastMessage}}|{{lastUserMessage}}|{{lastCharMessage}}|{{lastMessageId}}", msgs))
    @Test fun emptyChatGivesEmpty() = assertEquals("[||||||]", x("[{{lastMessage}}|{{lastMessageId}}|{{lastUserMessage}}|{{lastCharMessage}}|{{lastSwipeId}}|{{currentSwipeId}}|{{allChatRange}}]", emptyList()))
    @Test fun swipeIds() = assertEquals("2 2", x("{{lastSwipeId}} {{currentSwipeId}}", msgs))
    @Test fun lastUserSkipsAssistant() = assertEquals("question", x("{{lastUserMessage}}", msgs + ChatMessage.assistant("Bea", "x")))
    @Test fun systemMessagesIgnoredForLastChar() = assertEquals("answer2", x("{{lastCharMessage}}", msgs + ChatMessage("Sys", false, isSystem = true, swipes = listOf("note"))))
    @Test fun rangeAndFirstIds() = assertEquals("0-2|0|0", x("{{allChatRange}}|{{firstDisplayedMessageId}}|{{firstIncludedMessageId}}", msgs))
    @Test fun inProgressPlaceholderExcluded() = assertEquals("question|1", x("{{lastMessage}}|{{lastMessageId}}", msgs.take(2) + ChatMessage.assistant("Bea", "")))
}
