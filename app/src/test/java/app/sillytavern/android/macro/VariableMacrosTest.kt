package app.sillytavern.android.macro

import app.sillytavern.android.chat.ChatMessage
import app.sillytavern.android.chat.ChatRepository
import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.data.StoragePaths
import app.sillytavern.android.prompt.model.InstructTemplate
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class VariableMacrosTest {
    private fun ctx(vars: VariableStore = VariableStore.inMemory(), instruct: InstructTemplate? = null) =
        MacroContext(user = "Al", char = "Bea", vars = vars, instruct = instruct)
    private fun x(s: String, c: MacroContext) = Macros.expand(s, c)

    @Test fun setGetLocal() = assertEquals("5", x("{{setvar::a::5}}{{getvar::a}}", ctx()))
    @Test fun numericAddAndInc() {
        val c = ctx()
        assertEquals("7", x("{{setvar::a::5}}{{addvar::a::2}}{{getvar::a}}", c))
        assertEquals("8", x("{{incvar::a}}", c)); assertEquals("7", x("{{decvar::a}}", c))
    }
    @Test fun stringAddAppends() = assertEquals("foobar", x("{{setvar::s::foo}}{{addvar::s::bar}}{{getvar::s}}", ctx()))
    @Test fun globalVsLocalIsolated() {
        val c = ctx()
        assertEquals("L|G", x("{{setvar::a::L}}{{setglobalvar::a::G}}{{getvar::a}}|{{getglobalvar::a}}", c))
    }
    @Test fun missingVarEmpty() = assertEquals("[]", x("[{{getvar::zzz}}]", ctx()))
    @Test fun deleteAndHas() {
        val c = ctx()
        assertEquals("true", x("{{setvar::a::1}}{{hasvar::a}}", c))
        assertEquals("false", x("{{deletevar::a}}{{hasvar::a}}", c))
    }
    @Test fun selfReferentialTerminates() {
        val c = ctx()
        x("{{setvar::a::{{getvar::a}}x}}", c); x("{{setvar::a::{{getvar::a}}x}}", c)
        assertEquals("xx", x("{{getvar::a}}", c))
    }
    @Test fun indexedRead() = assertEquals("b", x("{{setvar::l::[\"a\",\"b\"]}}{{getvar::l::1}}", ctx()))
    @Test fun localVarsPersistInChatHeader() {
        val r = ChatRepository(StoragePaths(Files.createTempDirectory("v").toFile()).ensure())
        var chat = r.create("A", "A", "U", null)
        val store = VariableStore({ (chat.header["chat_metadata"] as JsonObject)["variables"] as? JsonObject ?: JsonObject(emptyMap()) },
            { v -> chat = chat.copy(header = JsonObject(chat.header + ("chat_metadata" to JsonObject((chat.header["chat_metadata"] as JsonObject) + ("variables" to v))))) },
            { emptyMap() }, { })
        store.set("mood", "happy", false); store.set("n", "3", false)
        r.save(chat)
        val back = r.load(chat.file!!)
        val vars = (back.header["chat_metadata"] as JsonObject)["variables"] as JsonObject
        assertEquals("happy", vars["mood"]!!.jsonPrimitive.content)
        assertEquals(3, vars["n"]!!.jsonPrimitive.int)
    }
    @Test fun instructMacroAliases() {
        val t = InstructTemplate(inputSequence = "<U>", outputSequence = "<A>", outputSuffix = "</s>", systemSequence = "<S>")
        val c = ctx(instruct = t)
        assertEquals("<U>|<U>|<A>|<A>|</s>|</s>|<S>", x("{{instructUserPrefix}}|{{instructInput}}|{{instructAssistantPrefix}}|{{instructOutput}}|{{instructAssistantSuffix}}|{{instructSeparator}}|{{instructSystemPrefix}}", c))
    }
    @Test fun instructMacroEmptyWhenOff() = assertEquals("[]", x("[{{instructUserPrefix}}]", ctx()))
}
