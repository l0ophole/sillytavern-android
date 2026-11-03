package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.card.StoredCharacter
import app.sillytavern.android.group.ActivationStrategy
import app.sillytavern.android.group.Group
import app.sillytavern.android.group.GroupGenerationMode
import app.sillytavern.android.tags.updateTags

private val strategies = listOf(
    ActivationStrategy.NATURAL to "Natural order (mentions, then talkativeness)",
    ActivationStrategy.LIST to "List order (everyone, in order)",
    ActivationStrategy.POOLED to "Pooled order (one at a time, rotating)",
    ActivationStrategy.MANUAL to "Manual (only when you pick a member)",
)
private val modes = listOf(
    GroupGenerationMode.SWAP to "Swap character cards",
    GroupGenerationMode.APPEND to "Join character cards (exclude muted)",
    GroupGenerationMode.APPEND_DISABLED to "Join character cards (include muted)",
)

/** Create ([groupId] null) or edit a group. */
@Composable
fun GroupEditScreen(c: AppContainer, groupId: String?, onDone: () -> Unit) {
    val existing = remember(groupId) { groupId?.let { c.groups.get(it) } }
    var group by remember { mutableStateOf(existing ?: Group(id = "", name = "", chatId = "")) }
    val all = remember { c.characters.list() }
    val byAvatar = all.associateBy { it.fileName + ".png" }
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctl = c.chatController

    fun move(a: String, d: Int) {
        val l = group.members.toMutableList(); val i = l.indexOf(a); val j = i + d
        if (i >= 0 && j in l.indices) { l[i] = l[j]; l[j] = a; group = group.copy(members = l) }
    }

    Scaffold(topBar = {
        BackTopBar(if (existing == null) "New group" else "Edit group", onDone, actions = {
            TextButton(enabled = group.name.isNotBlank(), onClick = {
                val saved = if (existing == null) c.groups.create(group.name.trim(), group.members).let { g ->
                    group.copy(id = g.id, chatId = g.chatId, chats = g.chats, name = group.name.trim()).also(c.groups::save)
                } else group.copy(name = group.name.trim()).also(c.groups::save)
                if (ctl.state.value.group?.id == saved.id) ctl.updateGroup { saved }
                onDone()
            }) { Text("Save") }
        })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Field("Name", group.name, { group = group.copy(name = it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favorite", Modifier.weight(1f)); Switch(group.fav, { group = group.copy(fav = it) })
            }
            Text("Members", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            group.members.forEach { a ->
                val ch = byAvatar[a]
                val muted = group.isMuted(a)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text((ch?.card?.name ?: "${a.removeSuffix(".png")} (missing)") + if (muted) "  (muted)" else "", Modifier.weight(1f))
                    TextButton(onClick = { move(a, -1) }) { Text("↑") }
                    TextButton(onClick = { move(a, 1) }) { Text("↓") }
                    TextButton(onClick = { group = group.copy(disabledMembers = if (muted) group.disabledMembers - a else group.disabledMembers + a) }) { Text(if (muted) "Unmute" else "Mute") }
                    TextButton(onClick = { group = group.copy(members = group.members - a, disabledMembers = group.disabledMembers - a) }) { Text("✕") }
                }
            }
            OutlinedButton(onClick = { adding = true }) { Text("Add member") }
            Spacer(Modifier.height(8.dp))
            Pick("Reply order", strategies.map { it.second }, strategies.indexOfFirst { it.first == group.activationStrategy }.coerceAtLeast(0)) {
                group = group.copy(activationStrategy = strategies[it].first)
            }
            Pick("Card handling", modes.map { it.second }, modes.indexOfFirst { it.first == group.generationMode }.coerceAtLeast(0)) {
                group = group.copy(generationMode = modes[it].first)
            }
            if (group.generationMode != GroupGenerationMode.SWAP) {
                Field("Join prefix (use <FIELDNAME>, {{char}})", group.joinPrefix, { group = group.copy(joinPrefix = it) }, singleLine = false)
                Field("Join suffix", group.joinSuffix, { group = group.copy(joinSuffix = it) }, singleLine = false)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow a member to reply twice in a row", Modifier.weight(1f))
                Switch(group.allowSelfResponses, { group = group.copy(allowSelfResponses = it) })
            }
            Field("Auto mode delay (seconds)", group.autoModeDelay.toString(), { group = group.copy(autoModeDelay = it.toIntOrNull()?.coerceIn(1, 3600) ?: group.autoModeDelay) }, number = true)
            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                EntityTagEditor(c, existing.id)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete group") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (adding) {
        val candidates = all.filter { it.fileName + ".png" !in group.members }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("Add member") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text("Every character is already a member.")
                    candidates.forEach { ch: StoredCharacter ->
                        TextButton(onClick = { group = group.copy(members = group.members + (ch.fileName + ".png")); adding = false }) { Text(ch.card.name) }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { adding = false }) { Text("Close") } })
    }
    if (confirmDelete && existing != null) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete ${existing.name}?") },
            text = { Text("The group and all of its chats are deleted. Characters are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    c.groups.delete(existing.id)
                    c.settings.updateTagsRemoveKey(existing.id)
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    }
}

private fun app.sillytavern.android.data.SettingsRepository.updateTagsRemoveKey(key: String) =
    updateTags { app.sillytavern.android.tags.TagOps.removeKey(it, key) }
