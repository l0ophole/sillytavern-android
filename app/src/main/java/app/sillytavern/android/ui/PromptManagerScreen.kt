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
import app.sillytavern.android.data.DefaultPresets
import app.sillytavern.android.data.PresetIo
import app.sillytavern.android.data.PresetKind
import app.sillytavern.android.prompt.model.*
import java.util.UUID

/** Prompt Manager: pick a preset, reorder / toggle / edit its prompts, import and export ST JSON. */
@Composable
fun PromptManagerScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    val ctx = LocalContext.current
    val pm = s.activePromptManager
    var editing by remember { mutableStateOf<PromptDefinition?>(null) }
    var options by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun updatePm(t: (PromptManagerPreset) -> PromptManagerPreset) = c.settings.update { st ->
        st.copy(promptManagerPresets = st.promptManagerPresets.map { if (it.name == pm.name) t(it) else it })
    }
    fun move(index: Int, delta: Int) = updatePm { p ->
        val j = index + delta
        if (j !in p.order.indices) p else p.copy(order = p.order.toMutableList().also { val x = it[index]; it[index] = it[j]; it[j] = x })
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) message = runCatching {
            PresetIo.importPreset(c.settings, PresetKind.PROMPT_MANAGER, uri.lastPathSegment.orEmpty(), String(ctx.readUri(uri))).getOrThrow()
        }.fold({ "Imported \"$it\"" }, { "Import failed: ${it.message}" })
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = PresetIo.exportPreset(c.settings.settings.value, PresetKind.PROMPT_MANAGER, pm.name)
        if (uri != null && text != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        }.fold({ "Exported" }, { "Export failed: ${it.message}" })
    }

    Scaffold(topBar = { BackTopBar("Prompt Manager", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text("Preset", style = MaterialTheme.typography.titleMedium)
            s.promptManagerPresets.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(p.name == pm.name, { c.settings.update { it.copy(activePromptManagerName = p.name) } })
                    Text(p.name)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
                TextButton(onClick = { exporter.launch("${pm.name}.json") }) { Text("Export") }
                TextButton(onClick = {
                    var n = "${pm.name} copy"; while (s.promptManagerPresets.any { it.name == n }) n += "+"
                    c.settings.update { it.copy(promptManagerPresets = it.promptManagerPresets + pm.copy(name = n), activePromptManagerName = n) }
                }) { Text("Duplicate") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { updatePm { DefaultPresets.promptManager(it.name) } }) { Text("Reset") }
                TextButton(enabled = s.promptManagerPresets.size > 1, onClick = {
                    c.settings.update { st -> st.copy(promptManagerPresets = st.promptManagerPresets.filter { it.name != pm.name }, activePromptManagerName = null) }
                }) { Text("Delete") }
                TextButton(onClick = { options = true }) { Text("Options") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Prompts (in order)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { editing = PromptDefinition(identifier = "custom-${UUID.randomUUID()}", name = "") }) { Text("Add prompt") }
            }
            val defs = pm.prompts.associateBy { it.identifier }
            pm.order.forEachIndexed { index, entry ->
                val d = defs[entry.identifier] ?: return@forEachIndexed
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Switch(entry.enabled, { on -> updatePm { p -> p.copy(order = p.order.map { if (it.identifier == d.identifier) it.copy(enabled = on) else it }) } })
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(d.name.ifBlank { d.identifier }, style = MaterialTheme.typography.titleSmall)
                        val sub = when {
                            d.marker -> "marker"
                            d.injectionPosition == 1 -> "${d.role} · inject at depth ${d.injectionDepth}"
                            else -> d.role
                        }
                        Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { move(index, -1) }, enabled = index > 0) { Text("↑") }
                    TextButton(onClick = { move(index, 1) }, enabled = index < pm.order.lastIndex) { Text("↓") }
                    TextButton(onClick = { editing = d }) { Text("Edit") }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    editing?.let { d -> PromptEditDialog(d, isNew = pm.prompts.none { it.identifier == d.identifier },
        onDismiss = { editing = null },
        onDelete = if (d.identifier.startsWith("custom-")) ({
            updatePm { p -> p.copy(prompts = p.prompts.filter { it.identifier != d.identifier }, order = p.order.filter { it.identifier != d.identifier }) }
            editing = null
        }) else null,
        onSave = { nd ->
            updatePm { p ->
                if (p.prompts.any { it.identifier == nd.identifier }) p.copy(prompts = p.prompts.map { if (it.identifier == nd.identifier) nd else it })
                else p.copy(prompts = p.prompts + nd, order = p.order + PromptOrderEntry(nd.identifier, true))
            }
            editing = null
        })
    }
    if (options) PromptOptionsDialog(pm, onDismiss = { options = false }, onSave = { np -> updatePm { np }; options = false })
}

@Composable
private fun PromptEditDialog(d: PromptDefinition, isNew: Boolean, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (PromptDefinition) -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    var role by remember { mutableStateOf(d.role) }
    var content by remember { mutableStateOf(d.content) }
    var absolute by remember { mutableStateOf(d.injectionPosition == 1) }
    var depth by remember { mutableStateOf(d.injectionDepth.toString()) }
    var order by remember { mutableStateOf(d.injectionOrder.toString()) }
    var forbid by remember { mutableStateOf(d.forbidOverrides) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(if (isNew) "New prompt" else "Edit prompt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name", name, { name = it })
                if (d.marker) {
                    Text("This is a built-in section; you can only enable, disable and reorder it.", style = MaterialTheme.typography.bodySmall)
                } else {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("system", "user", "assistant").forEachIndexed { i, r ->
                            SegmentedButton(role == r, { role = r }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(r) }
                        }
                    }
                    Field("Content", content, { content = it }, singleLine = false, minLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(absolute, { absolute = it }); Spacer(Modifier.width(8.dp)); Text("Inject into chat at depth")
                    }
                    if (absolute) {
                        Field("Depth from end (0 = after last message)", depth, { depth = it }, number = true)
                        Field("Order (lower first)", order, { order = it }, number = true)
                    }
                    if (d.identifier == "main" || d.identifier == "jailbreak") Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(forbid, { forbid = it }); Spacer(Modifier.width(8.dp)); Text("Ignore character card override")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(d.copy(
                    name = name.trim(), role = role, content = content,
                    injectionPosition = if (absolute) 1 else 0,
                    injectionDepth = depth.toIntOrNull()?.coerceAtLeast(0) ?: d.injectionDepth,
                    injectionOrder = order.toIntOrNull() ?: d.injectionOrder, forbidOverrides = forbid,
                ))
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PromptOptionsDialog(p: PromptManagerPreset, onDismiss: () -> Unit, onSave: (PromptManagerPreset) -> Unit) {
    var impersonation by remember { mutableStateOf(p.impersonationPrompt) }
    var newChat by remember { mutableStateOf(p.newChatPrompt) }
    var newExample by remember { mutableStateOf(p.newExampleChatPrompt) }
    var cont by remember { mutableStateOf(p.continueNudgePrompt) }
    var sendIfEmpty by remember { mutableStateOf(p.sendIfEmpty) }
    var scenario by remember { mutableStateOf(p.scenarioFormat) }
    var personality by remember { mutableStateOf(p.personalityFormat) }
    var squash by remember { mutableStateOf(p.squashSystemMessages) }
    var names by remember { mutableStateOf(p.namesBehavior == 2) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Preset options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Impersonation prompt", impersonation, { impersonation = it }, singleLine = false, minLines = 2)
                Field("Continue nudge", cont, { cont = it }, singleLine = false, minLines = 2)
                Field("New chat prompt", newChat, { newChat = it })
                Field("New example chat prompt", newExample, { newExample = it })
                Field("Send if empty", sendIfEmpty, { sendIfEmpty = it })
                Field("Scenario format", scenario, { scenario = it })
                Field("Personality format", personality, { personality = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(squash, { squash = it }); Spacer(Modifier.width(8.dp)); Text("Squash system messages") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(names, { names = it }); Spacer(Modifier.width(8.dp)); Text("Prefix names in message text") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(p.copy(
                    impersonationPrompt = impersonation, continueNudgePrompt = cont, newChatPrompt = newChat,
                    newExampleChatPrompt = newExample, sendIfEmpty = sendIfEmpty, scenarioFormat = scenario,
                    personalityFormat = personality, squashSystemMessages = squash, namesBehavior = if (names) 2 else 0,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
