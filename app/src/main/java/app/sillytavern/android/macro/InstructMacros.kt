package app.sillytavern.android.macro

import app.sillytavern.android.prompt.model.InstructTemplate

object InstructMacros {
    fun register() {
        fun reg(vararg names: String, f: (InstructTemplate) -> String) =
            MacroRegistry.register(MacroDef(names.toList(), 0, 0) { c -> c.ctx.instruct?.let(f) ?: "" })
        reg("instructSystemPrefix") { it.systemSequence }
        reg("instructSystemSuffix") { it.systemSuffix }
        reg("instructUserPrefix", "instructInput") { it.inputSequence }
        reg("instructUserSuffix") { it.inputSuffix }
        reg("instructAssistantPrefix", "instructOutput") { it.outputSequence }
        reg("instructAssistantSuffix", "instructSeparator") { it.outputSuffix }
        reg("instructFirstAssistantPrefix", "instructFirstOutputPrefix") { it.firstOutputSequence }
        reg("instructLastAssistantPrefix", "instructLastOutputPrefix") { it.lastOutputSequence }
        reg("instructSystemInstructionPrefix") { it.systemSequence }
        reg("instructStoryStringPrefix") { it.storyStringPrefix }
        reg("instructStoryStringSuffix") { it.storyStringSuffix }
        reg("instructUserFiller") { it.userAlignmentMessage }
        reg("instructStopSequence") { it.stopSequence }
    }
}
