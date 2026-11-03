package app.sillytavern.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.data.Persona
import coil.compose.AsyncImage
import java.io.File

internal val personaPositions = listOf("In prompt (after main)", "Top of Author's Note", "Bottom of Author's Note", "In chat at depth", "Not sent")
private val positionValues = listOf(0, 2, 3, 4, 5)
internal val noteRoles = listOf("System", "User", "Assistant")

/** Avatar thumbnail of a persona (initial letter when it has none). */
@Composable
fun PersonaAvatar(c: AppContainer, p: Persona, size: Int = 40) {
    val f = if (p.avatar.isNotBlank()) File(c.paths.userAvatars, p.avatar).takeIf { it.exists() } else null
    if (f != null) AsyncImage(f, p.name, Modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    else Surface(Modifier.size(size.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(p.name.take(1).uppercase()) }
    }
}

@Composable
fun PersonaEditDialog(c: AppContainer, p: Persona, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s by c.settings.settings.collectAsState()
    var name by remember { mutableStateOf(p.name) }
    var title by remember { mutableStateOf(p.title) }
    var desc by remember { mutableStateOf(p.description) }
    var avatar by remember { mutableStateOf(p.avatar) }
    var position by remember { mutableStateOf(p.position.let { if (it == 1) 0 else it }) }
    var depth by remember { mutableStateOf(p.depth.toString()) }
    var role by remember { mutableStateOf(p.role) }
    var lorebook by remember { mutableStateOf(p.lorebook) }
    var isDefault by remember { mutableStateOf(s.defaultPersonaId == p.id) }
    var error by remember { mutableStateOf<String?>(null) }
    val books = remember { c.worlds.list() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val png = toAvatarPng(bytes)
            val file = "${p.id}.png"
            File(c.paths.userAvatars, file).writeBytes(png)
            avatar = file
        }.onFailure { error = "Could not use that image: ${it.message}" }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Persona") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PersonaAvatar(c, p.copy(avatar = avatar, name = name.ifBlank { "?" }), 56)
                    TextButton(onClick = { picker.launch("image/*") }) { Text("Choose avatar…") }
                    if (avatar.isNotBlank()) TextButton(onClick = { avatar = "" }) { Text("Remove") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Field("Name", name, { name = it })
                Field("Title (list label)", title, { title = it })
                Field("Description", desc, { desc = it }, singleLine = false, minLines = 3)
                Pick("Description position", personaPositions, positionValues.indexOf(position).coerceAtLeast(0)) { position = positionValues[it] }
                if (position == 4) {
                    Field("Depth", depth, { depth = it.filter(Char::isDigit) }, number = true)
                    Pick("Role", noteRoles, role) { role = it }
                }
                Pick("Lorebook", listOf("(none)") + books, if (lorebook.isBlank()) 0 else books.indexOf(lorebook) + 1) { lorebook = if (it == 0) "" else books[it - 1] }
                SwitchRow("Default persona", isDefault) { isDefault = it }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val np = p.copy(name = name.trim(), title = title.trim(), description = desc, avatar = avatar, position = position,
                    depth = depth.toIntOrNull() ?: 2, role = role, lorebook = lorebook)
                c.settings.update { st ->
                    st.copy(
                        personas = if (st.personas.any { it.id == p.id }) st.personas.map { if (it.id == p.id) np else it } else st.personas + np,
                        defaultPersonaId = if (isDefault) p.id else st.defaultPersonaId.takeIf { it != p.id },
                    )
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Chat-menu dialog: switch persona and lock it to this chat or character. */
@Composable
fun PersonaLockDialog(c: AppContainer, onDismiss: () -> Unit) {
    val ctl = c.chatController
    val s by c.settings.settings.collectAsState()
    val state by ctl.state.collectAsState()
    // Recomputed whenever the chat or the active persona changes.
    val chatLocked = remember(state.chat?.header, s.activePersonaId) { ctl.isChatLocked() }
    val charLocked = remember(s.personas, s.activePersonaId, state.character) { ctl.isCharacterLocked() }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Persona") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                s.personas.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(p.id == s.activePersona.id, { ctl.selectPersona(p.id) })
                        PersonaAvatar(c, p, 32)
                        Spacer(Modifier.width(8.dp))
                        Text(p.name + if (p.id == s.defaultPersonaId) " (default)" else "")
                    }
                }
                SwitchRow("Lock to this chat", chatLocked) { if (it) ctl.lockPersona(true) else ctl.unlockPersona(true) }
                if (state.group == null) SwitchRow("Lock to this character", charLocked) { if (it) ctl.lockPersona(false) else ctl.unlockPersona(false) }
                SwitchRow("Auto-lock when selecting a persona", s.personaAutoLock) { v -> c.settings.update { it.copy(personaAutoLock = v) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
