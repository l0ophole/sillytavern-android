package app.sillytavern.android.command

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.macro.VariableStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GroupCommandsTest {
    private class Host(var members: List<GroupMemberInfo>?) : CommandHost {
        override val variables = VariableStore.inMemory()
        val calls = mutableListOf<String>()
        override fun macroContext() = MacroContext("Me", "Ann", vars = variables)
        override suspend fun echo(text: String, severity: String) {}
        override suspend fun groupMembers() = members
        override suspend fun groupSetEnabled(avatar: String, enabled: Boolean) { calls += "enabled:$avatar:$enabled" }
        override suspend fun groupAdd(characterName: String) = (characterName == "Cy").also { if (it) calls += "add:$characterName" }
        override suspend fun groupRemove(avatar: String) { calls += "remove:$avatar" }
        override suspend fun groupMove(avatar: String, delta: Int) { calls += "move:$avatar:$delta" }
        override suspend fun groupSpeak(avatar: String) { calls += "speak:$avatar" }
        override suspend fun generateReply() { calls += "trigger" }
    }
    private val two = listOf(GroupMemberInfo("Ann", "Ann.png", true), GroupMemberInfo("Ben", "Ben.png", false))
    private fun run(h: Host, t: String) = runBlocking { CommandExecutor(h).runText(t) }

    @Test fun memberCountAndGet() {
        val h = Host(two)
        assertEquals("2", run(h, "/member-count").pipe)
        assertEquals("Ben", run(h, "/member-get 1").pipe)
        assertEquals("1", run(h, "/member-get field=index ben").pipe)
        assertEquals("Ann.png", run(h, "/member-get field=avatar Ann").pipe)
    }

    @Test fun enableDisable() {
        val h = Host(two)
        run(h, "/member-disable Ann"); run(h, "/member-enable 1")
        assertEquals(listOf("enabled:Ann.png:false", "enabled:Ben.png:true"), h.calls)
    }

    @Test fun addRemoveMove() {
        val h = Host(two)
        run(h, "/member-add Cy"); run(h, "/member-remove Ben"); run(h, "/member-up Ben"); run(h, "/member-down 0")
        assertEquals(listOf("add:Cy", "remove:Ben.png", "move:Ben.png:-1", "move:Ann.png:1"), h.calls)
    }

    @Test fun outsideGroupErrors() {
        val r = run(Host(null), "/member-count")
        assertTrue(r.error!!, r.error!!.contains("group"))
    }

    @Test fun unknownMemberErrors() {
        assertNotNull(run(Host(two), "/member-disable Zed").error)
        assertNotNull(run(Host(two), "/member-add Nobody").error)
    }

    @Test fun triggerWithNameSpeaksMember() {
        val h = Host(two)
        run(h, "/trigger Ben"); run(h, "/trigger")
        assertEquals(listOf("speak:Ben.png", "trigger"), h.calls)
    }

    @Test fun triggerOutsideGroupStillGenerates() {
        val h = Host(null); run(h, "/trigger"); assertEquals(listOf("trigger"), h.calls)
    }
}
