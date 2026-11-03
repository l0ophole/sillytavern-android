package app.sillytavern.android.command

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.macro.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecordingHost : CommandHost {
    override val variables = VariableStore.inMemory()
    val calls = mutableListOf<String>()
    var chat = "A: hi\nB: yo"
    override fun macroContext() = MacroContext("Al", "Bea", vars = variables)
    override suspend fun echo(text: String, severity: String) { calls += "echo:$text" }
    override suspend fun sendUser(text: String) { calls += "send:$text" }
    override suspend fun addSystemMessage(text: String) { calls += "sys:$text" }
    override suspend fun addMessageAs(name: String, text: String) { calls += "as:$name:$text" }
    override suspend fun generateReply() { calls += "trigger" }
    override suspend fun continueLast() { calls += "continue" }
    override suspend fun regenerate() { calls += "regen" }
    override suspend fun swipeBy(delta: Int) { calls += "swipe:$delta" }
    override suspend fun impersonate(): String { calls += "imp"; return "imp text" }
    override suspend fun deleteMessages(range: IntRange) { calls += "del:${range.first}-${range.last}" }
    override suspend fun hideMessages(range: IntRange, hidden: Boolean) { calls += "hide:$hidden:${range.first}-${range.last}" }
    override suspend fun setInput(text: String) { calls += "input:$text" }
    override fun messageCount() = 10
    override fun chatText(range: IntRange?, names: Boolean) = chat
    override suspend fun generateQuiet(prompt: String, useHistory: Boolean): String { calls += "gen:$useHistory:$prompt"; return "generated" }
    override suspend fun newChat() { calls += "newchat" }
    override suspend fun renameChat(name: String) { calls += "rename:$name" }
    override fun chatName() = "chat1"
    override suspend fun setChatBook(name: String?) { calls += "book:$name" }
    override suspend fun switchPersona(name: String): Boolean { calls += "persona:$name"; return name == "Al" }
}

class ChatCommandsTest {
    private val host = RecordingHost()
    private fun run(t: String) = runBlocking { CommandExecutor(host).runText(t) }

    @Test fun sysAddsSystemMessage() { run("/sys the room is cold"); assertEquals(listOf("sys:the room is cold"), host.calls) }
    @Test fun sendWithoutGenerate() { run("/send hello"); assertEquals(listOf("send:hello"), host.calls) }
    @Test fun trigger() { run("/send hi | /trigger"); assertEquals(listOf("send:hi", "trigger"), host.calls) }
    @Test fun sendasName() { run("""/sendas name="Bob" hey"""); assertEquals(listOf("as:Bob:hey"), host.calls) }
    @Test fun deleteLastN() { run("/delete 2"); assertEquals(listOf("del:8-9"), host.calls) }
    @Test fun cutRange() { run("/cut 3-5"); assertEquals(listOf("del:3-5"), host.calls); run("/cut 7"); assertEquals("del:7-7", host.calls.last()) }
    @Test fun hideUnhideRange() { run("/hide 1-2 | /unhide 4"); assertEquals(listOf("hide:true:1-2", "hide:false:4-4"), host.calls) }
    @Test fun outOfRangeIndexIgnored() { run("/cut 99"); run("/hide abc"); assertTrue(host.calls.none { it.startsWith("del:99") }) }
    @Test fun setInput() { run("/setinput some text"); assertEquals(listOf("input:some text"), host.calls) }
    @Test fun genReturnsTextAndPipes() { run("/gen write a haiku | /setvar key=h"); assertEquals("generated", host.variables.get("h", false)); assertEquals("gen:true:write a haiku", host.calls[0]) }
    @Test fun genRawNoHistory() { assertEquals("generated", run("/genraw say hi").pipe); assertEquals("gen:false:say hi", host.calls.single()) }
    @Test fun swipeAndContinue() { run("/swipe right | /swipe left | /continue | /regenerate"); assertEquals(listOf("swipe:1", "swipe:-1", "continue", "regen"), host.calls) }
    @Test fun impersonateReturnsText() { assertEquals("imp text", run("/impersonate").pipe) }
    @Test fun messagesReturnsChatText() { assertEquals("A: hi\nB: yo", run("/messages").pipe) }
    @Test fun worldAndChatName() { run("/world Lore"); run("/world"); assertEquals(listOf("book:Lore", "book:null"), host.calls); assertEquals("chat1", run("/getchatname").pipe) }
    @Test fun personaSwitchFailsForUnknown() { assertNull(run("/persona Al").error); assertNotNull(run("/persona Zed").error) }
    @Test fun renameChat() { run("/renamechat New name"); assertEquals(listOf("rename:New name"), host.calls) }
}
