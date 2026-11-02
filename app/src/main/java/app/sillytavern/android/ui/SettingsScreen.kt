package app.sillytavern.android.ui

import app.sillytavern.android.api.Providers
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.data.*

@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onPromptManager: () -> Unit, onPresets: () -> Unit, onWorlds: () -> Unit) {
    val s by c.settings.settings.collectAsState()
    var editConn by remember { mutableStateOf<ConnectionProfile?>(null) }
    var editPersona by remember { mutableStateOf<Persona?>(null) }

    Scaffold(topBar = { BackTopBar("Settings", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionHeader("Connections") { editConn = ConnectionProfile() }
            if (s.connections.isEmpty()) Text("No connections. Tap Add to configure an API.", Modifier.padding(vertical = 8.dp))
            s.connections.forEach { p ->
                ItemRow(p.name, "${p.type.name.lowercase().replace('_', ' ')} · ${p.model.ifBlank { "no model" }}",
                    selected = p.id == s.activeConnection?.id,
                    onSelect = { c.settings.update { it.copy(activeConnectionId = p.id) } },
                    onEdit = { editConn = p },
                    onDelete = { c.secrets.remove("key:${p.id}"); c.settings.update { it.copy(connections = it.connections - p) } })
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader("Personas") { editPersona = Persona(name = "") }
            s.personas.forEach { p ->
                ItemRow(p.name, p.description.take(60), p.id == s.activePersona.id,
                    onSelect = { c.settings.update { it.copy(activePersonaId = p.id) } },
                    onEdit = { editPersona = p },
                    onDelete = if (s.personas.size > 1) ({ c.settings.update { it.copy(personas = it.personas - p) } }) else null)
            }
            Spacer(Modifier.height(16.dp))
            Text("Prompts", style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = onPromptManager) { Text("Prompt Manager (${s.activePromptManager.name})") }
            }
            Row {
                TextButton(onClick = onPresets) { Text("Instruct, context, system prompt & samplers") }
            }
            Row {
                TextButton(onClick = onWorlds) { Text("World Info (${s.globalWorldBooks.size} global)") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    editConn?.let { ConnectionDialog(c, it) { editConn = null } }
    editPersona?.let { p ->
        var name by remember { mutableStateOf(p.name) }
        var desc by remember { mutableStateOf(p.description) }
        AlertDialog(
            onDismissRequest = { editPersona = null }, title = { Text("Persona") },
            text = { Column { Field("Name", name, { name = it }); Field("Description", desc, { desc = it }, singleLine = false, minLines = 3) } },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val np = p.copy(name = name.trim(), description = desc)
                    c.settings.update { st -> st.copy(personas = if (st.personas.any { it.id == p.id }) st.personas.map { if (it.id == p.id) np else it } else st.personas + np) }
                    editPersona = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editPersona = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAdd) { Text("Add") }
    }

@Composable
private fun ItemRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: (() -> Unit)?) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onEdit) { Text("Edit") }
        if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDialog(c: AppContainer, p: ConnectionProfile, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var type by remember { mutableStateOf(p.type) }
    var url by remember { mutableStateOf(p.baseUrl) }
    var model by remember { mutableStateOf(p.model) }
    var key by remember { mutableStateOf(c.secrets.get("key:${p.id}") ?: "") }
    val sm = p.sampler
    var temp by remember { mutableStateOf(sm.temperature.toString()) }
    var topP by remember { mutableStateOf(sm.topP.toString()) }
    var topK by remember { mutableStateOf(sm.topK.toString()) }
    var minP by remember { mutableStateOf(sm.minP.toString()) }
    var rep by remember { mutableStateOf(sm.repetitionPenalty.toString()) }
    var ctx by remember { mutableStateOf(sm.maxContextTokens.toString()) }
    var maxTok by remember { mutableStateOf(sm.maxResponseTokens.toString()) }
    var stream by remember { mutableStateOf(sm.stream) }

    fun defaultUrl(t: BackendType) = Providers.ofType(t).first().baseUrl

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name", name, { name = it })
                Text("API type", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BackendType.entries.forEachIndexed { i, t ->
                        SegmentedButton(type == t, {
                            if (url == defaultUrl(type)) url = defaultUrl(t)
                            type = t
                        }, SegmentedButtonDefaults.itemShape(i, BackendType.entries.size)) {
                            Text(Providers.ofType(t).first().label.substringBefore(" ("), maxLines = 1)
                        }
                    }
                }
                Field("Base URL (without /chat/completions)", url, { url = it })
                Field("Model", model, { model = it })
                Field("API key", key, { key = it }, secret = true)
                Field("Temperature", temp, { temp = it }, number = true)
                Field("Top P", topP, { topP = it }, number = true)
                Field("Top K (0 = off)", topK, { topK = it }, number = true)
                Field("Min P (0 = off)", minP, { minP = it }, number = true)
                Field("Repetition penalty (1 = off)", rep, { rep = it }, number = true)
                Field("Context size (tokens)", ctx, { ctx = it }, number = true)
                Field("Max response (tokens)", maxTok, { maxTok = it }, number = true)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(stream, { stream = it }); Spacer(Modifier.width(8.dp)); Text("Stream response") }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && url.isNotBlank(), onClick = {
                val np = p.copy(
                    name = name.trim(), type = type, baseUrl = url.trim(), model = model.trim(),
                    sampler = sm.copy(
                        temperature = temp.toDoubleOrNull() ?: sm.temperature, topP = topP.toDoubleOrNull() ?: sm.topP,
                        topK = topK.toIntOrNull() ?: sm.topK, minP = minP.toDoubleOrNull() ?: sm.minP,
                        repetitionPenalty = rep.toDoubleOrNull() ?: sm.repetitionPenalty,
                        maxContextTokens = ctx.toIntOrNull() ?: sm.maxContextTokens,
                        maxResponseTokens = maxTok.toIntOrNull() ?: sm.maxResponseTokens, stream = stream,
                    ),
                )
                if (key.isBlank()) c.secrets.remove("key:${p.id}") else c.secrets.set("key:${p.id}", key.trim())
                c.settings.update { st ->
                    val exists = st.connections.any { it.id == p.id }
                    st.copy(
                        connections = if (exists) st.connections.map { if (it.id == p.id) np else it } else st.connections + np,
                        activeConnectionId = st.activeConnectionId ?: np.id,
                    )
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
