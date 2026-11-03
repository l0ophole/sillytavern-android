package app.sillytavern.android.command

object CommandLibrary {
    fun registerAll() {
        FlowCommands.register()
        TextCommands.register()
        MathCommands.register()
        ChatCommands.register()
        QuickReplyCommands.register()
    }
}

internal fun cmd(name: String, vararg aliases: String, help: String = "", rawUnnamed: Boolean = false,
                 rawNamed: Set<String> = emptySet(), usesPipe: Boolean = true, run: suspend (CommandContext) -> String) =
    CommandRegistry.register(CommandDef(name, aliases.toList(), help, rawUnnamed, rawNamed, usesPipe, run))
