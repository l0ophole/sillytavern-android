package app.sillytavern.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    singleLine: Boolean = true, minLines: Int = 1, number: Boolean = false, secret: Boolean = false,
) = OutlinedTextField(
    value = value, onValueChange = onChange, label = { Text(label) },
    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    singleLine = singleLine, minLines = minLines,
    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text),
    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) =
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
        actions = actions,
    )
