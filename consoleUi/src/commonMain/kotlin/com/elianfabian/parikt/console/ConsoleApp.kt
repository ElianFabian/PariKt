package com.elianfabian.parikt.console

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class ConsolePage { Console, Settings }

@Composable
fun ConsoleApp(persistPreferences: Boolean = true) {
    MaterialTheme {
        val store = if (persistPreferences) rememberConsoleStore() else remember { MemoryConsoleStore() }
        val state = remember(store) { ConsoleState(store = store) }
        LaunchedEffect(state) { state.initialize() }
        var page by remember { mutableStateOf(ConsolePage.Console) }
        var scriptsOpen by remember { mutableStateOf(false) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
                    when (page) {
                        ConsolePage.Console -> ConsoleScreen(state, { page = ConsolePage.Settings }, { scriptsOpen = true })
                        ConsolePage.Settings -> ConsoleSettingsScreen(state) { page = ConsolePage.Console }
                    }
                }
            }
        }
        if (scriptsOpen) ConsoleScriptsDialog(state) { scriptsOpen = false }
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
    var inputHeight by remember { mutableIntStateOf(0) }
    val historyScroll = rememberLazyListState()
    LaunchedEffect(state.entries.size) { if (state.entries.isNotEmpty()) historyScroll.animateScrollToItem(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(if (state.ready) "PariKt ${state.version}" else "PariKt · ${state.status}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("engine-status").semantics { contentDescription = state.status })
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            actions = {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("console-menu")) {
                    Icon(ConsoleIcons.More, contentDescription = "Console menu")
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
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = { state.updateInput(it); showCompletion = true },
                modifier = Modifier.fillMaxWidth().testTag("command-input").onGloballyPositioned { inputHeight = it.size.height }
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Tab && showCompletion && state.completions.isNotEmpty() -> { state.complete(state.completions.first()); showCompletion = false; true }
                            event.key == Key.Escape && showCompletion -> { showCompletion = false; true }
                            else -> false
                        }
                    },
                placeholder = { Text("Enter a GP command…", fontFamily = FontFamily.Monospace) },
                singleLine = false, minLines = 1, maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                // Request the multiline return key on mobile too; execution belongs to the check button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                keyboardActions = KeyboardActions(onDone = { showCompletion = false }),
            )
            if (showCompletion && state.completions.isNotEmpty()) {
                Popup(alignment = Alignment.TopStart, offset = IntOffset(0, inputHeight + 4),
                    properties = PopupProperties(focusable = false), onDismissRequest = { showCompletion = false }) {
                    Surface(Modifier.fillMaxWidth().heightIn(max = 220.dp), shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(4.dp)) {
                            items(state.completions, key = { it }) { completion ->
                                DropdownMenuItem(onClick = { state.complete(completion); showCompletion = false },
                                    text = { Text(completion, fontFamily = FontFamily.Monospace) },
                                    modifier = Modifier.testTag("completion-$completion"))
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { state.command = ""; showCompletion = false }, modifier = Modifier.weight(1f).testTag("clear-input")) { Icon(ConsoleIcons.Clear, "Clear") }
                FilledTonalIconButton(onClick = { state.navigateHistory(-1); showCompletion = false }, enabled = state.canGoBack,
                    modifier = Modifier.weight(1f).testTag("previous-command")) { Icon(ConsoleIcons.Back, "Previous") }
                FilledTonalIconButton(onClick = { state.navigateHistory(1); showCompletion = false }, enabled = state.canGoForward,
                    modifier = Modifier.weight(1f).testTag("next-command")) { Icon(ConsoleIcons.Forward, "Next") }
                Button(onClick = { showCompletion = false; runCommand() }, enabled = state.ready && state.command.isNotBlank(),
                    modifier = Modifier.weight(1f).testTag("run-command"), contentPadding = PaddingValues(0.dp)) { Icon(ConsoleIcons.Check, "Run") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("console-output"), state = historyScroll,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.entries.isEmpty()) item { Text("Command results will appear here.", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(state.entries.asReversed(), key = { it.id }) { entry ->
                Card(Modifier.fillMaxWidth().pointerInput(entry.id) {
                    var drag = 0f
                    detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                        onHorizontalDrag = { _, amount -> drag += amount },
                        onDragEnd = { if (abs(drag) > 120f) state.removeEntry(entry); drag = 0f })
                }, shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    SelectionContainer {
                        Text("? ${entry.command}\n${entry.result.trimEnd().ifEmpty { "(no output)" }}",
                            fontFamily = FontFamily.Monospace, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().clickable { state.reuse(entry); showCompletion = false }.padding(12.dp))
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
