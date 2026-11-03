package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.chat.NoteConfig
import app.sillytavern.android.data.CharacterNote
import app.sillytavern.android.data.NoteSettings

private val notePositions = listOf("In prompt", "In chat at depth", "Before prompt")
private val charNotePositions = listOf("Replace chat note", "Before chat note", "After chat note")

/** Author's Note of the open chat, plus the open character's own note. */
@Composable
fun AuthorsNoteDialog(c: AppContainer, onDismiss: () -> Unit) {
    val ctl = c.chatController
    val cfg = remember { ctl.noteConfig() }
    val charNote = remember { ctl.characterNote() }
    val chatState by ctl.state.collectAsState()
    val single = chatState.group == null
    var text by remember { mutableStateOf(cfg.prompt) }
    var interval by remember { mutableStateOf(cfg.interval.toString()) }
    var depth by remember { mutableStateOf(cfg.depth.toString()) }
    var position by remember { mutableStateOf(cfg.position.coerceIn(0, 2)) }
    var role by remember { mutableStateOf(cfg.role.coerceIn(0, 2)) }
    var cText by remember { mutableStateOf(charNote?.prompt.orEmpty()) }
    var cUse by remember { mutableStateOf(charNote?.useChara ?: false) }
    var cPos by remember { mutableStateOf(charNote?.position?.coerceIn(0, 2) ?: 0) }
    fun config() = NoteConfig(text, interval.toIntOrNull()?.coerceAtLeast(0) ?: 1, depth.toIntOrNull()?.coerceAtLeast(0) ?: 4, position, role)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Author's Note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Note for this chat", text, { text = it }, singleLine = false, minLines = 4)
                Field("Insert every N user messages (0 = never, 1 = always)", interval, { interval = it.filter(Char::isDigit) }, number = true)
                Pick("Position", notePositions, position) { position = it }
                if (position == 1) Field("Depth", depth, { depth = it.filter(Char::isDigit) }, number = true)
                Pick("Role", noteRoles, role) { role = it }
                TextButton(onClick = {
                    val k = config()
                    c.settings.update { it.copy(note = NoteSettings(k.position, k.depth, k.interval, k.role)) }
                }) { Text("Use these settings as the default for new chats") }
                if (single) {
                    Spacer(Modifier.height(8.dp))
                    Text("Character's note", style = MaterialTheme.typography.titleSmall)
                    Field("Note for ${chatState.character?.card?.name.orEmpty()}", cText, { cText = it }, singleLine = false, minLines = 3)
                    SwitchRow("Use the character's note", cUse) { cUse = it }
                    Pick("Combine with chat note", charNotePositions, cPos) { cPos = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ctl.setNoteConfig(config())
                if (single) ctl.setCharacterNote(CharacterNote(cText, cUse, cPos))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val summaryPositions = listOf("In prompt", "In chat at depth", "Before prompt", "Not injected")
private val summaryPositionValues = listOf(0, 1, 2, -1)

/** Chat summary: view / edit / generate, and the summary settings. */
@Composable
fun SummaryDialog(c: AppContainer, onDismiss: () -> Unit) {
    val ctl = c.chatController
    val state by ctl.state.collectAsState()
    val s by c.settings.settings.collectAsState()
    val sm = s.summary
    var text by remember { mutableStateOf(ctl.summaryText()) }
    var interval by remember(sm.interval) { mutableStateOf(sm.interval.toString()) }
    var words by remember(sm.promptWords) { mutableStateOf(sm.promptWords.toString()) }
    var depth by remember(sm.depth) { mutableStateOf(sm.depth.toString()) }
    var prompt by remember { mutableStateOf(sm.prompt) }
    var template by remember { mutableStateOf(sm.template) }
    // A freshly generated summary arrives through the chat state.
    LaunchedEffect(state.chat?.messages, state.summarizing) { if (!state.summarizing) text = ctl.summaryText() }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Summary") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Current summary", text, { text = it }, singleLine = false, minLines = 5)
                Row {
                    TextButton(enabled = !state.summarizing && !state.generating, onClick = { ctl.summarize() }) { Text(if (state.summarizing) "Summarizing…" else "Summarize now") }
                    TextButton(onClick = { ctl.setSummary(text) }) { Text("Save text") }
                }
                SwitchRow("Summarize automatically", sm.auto) { v -> ctl.setSummarySettings { it.copy(auto = v) } }
                SwitchRow("Freeze (keep this summary, no auto updates)", sm.frozen) { v -> ctl.setSummarySettings { it.copy(frozen = v) } }
                Field("Auto-summarize after N chat lines", interval, { interval = it.filter(Char::isDigit); interval.toIntOrNull()?.let { n -> ctl.setSummarySettings { it.copy(interval = n) } } }, number = true)
                Field("Summary length (words)", words, { words = it.filter(Char::isDigit); words.toIntOrNull()?.let { n -> ctl.setSummarySettings { it.copy(promptWords = n.coerceAtLeast(1)) } } }, number = true)
                Pick("Inject summary", summaryPositions, summaryPositionValues.indexOf(sm.position).coerceAtLeast(0)) { i -> ctl.setSummarySettings { it.copy(position = summaryPositionValues[i]) } }
                if (sm.position == 1) {
                    Field("Depth", depth, { depth = it.filter(Char::isDigit); depth.toIntOrNull()?.let { n -> ctl.setSummarySettings { it.copy(depth = n) } } }, number = true)
                }
                Pick("Role", noteRoles, sm.role.coerceIn(0, 2)) { i -> ctl.setSummarySettings { it.copy(role = i) } }
                Field("Summarization prompt ({{words}})", prompt, { prompt = it; ctl.setSummarySettings { s0 -> s0.copy(prompt = it) } }, singleLine = false, minLines = 3)
                Field("Injection template ({{summary}})", template, { template = it; ctl.setSummarySettings { s0 -> s0.copy(template = it) } })
            }
        },
        confirmButton = { TextButton(onClick = { ctl.setSummary(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
