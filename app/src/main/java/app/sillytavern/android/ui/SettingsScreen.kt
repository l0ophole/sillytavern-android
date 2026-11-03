package app.sillytavern.android.ui

import app.sillytavern.android.api.BackendException
import app.sillytavern.android.api.ModelInfo
import app.sillytavern.android.api.ModelLister
import app.sillytavern.android.api.Provider
import app.sillytavern.android.api.Providers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
fun SettingsScreen(c: AppContainer, onBack: () -> Unit, onPromptManager: () -> Unit, onPresets: () -> Unit, onWorlds: () -> Unit, onQuickReplies: () -> Unit, onRegex: () -> Unit, onTags: () -> Unit = {}, onAppearance: () -> Unit = {}, onMedia: () -> Unit = {}, onGallery: () -> Unit = {}, onMemory: () -> Unit = {}) {
    val s by c.settings.settings.collectAsState()
    var editConn by remember { mutableStateOf<ConnectionProfile?>(null) }
    var editPersona by remember { mutableStateOf<Persona?>(null) }

    Scaffold(topBar = { BackTopBar(tr("Settings"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = onAppearance) { Text(tr("Appearance & language")) }
            TextButton(onClick = onMedia) { Text(tr("Speech, images & attachments")) }
            TextButton(onClick = onGallery) { Text(tr("Gallery")) }
            TextButton(onClick = onMemory) { Text(tr("Memory, data bank & expressions")) }
            SectionHeader(tr("Connections")) { editConn = ConnectionProfile() }
            if (s.connections.isEmpty()) Text("No connections. Tap Add to configure an API.", Modifier.padding(vertical = 8.dp))
            s.connections.forEach { p ->
                ItemRow(p.name, "${p.type.name.lowercase().replace('_', ' ')} · ${p.model.ifBlank { "no model" }}",
                    selected = p.id == s.activeConnection?.id,
                    onSelect = { c.settings.update { it.copy(activeConnectionId = p.id) } },
                    onEdit = { editConn = p },
                    onDelete = { c.secrets.remove("key:${p.id}"); c.settings.update { it.copy(connections = it.connections - p) } })
            }
            Spacer(Modifier.height(16.dp))
            SectionHeader(tr("Personas")) { editPersona = Persona(name = "") }
            s.personas.forEach { p ->
                ItemRow(p.name + if (p.id == s.defaultPersonaId) "  ★" else "", p.title.ifBlank { p.description.take(60) }, p.id == s.activePersona.id,
                    onSelect = { c.chatController.selectPersona(p.id) },
                    onEdit = { editPersona = p },
                    onDelete = if (s.personas.size > 1) ({
                        c.settings.update { st -> st.copy(personas = st.personas - p, defaultPersonaId = st.defaultPersonaId.takeIf { it != p.id }, activePersonaId = st.activePersonaId.takeIf { it != p.id }) }
                    }) else null,
                    leading = { PersonaAvatar(c, p, 36) })
            }
            Spacer(Modifier.height(16.dp))
            Text(tr("Prompts"), style = MaterialTheme.typography.titleLarge)
            Row {
                TextButton(onClick = onPromptManager) { Text("Prompt Manager (${s.activePromptManager.name})") }
            }
            Row {
                TextButton(onClick = onPresets) { Text("Instruct, context, system prompt & samplers") }
            }
            Row {
                TextButton(onClick = onWorlds) { Text("World Info (${s.globalWorldBooks.size} global)") }
            }
            Row {
                TextButton(onClick = onTags) { Text("Tags & folders (${s.tags.size})") }
            }
            Row {
                TextButton(onClick = onQuickReplies) { Text("Quick Replies (${s.quickReplies.globalSets.size} global)") }
            }
            Row {
                TextButton(onClick = onRegex) { Text("Regex scripts") }
            }
            if (s.globalVariables.isNotEmpty()) {
                Text("Global variables", style = MaterialTheme.typography.titleMedium)
                s.globalVariables.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("$k = ${v.take(60)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { c.settings.update { st -> st.copy(globalVariables = st.globalVariables - k) } }) { Text("Delete") }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    editConn?.let { ConnectionDialog(c, it) { editConn = null } }
    editPersona?.let { p -> PersonaEditDialog(c, p) { editPersona = null } }
}

@Composable
private fun SectionHeader(title: String, onAdd: () -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onAdd) { Text(tr("Add")) }
    }

@Composable
private fun ItemRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: (() -> Unit)?, leading: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect)
        leading()
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onEdit) { Text(tr("Edit")) }
        if (onDelete != null) TextButton(onClick = onDelete) { Text(tr("Delete")) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDialog(c: AppContainer, p: ConnectionProfile, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var provider by remember { mutableStateOf(Providers.forProfile(p)) }
    var effort by remember { mutableStateOf(p.reasoning.effort) }
    var budget by remember { mutableStateOf(if (p.reasoning.budgetTokens > 0) p.reasoning.budgetTokens.toString() else "") }
    var providerMenu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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

    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Connection") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Field("Name", name, { name = it })
                Text("Provider", style = MaterialTheme.typography.labelMedium)
                Box {
                    OutlinedButton(onClick = { providerMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(provider.label) }
                    DropdownMenu(providerMenu, { providerMenu = false }) {
                        Providers.all.forEach { pr ->
                            DropdownMenuItem(text = { Text(pr.label) }, onClick = {
                                providerMenu = false
                                if (pr.id != provider.id) {
                                    if (url.isBlank() || url == provider.baseUrl) url = pr.baseUrl
                                    model = ""; models = null
                                    provider = pr
                                }
                            })
                        }
                    }
                }
                Field("Base URL (without /chat/completions)", url, { url = it })
                Field("Model", model, { model = it })
                OutlinedButton(
                    enabled = !loadingModels && url.isNotBlank(),
                    onClick = {
                        loadingModels = true; modelError = null
                        val probe = p.copy(type = provider.type, provider = provider.id, baseUrl = url.trim(), model = model.trim())
                        scope.launch {
                            try { models = ModelLister.fetch(probe, key.trim()); if (models!!.isEmpty()) modelError = "No models returned" }
                            catch (e: BackendException) { modelError = e.message }
                            catch (e: Exception) { modelError = e.message ?: e.javaClass.simpleName }
                            loadingModels = false
                        }
                    },
                ) { Text(if (loadingModels) "Loading…" else "Fetch models") }
                modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Field(if (provider.keyRequired) "API key" else "API key (optional)", key, { key = it }, secret = true)
                if (provider.type == BackendType.HORDE) Text("Leave the key empty for anonymous access (slow queue).", style = MaterialTheme.typography.bodySmall)
                if (provider.type == BackendType.NOVELAI) Text("NovelAI uses the text prompt with your instruct/context templates.", style = MaterialTheme.typography.bodySmall)
                if (provider.type != BackendType.TEXT_COMPLETION && provider.type != BackendType.KOBOLD && provider.type != BackendType.NOVELAI && provider.type != BackendType.HORDE) {
                    Text("Reasoning effort", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("auto", "min", "low", "medium", "high", "max").forEach { e ->
                            FilterChip(effort == e, { effort = e }, label = { Text(e) })
                        }
                    }
                    Field("Thinking budget tokens (0 = off)", budget, { budget = it.filter(Char::isDigit) }, number = true)
                }
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
                    name = name.trim(), type = provider.type, provider = provider.id, baseUrl = url.trim(), model = model.trim(),
                    reasoning = ReasoningSettings(effort, budget.toIntOrNull() ?: 0),
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
