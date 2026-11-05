package com.elianfabian.parikt.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class ConsolePage { Console, Settings, Scripts }

@Composable
fun ConsoleApp(persistPreferences: Boolean = true) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        val store = if (persistPreferences) rememberConsoleStore() else remember { MemoryConsoleStore() }
        val state = remember(store) { ConsoleState(store = store) }
        LaunchedEffect(state) { state.initialize() }
        var page by remember { mutableStateOf(ConsolePage.Console) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                    when (page) {
                        ConsolePage.Console -> ConsoleScreen(state, { page = ConsolePage.Settings }, { page = ConsolePage.Scripts })
                        ConsolePage.Settings -> ConsoleSettingsScreen(state) { page = ConsolePage.Console }
                        ConsolePage.Scripts -> ConsoleScriptsScreen(state) { page = ConsolePage.Console }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsoleScreen(state: ConsoleState, onSettings: () -> Unit = {}, onScripts: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // PARI calls, including restart and script execution, stay on the initialization UI thread.
    val runCommand: () -> Unit = { scope.launch { state.runCommand() } }
    val clipboard = LocalClipboardManager.current
    var menuOpen by remember { mutableStateOf(false) }
    var showCompletion by remember { mutableStateOf(false) }
    val historyScroll = rememberLazyListState()
    LaunchedEffect(state.entries.size) { if (state.entries.isNotEmpty()) historyScroll.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("PariKt", style = MaterialTheme.typography.titleLarge)
                    Text(state.status, style = MaterialTheme.typography.labelSmall,
                        color = if (state.startupError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("engine-status"))
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("console-menu")) {
                    Text("⋮", fontSize = 26.sp, modifier = Modifier.semantics { contentDescription = "Console menu" })
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Custom scripts") }, onClick = { menuOpen = false; onScripts() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onSettings() })
                    DropdownMenuItem(text = { Text("Restart engine (clear variables)") }, enabled = !state.starting,
                        onClick = { menuOpen = false; scope.launch { state.restart() } })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Copy output") }, enabled = state.entries.isNotEmpty(),
                        onClick = { menuOpen = false; clipboard.setText(AnnotatedString(state.outputText())) })
                    DropdownMenuItem(text = { Text("Clear output") }, enabled = state.entries.isNotEmpty(),
                        modifier = Modifier.testTag("clear-output"), onClick = { menuOpen = false; state.clearOutput() })
                    HorizontalDivider()
                    listOf("Factor a number" to "factor(123456)", "Check a prime" to "isprime(2^127-1)", "Pi" to "Pi").forEach { (label, command) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; state.command = command; showCompletion = false })
                    }
                }
            },
        )
        state.startupError?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    SelectionContainer { Text(message, color = MaterialTheme.colorScheme.onErrorContainer) }
                    TextButton(onClick = { scope.launch { state.restart() } }, enabled = !state.starting) { Text("Retry initialization") }
                }
            }
        }
        ConsoleNotice(state)
        OutlinedTextField(
            value = state.input,
            onValueChange = { state.updateInput(it); showCompletion = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).testTag("command-input")
                .onPreviewKeyEvent { event ->
                    when {
                        event.type != KeyEventType.KeyDown -> false
                        event.key == Key.Enter && !event.isShiftPressed -> { showCompletion = false; runCommand(); true }
                        event.key == Key.Tab && showCompletion && state.completions.isNotEmpty() -> { state.complete(state.completions.first()); showCompletion = false; true }
                        event.key == Key.Escape && showCompletion -> { showCompletion = false; true }
                        else -> false
                    }
                },
            placeholder = { Text("Enter a GP command…", fontFamily = FontFamily.Monospace) },
            minLines = 1, maxLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { showCompletion = false; runCommand() }),
        )
        if (showCompletion && state.completions.isNotEmpty()) {
            // Inline suggestions keep the keyboard and command field focused on every target.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.completions.forEach { completion ->
                    SuggestionChip(onClick = { state.complete(completion); showCompletion = false },
                        label = { Text(completion, fontFamily = FontFamily.Monospace) }, modifier = Modifier.testTag("completion-$completion"))
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { state.command = ""; showCompletion = false }, modifier = Modifier.weight(1f).testTag("clear-input"), contentPadding = PaddingValues(8.dp)) { Text("Clear") }
                FilledTonalButton(onClick = { state.navigateHistory(-1); showCompletion = false }, enabled = state.canGoBack,
                    modifier = Modifier.weight(1f).testTag("previous-command"), contentPadding = PaddingValues(8.dp)) { Text("Previous") }
                FilledTonalButton(onClick = { state.navigateHistory(1); showCompletion = false }, enabled = state.canGoForward,
                    modifier = Modifier.weight(1f).testTag("next-command"), contentPadding = PaddingValues(8.dp)) { Text("Next") }
                Button(onClick = { showCompletion = false; runCommand() }, enabled = state.ready && state.command.isNotBlank(),
                    modifier = Modifier.weight(1f).testTag("run-command"), contentPadding = PaddingValues(8.dp)) { Text("Run") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("console-output"), state = historyScroll,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.entries.isEmpty()) item { Text("Command results will appear here.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.entries.asReversed(), key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(Modifier.padding(start = 12.dp, end = 8.dp, bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("? ${entry.command}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).clickable { state.reuse(entry); showCompletion = false }.padding(vertical = 12.dp))
                            IconButton(onClick = { state.removeEntry(entry) }, modifier = Modifier.semantics { contentDescription = "Remove result for ${entry.command}" }) { Text("×", fontSize = 22.sp) }
                        }
                        SelectionContainer {
                            Text(entry.result.trimEnd().ifEmpty { "(no output)" }, fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ConsoleNotice(state: ConsoleState) {
    state.notice?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = state::dismissNotice) { Text("Dismiss") }
            }
        }
    }
}
