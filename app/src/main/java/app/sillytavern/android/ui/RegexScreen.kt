package app.sillytavern.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.chat.MacroContext
import app.sillytavern.android.chat.Macros
import app.sillytavern.android.regex.*

private val placements = listOf(Placement.USER_INPUT to "User input", Placement.AI_OUTPUT to "AI output", Placement.SLASH_COMMAND to "Slash command",
    Placement.WORLD_INFO to "World Info", Placement.REASONING to "Reasoning")

/** List + editor for a list of regex scripts; used for global scripts and a character's scoped scripts. */
@Composable
fun RegexListEditor(scripts: List<RegexScript>, onChange: (List<RegexScript>) -> Unit, c: AppContainer? = null) {
    var editing by remember { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && c != null) message = runCatching { c.regexScripts.importJson(String(ctx.readUri(uri))) }
            .fold({ onChange(scripts + it); "Imported ${it.size}" }, { "Import failed: ${it.message}" })
    }
    var exportIdx by remember { mutableStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val s = scripts.getOrNull(exportIdx)
        if (uri != null && s != null && c != null) message = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(c.regexScripts.exportJson(s).toByteArray()) } }
            .fold({ "Exported" }, { "Export failed: ${it.message}" })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        if (c != null) TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
        TextButton(onClick = { onChange(scripts + RegexScript(scriptName = "New script", placement = listOf(Placement.AI_OUTPUT))); editing = scripts.size }) { Text("Add") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (scripts.isEmpty()) Text("No scripts.", style = MaterialTheme.typography.bodySmall)
    scripts.forEachIndexed { i, s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Switch(!s.disabled, { on -> onChange(scripts.toMutableList().also { it[i] = s.copy(disabled = !on) }) })
            Spacer(Modifier.width(8.dp))
            Text(s.scriptName.ifBlank { "(unnamed)" }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { editing = i }) { Text("Edit") }
            if (c != null) TextButton(onClick = { exportIdx = i; exporter.launch("${s.scriptName.ifBlank { "script" }}.json") }) { Text("Export") }
            TextButton(onClick = { onChange(scripts.toMutableList().also { it.removeAt(i) }) }) { Text("Delete") }
        }
    }
    editing?.let { i ->
        scripts.getOrNull(i)?.let { s ->
            ScriptDialog(s, onDismiss = { editing = null }, onSave = { upd -> onChange(scripts.toMutableList().also { it[i] = upd }); editing = null })
        } ?: run { editing = null }
    }
}

@Composable
private fun ScriptDialog(s: RegexScript, onDismiss: () -> Unit, onSave: (RegexScript) -> Unit) {
    var name by remember { mutableStateOf(s.scriptName) }
    var find by remember { mutableStateOf(s.findRegex) }
    var rep by remember { mutableStateOf(s.replaceString) }
    var trim by remember { mutableStateOf(s.trimStrings.joinToString("\n")) }
    var pl by remember { mutableStateOf(s.placement.toSet()) }
    var md by remember { mutableStateOf(s.markdownOnly) }
    var pr by remember { mutableStateOf(s.promptOnly) }
    var onEdit by remember { mutableStateOf(s.runOnEdit) }
    var sub by remember { mutableStateOf(s.substituteRegex.coerceIn(0, 2)) }
    var minD by remember { mutableStateOf(s.minDepth?.toString().orEmpty()) }
    var maxD by remember { mutableStateOf(s.maxDepth?.toString().orEmpty()) }
    var sample by remember { mutableStateOf("") }
    fun current() = s.copy(scriptName = name, findRegex = find, replaceString = rep, trimStrings = trim.lines().filter { it.isNotEmpty() },
        placement = pl.toList().sorted(), markdownOnly = md, promptOnly = pr, runOnEdit = onEdit, substituteRegex = sub,
        minDepth = minD.trim().toIntOrNull(), maxDepth = maxD.trim().toIntOrNull())
    val result = remember(sample, find, rep, trim, pl, md, pr, sub) {
        val sc = current()
        RegexEngine.run(sc, sample, RegexParams(macros = { Macros.expand(it, MacroContext("User", "Char")) }))
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Regex script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name", name, { name = it })
                Field("Find regex (/pattern/flags)", find, { find = it })
                Field("Replace with ($1, \$<name>, {{match}})", rep, { rep = it }, singleLine = false, minLines = 2)
                Field("Trim strings (one per line)", trim, { trim = it }, singleLine = false, minLines = 2)
                Text("Affects", style = MaterialTheme.typography.labelLarge)
                placements.forEach { (id, label) -> SwitchRow(label, id in pl) { on -> pl = if (on) pl + id else pl - id } }
                SwitchRow("Only format display (markdown)", md) { md = it; if (it) pr = false }
                SwitchRow("Only format prompt", pr) { pr = it; if (it) md = false }
                SwitchRow("Run on edit", onEdit) { onEdit = it }
                Pick("Macros in find regex", listOf("Don't substitute", "Substitute", "Substitute (escaped)"), sub) { sub = it }
                Field("Min depth", minD, { minD = it }, number = true)
                Field("Max depth", maxD, { maxD = it }, number = true)
                Text("Test", style = MaterialTheme.typography.labelLarge)
                Field("Sample text", sample, { sample = it }, singleLine = false, minLines = 2)
                Text("→ $result", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(current()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RegexScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    var scripts by remember { mutableStateOf(c.regexScripts.global()) }
    Scaffold(topBar = { BackTopBar("Regex scripts", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SwitchRow("Enable regex scripts", s.regexEnabled) { v -> c.settings.update { it.copy(regexEnabled = v) } }
            Text("Global scripts", style = MaterialTheme.typography.titleLarge)
            RegexListEditor(scripts, { scripts = it; c.regexScripts.saveGlobal(it) }, c)
            Text("Scripts run in list order. Character-specific scripts are edited in the character editor.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(32.dp))
        }
    }
}
