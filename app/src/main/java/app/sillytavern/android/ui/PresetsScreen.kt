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
import app.sillytavern.android.data.AppSettings
import app.sillytavern.android.data.PresetIo
import app.sillytavern.android.data.PresetKind
import app.sillytavern.android.prompt.model.*

/** One editable text/number field of a preset. */
private class FieldSpec<T>(val label: String, val get: (T) -> String, val set: (T, String) -> T, val multiline: Boolean = false, val number: Boolean = false)

/** Describes how to list/select/edit one kind of preset in [AppSettings]. */
private class Kind<T>(
    val io: PresetKind,
    val items: (AppSettings) -> List<T>,
    val name: (T) -> String,
    val rename: (T, String) -> T,
    val activeName: (AppSettings) -> String,
    val setActive: (AppSettings, String) -> AppSettings,
    val put: (AppSettings, List<T>) -> AppSettings,
    val fields: List<FieldSpec<T>>,
)

private fun <T> text(label: String, get: (T) -> String, set: (T, String) -> T, multiline: Boolean = false) = FieldSpec(label, get, set, multiline)
private fun <T> num(label: String, get: (T) -> Any, set: (T, Double) -> T) =
    FieldSpec<T>(label, { get(it).toString() }, { t, v -> v.toDoubleOrNull()?.let { set(t, it) } ?: t }, number = true)

private val instructKind = Kind<InstructTemplate>(
    PresetKind.INSTRUCT, { it.instructTemplates }, { it.name }, { t, n -> t.copy(name = n) },
    { it.activeInstruct?.name ?: it.activeInstructName ?: it.instructTemplates.firstOrNull()?.name.orEmpty() },
    { s, n -> s.copy(activeInstructName = n) }, { s, l -> s.copy(instructTemplates = l) },
    listOf(
        text("Input sequence", { it.inputSequence }, { t, v -> t.copy(inputSequence = v) }),
        text("Input suffix", { it.inputSuffix }, { t, v -> t.copy(inputSuffix = v) }),
        text("Output sequence", { it.outputSequence }, { t, v -> t.copy(outputSequence = v) }),
        text("Output suffix", { it.outputSuffix }, { t, v -> t.copy(outputSuffix = v) }),
        text("System sequence", { it.systemSequence }, { t, v -> t.copy(systemSequence = v) }),
        text("System suffix", { it.systemSuffix }, { t, v -> t.copy(systemSuffix = v) }),
        text("First output sequence", { it.firstOutputSequence }, { t, v -> t.copy(firstOutputSequence = v) }),
        text("Last output sequence", { it.lastOutputSequence }, { t, v -> t.copy(lastOutputSequence = v) }),
        text("Stop sequence", { it.stopSequence }, { t, v -> t.copy(stopSequence = v) }),
        text("Story string prefix", { it.storyStringPrefix }, { t, v -> t.copy(storyStringPrefix = v) }),
        text("Story string suffix", { it.storyStringSuffix }, { t, v -> t.copy(storyStringSuffix = v) }),
        text("Names behavior (none / force / always)", { it.namesBehavior }, { t, v -> t.copy(namesBehavior = v.trim().ifEmpty { "none" }) }),
    ),
)

private val contextKind = Kind<ContextTemplate>(
    PresetKind.CONTEXT, { it.contextTemplates }, { it.name }, { t, n -> t.copy(name = n) },
    { it.activeContext.name }, { s, n -> s.copy(activeContextName = n) }, { s, l -> s.copy(contextTemplates = l) },
    listOf(
        text("Story string", { it.storyString }, { t, v -> t.copy(storyString = v) }, multiline = true),
        text("Example separator", { it.exampleSeparator }, { t, v -> t.copy(exampleSeparator = v) }),
        text("Chat start", { it.chatStart }, { t, v -> t.copy(chatStart = v) }),
    ),
)

private val sysPromptKind = Kind<SystemPromptPreset>(
    PresetKind.SYSTEM_PROMPT, { it.systemPrompts }, { it.name }, { t, n -> t.copy(name = n) },
    { it.activeSystemPrompt?.name.orEmpty() }, { s, n -> s.copy(activeSystemPromptName = n) }, { s, l -> s.copy(systemPrompts = l) },
    listOf(
        text("Content", { it.content }, { t, v -> t.copy(content = v) }, multiline = true),
        text("Post-history instructions", { it.postHistory }, { t, v -> t.copy(postHistory = v) }, multiline = true),
    ),
)

private val samplerKind = Kind<TextGenSamplerPreset>(
    PresetKind.SAMPLER, { it.samplerPresets }, { it.name }, { t, n -> t.copy(name = n) },
    { it.activeSamplerPreset.name }, { s, n -> s.copy(activeSamplerPresetName = n) }, { s, l -> s.copy(samplerPresets = l) },
    listOf(
        num("Temperature", { it.temp }, { t, v -> t.copy(temp = v) }),
        num("Top P", { it.topP }, { t, v -> t.copy(topP = v) }),
        num("Top K", { it.topK }, { t, v -> t.copy(topK = v.toInt()) }),
        num("Top A", { it.topA }, { t, v -> t.copy(topA = v) }),
        num("Min P", { it.minP }, { t, v -> t.copy(minP = v) }),
        num("TFS", { it.tfs }, { t, v -> t.copy(tfs = v) }),
        num("Typical P", { it.typicalP }, { t, v -> t.copy(typicalP = v) }),
        num("Repetition penalty", { it.repPen }, { t, v -> t.copy(repPen = v) }),
        num("Repetition penalty range", { it.repPenRange }, { t, v -> t.copy(repPenRange = v.toInt()) }),
        num("Frequency penalty", { it.freqPen }, { t, v -> t.copy(freqPen = v) }),
        num("Presence penalty", { it.presencePen }, { t, v -> t.copy(presencePen = v) }),
        num("DRY multiplier", { it.dryMultiplier }, { t, v -> t.copy(dryMultiplier = v) }),
        num("DRY base", { it.dryBase }, { t, v -> t.copy(dryBase = v) }),
        num("DRY allowed length", { it.dryAllowedLength }, { t, v -> t.copy(dryAllowedLength = v.toInt()) }),
        num("Mirostat mode (0 = off)", { it.mirostatMode }, { t, v -> t.copy(mirostatMode = v.toInt()) }),
        num("Smoothing factor", { it.smoothingFactor }, { t, v -> t.copy(smoothingFactor = v) }),
        num("XTC threshold", { it.xtcThreshold }, { t, v -> t.copy(xtcThreshold = v) }),
        num("XTC probability", { it.xtcProbability }, { t, v -> t.copy(xtcProbability = v) }),
        num("Seed (-1 = random)", { it.seed }, { t, v -> t.copy(seed = v.toLong()) }),
        text("Banned strings (one per line)", { it.bannedTokens }, { t, v -> t.copy(bannedTokens = v) }, multiline = true),
        text("Grammar", { it.grammarString }, { t, v -> t.copy(grammarString = v) }, multiline = true),
    ),
)

/** Instruct / context / system-prompt / sampler presets, all importable and exportable as ST JSON. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsScreen(c: AppContainer, onBack: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    val titles = listOf("Instruct", "Context", "System", "Sampler")
    Scaffold(topBar = { BackTopBar("Templates & samplers", onBack) }) { pad ->
        Column(Modifier.padding(pad)) {
            SecondaryScrollableTabRow(tab, edgePadding = 8.dp) {
                titles.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
            }
            Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                if (tab == 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Switch(s.instructEnabled, { on -> c.settings.update { it.copy(instructEnabled = on) } })
                        Spacer(Modifier.width(8.dp))
                        Text("Use instruct template for text-completion connections")
                    }
                }
                when (tab) {
                    0 -> KindPanel(c, instructKind)
                    1 -> KindPanel(c, contextKind)
                    2 -> KindPanel(c, sysPromptKind)
                    else -> KindPanel(c, samplerKind)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun <T : Any> KindPanel(c: AppContainer, k: Kind<T>) {
    val s by c.settings.settings.collectAsState()
    val ctx = LocalContext.current
    val items = k.items(s)
    val active = k.activeName(s)
    var editing by remember(k) { mutableStateOf<T?>(null) }
    var message by remember(k) { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) message = runCatching {
            PresetIo.importPreset(c.settings, k.io, uri.lastPathSegment.orEmpty(), String(ctx.readUri(uri))).getOrThrow()
        }.fold({ "Imported \"$it\"" }, { "Import failed: ${it.message}" })
    }
    var exportName by remember(k) { mutableStateOf("") }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val text = PresetIo.exportPreset(c.settings.settings.value, k.io, exportName)
        if (uri != null && text != null) message = runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        }.fold({ "Exported" }, { "Export failed: ${it.message}" })
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${k.io.label} presets", style = MaterialTheme.typography.titleMedium)
        Row {
            TextButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }) { Text("Import") }
            items.firstOrNull { k.name(it) == active }?.let { cur ->
                TextButton(onClick = {
                    var n = "${k.name(cur)} copy"; while (items.any { k.name(it) == n }) n += "+"
                    c.settings.update { st -> k.setActive(k.put(st, k.items(st) + k.rename(cur, n)), n) }
                }) { Text("Duplicate") }
            }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    items.forEach { item ->
        val n = k.name(item)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(n == active, { c.settings.update { st -> k.setActive(st, n) } })
            Text(n, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { editing = item }) { Text("Edit") }
            TextButton(onClick = { exportName = n; exporter.launch("$n.json") }) { Text("Export") }
            if (items.size > 1) TextButton(onClick = {
                c.settings.update { st -> k.setActive(k.put(st, k.items(st).filter { k.name(it) != n }), k.items(st).first { k.name(it) != n }.let(k.name)) }
            }) { Text("Delete") }
        }
    }

    editing?.let { item ->
        var draft by remember(item) { mutableStateOf(item) }
        var name by remember(item) { mutableStateOf(k.name(item)) }
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text("Edit ${k.io.label.lowercase()} preset") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Field("Name", name, { name = it })
                    k.fields.forEach { f ->
                        var v by remember(item, f) { mutableStateOf(f.get(item)) }
                        Field(f.label, v, { v = it; draft = f.set(draft, it) }, singleLine = !f.multiline, minLines = if (f.multiline) 3 else 1, number = f.number)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val old = k.name(item)
                    val saved = k.rename(draft, name.trim())
                    c.settings.update { st ->
                        val list = k.items(st).map { if (k.name(it) == old) saved else it }
                        val st2 = k.put(st, list)
                        if (k.activeName(st) == old) k.setActive(st2, name.trim()) else st2
                    }
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
