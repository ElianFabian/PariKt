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
import androidx.compose.ui.unit.sp
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
internal fun ConsoleScriptsScreen(state: ConsoleState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var originalName by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedScript?>(null) }
    Column(Modifier.fillMaxSize()) {
        ConsolePageBar(if (editing) "Edit script" else "Custom scripts") {
            if (editing) { editing = false; state.dismissNotice() } else onBack()
        }
        ConsoleNotice(state)
        if (editing) {
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("script-name"))
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("GP code") }, minLines = 5, maxLines = 12,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().testTag("script-code"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (state.saveScript(originalName, name, code)) editing = false }, modifier = Modifier.testTag("save-script")) { Text("Save script") }
                    TextButton(onClick = { editing = false; state.dismissNotice() }) { Text("Cancel") }
                }
                Text("Scripts are saved on this device/browser. Load one to edit the command, or run it in the current PARI session.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = { originalName = null; name = ""; code = ""; editing = true; state.dismissNotice() }, modifier = Modifier.testTag("new-script")) { Text("New script") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.scripts.isEmpty()) item { Text("No saved scripts yet. Save GP commands or function definitions to reuse them.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.scripts, key = { it.name }) { script ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(script.name, style = MaterialTheme.typography.titleMedium)
                            Text(script.code, fontFamily = FontFamily.Monospace, fontSize = 14.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { state.command = script.code; onBack() }) { Text("Load") }
                                TextButton(onClick = { scope.launch { state.command = script.code; state.runCommand(); onBack() } }, enabled = state.ready) { Text("Run") }
                                TextButton(onClick = { originalName = script.name; name = script.name; code = script.code; editing = true; state.dismissNotice() }) { Text("Edit") }
                                TextButton(onClick = { pendingDelete = script }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { script ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete script?") }, text = { Text(script.name) },
            confirmButton = { TextButton(onClick = { state.deleteScript(script); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }
}
