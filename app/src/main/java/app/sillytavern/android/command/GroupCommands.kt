package app.sillytavern.android.command

object GroupCommands {
    private suspend fun members(host: CommandHost) =
        host.groupMembers() ?: throw CommandError("This command needs an open group chat")

    /** Finds a member by 0-based index, name (case-insensitive) or avatar file name. */
    suspend fun find(host: CommandHost, arg: String): GroupMemberInfo {
        val all = members(host)
        val a = arg.trim()
        return a.toIntOrNull()?.let(all::getOrNull)
            ?: all.firstOrNull { it.name.equals(a, true) || it.avatar.equals(a, true) }
            ?: throw CommandError("No group member found for \"$a\"")
    }

    fun register() {
        cmd("member-count", "countmember", "membercount", help = "Returns the number of members in the group.", usesPipe = false) { c ->
            members(c.host).size.toString()
        }
        cmd("member-get", "getmember", "memberget", help = "member-get field=name|index|avatar member - looks a member up by index, name or avatar.") { c ->
            val m = find(c.host, c.unnamed)
            when (val f = (c.named["field"] ?: "name").lowercase()) {
                "name" -> m.name
                "avatar" -> m.avatar
                "index" -> members(c.host).indexOf(m).toString()
                else -> throw CommandError("field must be name, index or avatar, not \"$f\"")
            }
        }
        cmd("member-enable", "enablemember", "memberenable", help = "member-enable member - lets a muted member speak again.", usesPipe = false) { c ->
            c.host.groupSetEnabled(find(c.host, c.unnamed).avatar, true); c.pipe
        }
        cmd("member-disable", "disablemember", "memberdisable", help = "member-disable member - mutes a member.", usesPipe = false) { c ->
            c.host.groupSetEnabled(find(c.host, c.unnamed).avatar, false); c.pipe
        }
        cmd("member-add", "addmember", "memberadd", help = "member-add character - adds a character to the group.", usesPipe = false) { c ->
            members(c.host)
            if (!c.host.groupAdd(c.unnamed.trim())) throw CommandError("No character found for \"${c.unnamed.trim()}\"")
            c.pipe
        }
        cmd("member-remove", "removemember", "memberremove", help = "member-remove member - removes a member from the group.", usesPipe = false) { c ->
            c.host.groupRemove(find(c.host, c.unnamed).avatar); c.pipe
        }
        cmd("member-up", "upmember", "memberup", help = "member-up member - moves a member earlier in the order.", usesPipe = false) { c ->
            c.host.groupMove(find(c.host, c.unnamed).avatar, -1); c.pipe
        }
        cmd("member-down", "downmember", "memberdown", help = "member-down member - moves a member later in the order.", usesPipe = false) { c ->
            c.host.groupMove(find(c.host, c.unnamed).avatar, 1); c.pipe
        }
    }
}
