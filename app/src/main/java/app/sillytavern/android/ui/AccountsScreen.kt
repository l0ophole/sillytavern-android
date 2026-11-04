package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.sillytavern.android.AppContainer
import app.sillytavern.android.SillyTavernApp
import app.sillytavern.android.data.Account
import app.sillytavern.android.data.AccountException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Dialog {
    data object Add : Dialog
    data class Switch(val a: Account) : Dialog
    data class Passcode(val a: Account) : Dialog
    data class Delete(val a: Account) : Dialog
}

/** Local users and the passcode. [onSwitched] must recreate the UI: every repository belongs to one user. */
@Composable
fun AccountsScreen(c: AppContainer, onBack: () -> Unit, onSwitched: () -> Unit) {
    val app = LocalContext.current.applicationContext as SillyTavernApp
    var version by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val users = remember(version) { c.accounts.list() }

    Scaffold(topBar = { BackTopBar(tr("Users & passcode"), onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            Text(tr("Users are separate sets of characters, chats, settings and API keys on this device. A passcode keeps casual users out, but the files on the device are not encrypted by it."), style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = { dialog = Dialog.Add }) { Text(tr("Add user")) }
            users.forEach { a ->
                val current = a.handle == c.account.handle
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.name + if (current) "  ✓" else "", style = MaterialTheme.typography.titleMedium)
                        Text(if (a.locked) tr("Passcode set") else tr("No passcode"), style = MaterialTheme.typography.labelSmall)
                        Row {
                            if (!current) TextButton(onClick = { if (a.locked) dialog = Dialog.Switch(a) else { app.switchTo(a.handle); onSwitched() } }) { Text(tr("Switch")) }
                            TextButton(onClick = { dialog = Dialog.Passcode(a) }) { Text(if (a.locked) tr("Change passcode") else tr("Set passcode")) }
                            if (!current && a.handle != "default") TextButton(onClick = { dialog = Dialog.Delete(a) }) { Text(tr("Delete")) }
                        }
                    }
                }
            }
            if (c.account.locked) TextButton(onClick = { app.lock(); onSwitched() }) { Text(tr("Lock now")) }
        }
    }

    when (val d = dialog) {
        null -> {}
        Dialog.Add -> {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { dialog = null }, title = { Text(tr("New user")) },
                text = { OutlinedTextField(name, { name = it }, label = { Text(tr("Name")) }, singleLine = true) },
                confirmButton = {
                    TextButton(enabled = name.isNotBlank(), onClick = {
                        try { c.accounts.create(name); version++; dialog = null } catch (e: AccountException) { message = e.message; dialog = null }
                    }) { Text(tr("Add")) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
            )
        }
        is Dialog.Switch -> CodeDialog(tr("Passcode for ${d.a.name}"), tr("Open"), { dialog = null }) { code ->
            if (withContext(Dispatchers.Default) { c.accounts.verify(d.a.handle, code) }) {
                app.switchTo(d.a.handle); withContext(Dispatchers.Default) { app.unlock(code) }; dialog = null; onSwitched(); true
            } else false
        }
        is Dialog.Delete -> CodeDialog(
            tr("Delete ${d.a.name} and all their data?"), tr("Delete"), { dialog = null }, needsCode = d.a.locked,
        ) { code ->
            val ok = try { withContext(Dispatchers.Default) { c.accounts.delete(d.a.handle, code) } } catch (e: AccountException) { message = e.message; true }
            if (ok) { version++; dialog = null }
            ok
        }
        is Dialog.Passcode -> {
            var current by remember { mutableStateOf("") }
            var next by remember { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            var saving by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            AlertDialog(
                onDismissRequest = { if (!saving) dialog = null }, title = { Text(tr("Passcode for ${d.a.name}")) },
                text = {
                    Column {
                        if (d.a.locked) OutlinedTextField(current, { current = it; wrong = false }, label = { Text(tr("Current passcode")) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), isError = wrong, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        OutlinedTextField(next, { next = it }, label = { Text(tr("New passcode (empty removes it)")) }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    }
                },
                confirmButton = {
                    TextButton(enabled = !saving, onClick = {
                        saving = true
                        scope.launch {
                            // PBKDF2 takes seconds on a phone: never on the UI thread.
                            val ok = withContext(Dispatchers.Default) { c.accounts.setPasscode(d.a.handle, next, current.takeIf { d.a.locked }) }
                            if (ok) {
                                if (d.a.handle == c.account.handle) {
                                    app.reload()
                                    if (next.isNotEmpty()) withContext(Dispatchers.Default) { app.unlock(next) }
                                    onSwitched()
                                }
                                version++; dialog = null
                            } else wrong = true
                            saving = false
                        }
                    }) { Text(tr(if (saving) "Saving…" else "Save")) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr("Cancel")) } },
            )
        }
    }
}

/** A passcode prompt. [onSubmit] returns false for a wrong code. */
@Composable
fun CodeDialog(title: String, action: String, onDismiss: () -> Unit, needsCode: Boolean = true, onSubmit: suspend (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            if (needsCode) OutlinedTextField(code, { code = it; wrong = false }, label = { Text(tr("Passcode")) }, singleLine = true, isError = wrong,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch { if (!onSubmit(code)) wrong = true; busy = false } }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    )
}

/** Shown instead of the app while the open user's passcode has not been entered. [onUnlocked] rebuilds the UI. */
@Composable
fun LockScreen(userName: String, others: List<Account>, onUnlock: suspend (String) -> Boolean, onSwitch: (Account) -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(32.dp).fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(userName, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(code, { code = it; wrong = false }, label = { Text(tr("Passcode")) }, singleLine = true, isError = wrong,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.padding(vertical = 16.dp))
            Button(enabled = !busy, onClick = { busy = true; scope.launch { if (!onUnlock(code)) wrong = true; busy = false } }) { Text(tr("Unlock")) }
            if (wrong) Text(tr("Wrong passcode"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            others.forEach { TextButton(onClick = { onSwitch(it) }) { Text("${tr("Switch to")} ${it.name}") } }
        }
    }
}
