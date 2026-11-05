package com.elianfabian.parikt.console

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsolePageBar(title: String, onBack: () -> Unit) {
    TopAppBar(title = { Text(title) }, windowInsets = WindowInsets(0, 0, 0, 0), navigationIcon = {
        TextButton(onClick = onBack, modifier = Modifier.testTag("back-to-console")) { Text("Back") }
    })
}

@Composable
internal fun ConsoleSettingsScreen(state: ConsoleState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stack by remember(state.settings) { mutableStateOf(state.settings.stackSize.toString()) }
    var maximum by remember(state.settings) { mutableStateOf(state.settings.maximumStackSize.toString()) }
    Column(Modifier.fillMaxSize()) {
        ConsolePageBar("Settings", onBack)
        ConsoleNotice(state)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PARI stack", style = MaterialTheme.typography.titleMedium)
            Text("Applying these settings restarts PARI and clears its variables and functions. Displayed results and saved scripts remain.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = stack, onValueChange = { stack = it }, label = { Text("parisize (bytes)") },
                supportingText = { Text("Initial stack size. Default: 4,000,000 bytes.") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("stack-size"))
            OutlinedTextField(value = maximum, onValueChange = { maximum = it }, label = { Text("parisizemax (bytes)") },
                supportingText = { Text("0 keeps automatic stack growth disabled. Otherwise, use at least parisize.") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("maximum-stack-size"))
            Button(onClick = { scope.launch { if (state.applySettings(stack, maximum)) onBack() } }, enabled = !state.starting,
                modifier = Modifier.testTag("apply-settings")) { Text("Apply and restart") }
        }
    }
}

@Composable
internal fun ConsoleScriptsDialog(state: ConsoleState, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var originalName by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedScript?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("scripts-dialog"),
        title = { Text("Custom Scripts & Functions") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                // Scroll the editor on small screens/with the IME; retain a separate saved list.
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ConsoleNotice(state)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Script Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("script-name"))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("PARI/GP Code") }, singleLine = false, maxLines = 4,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().testTag("script-code"))
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.align(androidx.compose.ui.Alignment.End), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (originalName != null) TextButton(onClick = { originalName = null; name = ""; code = ""; state.dismissNotice() }) { Text("Cancel edit") }
                        Button(onClick = {
                            if (state.saveScript(originalName, name, code)) { originalName = null; name = ""; code = "" }
                        }, modifier = Modifier.testTag("save-script")) { Text(if (originalName == null) "Save Script" else "Update Script") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Saved Scripts:", style = MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (state.scripts.isEmpty()) item { Text("No saved scripts yet.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(state.scripts, key = { it.name }) { script ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                            Column(Modifier.padding(8.dp)) {
                                Text(script.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                Text(script.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.align(androidx.compose.ui.Alignment.End)) {
                                    IconButton(onClick = { state.command = script.code; onDismiss() }, modifier = Modifier.testTag("load-script-${script.name}")) { Icon(ConsoleIcons.Load, "Load ${script.name}") }
                                    IconButton(onClick = { scope.launch { state.command = script.code; state.runCommand(); onDismiss() } }, enabled = state.ready,
                                        modifier = Modifier.testTag("run-script-${script.name}")) { Icon(ConsoleIcons.Play, "Run ${script.name}") }
                                    IconButton(onClick = { originalName = script.name; name = script.name; code = script.code; state.dismissNotice() },
                                        modifier = Modifier.testTag("edit-script-${script.name}")) { Icon(ConsoleIcons.Edit, "Edit ${script.name}") }
                                    IconButton(onClick = { pendingDelete = script }, modifier = Modifier.testTag("delete-script-${script.name}")) { Icon(ConsoleIcons.Delete, "Delete ${script.name}", tint = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-scripts")) { Text("Close") } },
    )
    pendingDelete?.let { script ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete script?") }, text = { Text(script.name) },
            confirmButton = { TextButton(onClick = { state.deleteScript(script); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }
}
