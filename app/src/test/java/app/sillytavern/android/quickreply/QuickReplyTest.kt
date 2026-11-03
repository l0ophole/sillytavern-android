package app.sillytavern.android.quickreply

import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.command.*
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.macro.VariableStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuickReplyTest {
    private val repo = QuickReplyRepository(StoragePaths(Files.createTempDirectory("qr").toFile()).ensure())
    private var settings = AppSettings()
    private var chatSet: String? = null
    private val svc = QuickReplyService(repo, { settings }, { f -> settings = f(settings) }, { chatSet }, { chatSet = it }, { "Ann.png" }, { setOf("A1") })
    private val sample get() = javaClass.getResource("/quickreply/sample.json")!!.readText()

    private inner class H : CommandHost {
        override val variables = VariableStore.inMemory()
        val sys = mutableListOf<String>()
        override fun macroContext() = MacroContext("Al", "Bea", vars = variables)
        override suspend fun echo(text: String, severity: String) { sys += "echo:$text" }
        override suspend fun addSystemMessage(text: String) { sys += text }
        override val quickReplies get() = svc
        override suspend fun runStoredScript(name: String, executor: CommandExecutor) = svc.runStored(name, executor)
    }
    private val host = H()
    private fun exec() = CommandExecutor(host)
    private fun run(t: String) = runBlocking { exec().runText(t) }
    private fun set(name: String = "S", vararg qrs: QuickReply, block: QuickReplySet.() -> QuickReplySet = { this }) =
        QuickReplySet(name = name).block().let { s -> qrs.fold(s) { a, q -> a.withAdded(q) } }.also(repo::save)

    @Test fun setJsonRoundTripKeepsAllFields() {
        val s = repo.importJson(sample)
        val back = repo.load("Tools")!!
        assertEquals(s, back); assertTrue(back.placeBeforeInput && back.injectInput && back.onlyBorderColor)
        assertEquals(2, back.qrList.size); assertEquals("A1", back.qrList[1].automationId); assertTrue(back.qrList[1].contextList.size == 1)
        assertTrue(repo.exportJson(back).contains("\"executeBeforeGeneration\""))
    }
    @Test fun importNameCollisionRenamed() { repo.importJson(sample); assertEquals("Tools (2)", repo.importJson(sample).name) }
    @Test fun runPlainMessageSends() {
        val s = set("S", QuickReply(label = "a", message = "hello {{user}}"))
        val o = runBlocking { svc.run(s, s.qrList[0], "", exec(), { it.replace("{{user}}", "Al") }) }
        assertEquals("hello Al", o.textToSend)
    }
    @Test fun runSlashMessageExecutes() {
        val s = set("S", QuickReply(label = "a", message = "/sys boo"))
        val o = runBlocking { svc.run(s, s.qrList[0], "", exec(), { it }) }
        assertNull(o.error); assertEquals(listOf("boo"), host.sys); assertNull(o.textToSend)
    }
    @Test fun inputMacroExpanded() {
        val s = set("S", QuickReply(label = "a", message = "/sys got {{input}}"))
        runBlocking { svc.run(s, s.qrList[0], "typed", exec(), { it }) }
        assertEquals(listOf("got typed"), host.sys)
    }
    @Test fun injectInputPrepends() {
        val s = set("S", QuickReply(label = "a", message = "MSG")) { copy(injectInput = true, placeBeforeInput = true) }
        assertEquals("MSG typed", runBlocking { svc.run(s, s.qrList[0], "typed", exec(), { it }) }.textToSend)
        val s2 = s.copy(placeBeforeInput = false)
        assertEquals("typed MSG", runBlocking { svc.run(s2, s2.qrList[0], "typed", exec(), { it }) }.textToSend)
    }
    @Test fun disableSendFillsInput() {
        val s = set("S", QuickReply(label = "a", message = "draft")) { copy(disableSend = true) }
        val o = runBlocking { svc.run(s, s.qrList[0], "", exec(), { it }) }
        assertEquals("draft", o.inputText); assertNull(o.textToSend)
    }
    @Test fun activeSetsDeduped() {
        set("G"); set("C")
        settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("G", "C"), characterSets = mapOf("Ann.png" to "G")))
        chatSet = "C"
        assertEquals(listOf("G", "C"), svc.activeSets().map { it.name })
    }
    @Test fun hiddenNotInBar() {
        repo.importJson(sample); settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("Tools")))
        assertEquals(listOf("Greet"), svc.bar().map { it.second.label })
    }
    @Test fun autoExecOnUserAndAiAndAutomationId() {
        repo.importJson(sample); settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("Tools")))
        assertEquals(listOf("Greet"), svc.autoExecutable(QuickReplyService.Trigger.USER).map { it.second.label })
        assertTrue(svc.autoExecutable(QuickReplyService.Trigger.AI).isEmpty())
        val withAuto = repo.load("Tools")!!.let { s -> s.copy(qrList = s.qrList.map { if (it.label == "Hidden") it.copy(executeOnAi = true) else it }) }
        repo.save(withAuto)
        assertEquals(listOf("Hidden"), svc.autoExecutable(QuickReplyService.Trigger.AI).map { it.second.label })
    }
    @Test fun automationIdMismatchSkipped() {
        val s = set("S", QuickReply(label = "x", message = "/sys a", executeOnAi = true, automationId = "ZZ"))
        settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("S")))
        assertTrue(svc.autoExecutable(QuickReplyService.Trigger.AI).isEmpty())
    }
    @Test fun qrCommandRunsByLabel() {
        set("S", QuickReply(label = "go", message = "/sys ran"))
        settings = settings.copy(quickReplies = QuickReplySettings(globalSets = listOf("S")))
        assertNull(run("/qr go").error); assertNull(run("/qr S.go").error); assertNull(run("/run S.go").error)
        assertEquals(listOf("ran", "ran", "ran"), host.sys)
    }
    @Test fun qrCreateUpdateDelete() {
        set("S")
        run("/qr-create set=S label=a first"); assertEquals("first", repo.load("S")!!.qrList.single().message)
        run("/qr-update set=S label=a newlabel=b second"); assertEquals("b" to "second", repo.load("S")!!.qrList.single().let { it.label to it.message })
        run("/qr-delete set=S b"); assertTrue(repo.load("S")!!.qrList.isEmpty())
    }
    @Test fun qrSetCommands() {
        set("S"); run("/qr-set-on S"); assertEquals(listOf("S"), settings.quickReplies.globalSets)
        run("/qr-set-off S"); assertTrue(settings.quickReplies.globalSets.isEmpty())
        run("/qr-chat-set S"); assertEquals("S", chatSet); run("/qr-chat-set-off"); assertNull(chatSet)
    }
    @Test fun unknownQrErrors() = assertNotNull(run("/qr nothing").error)
    @Test fun recursiveQrCapped() {
        set("S", QuickReply(label = "loop", message = "/run S.loop"))
        val r = run("/run S.loop")
        assertTrue(r.error!!.contains("depth"))
    }

    @Test fun controllerFiresAutoExecOnUserAndAi() = kotlinx.coroutines.test.runTest {
        repo.save(QuickReplySet(name = "Auto").withAdded(QuickReply(label = "u", message = "/incvar key", executeOnUser = true)).withAdded(QuickReply(label = "a", message = "/incvar ai", executeOnAi = true)))
        settings = AppSettings(connections = listOf(app.sillytavern.android.data.ConnectionProfile(name = "c")), quickReplies = QuickReplySettings(globalSets = listOf("Auto")))
        val backend = app.sillytavern.android.api.Backend { kotlinx.coroutines.flow.flow { emit("ok") } }
        val c = app.sillytavern.android.chat.ChatController(app.sillytavern.android.chat.ChatRepository(StoragePaths(Files.createTempDirectory("qa").toFile()).ensure()),
            { backend }, { settings }, { "k" }, this)
        c.quickReplies = QuickReplyService(repo, { settings }, { f -> settings = f(settings) }, { null }, { }, { null })
        c.open(app.sillytavern.android.card.StoredCharacter("Ann", app.sillytavern.android.card.CharacterCard(name = "Ann", firstMessage = "Hi"), java.io.File("x")))
        c.send("hello"); testScheduler.advanceUntilIdle()
        assertEquals("1", c.variableStore().get("key", false)); assertEquals("1", c.variableStore().get("ai", false))
    }
}
