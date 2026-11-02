package com.elianfabian.parikt.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val Background = Color(0xFF111827)
private val Panel = Color(0xFF0B1220)
private val Foreground = Color(0xFFE5E7EB)
private val Muted = Color(0xFF94A3B8)
private val Border = Color(0xFF475569)
private val Accent = Color(0xFF38BDF8)
private val Ready = Color(0xFFA5D6B5)
private val Error = Color(0xFFFCA5A5)
private val Shape = RoundedCornerShape(8.dp)

@Composable
fun ConsoleApp() {
	MaterialTheme(colorScheme = darkColorScheme(
		background = Background, surface = Background, onBackground = Foreground,
		onSurface = Foreground, onSurfaceVariant = Muted, primary = Accent,
	)) {
		val state = remember { ConsoleState() }
		LaunchedEffect(state) { state.initialize() }
		ConsoleScreen(state)
	}
}

@Composable
internal fun ConsoleScreen(state: ConsoleState) {
	val scope = rememberCoroutineScope()
	// Use the composition's dispatcher for native calls, including programmatic input.
	// PARI initialization and evaluation must stay on the same UI thread.
	val runCommand: () -> Unit = { scope.launch { state.runCommand() } }
	val historyScroll = rememberLazyListState()
	LaunchedEffect(state.entries.size) {
		if (state.entries.isNotEmpty()) historyScroll.animateScrollToItem(state.entries.lastIndex)
	}
	Surface(modifier = Modifier.fillMaxSize(), color = Background) {
		BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
			val shortWindow = maxHeight < 640.dp
			val sidePadding = if (maxWidth < 600.dp) 16.dp else 24.dp
			Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
				Column(
					Modifier.widthIn(max = 900.dp).fillMaxSize()
						.padding(horizontal = sidePadding, vertical = 24.dp)
						.then(if (shortWindow) Modifier.verticalScroll(rememberScrollState()) else Modifier),
					verticalArrangement = Arrangement.spacedBy(14.dp),
				) {
					Text("PariKt GP console", fontSize = 30.sp, fontWeight = FontWeight.Bold)
					Text(
						state.status,
						color = when { state.ready -> Ready; state.startupError != null -> Error; else -> Muted },
						modifier = Modifier.testTag("engine-status"),
					)
					state.startupError?.let { message ->
						SelectionContainer { Text(message, color = Error) }
						OutlinedButton(onClick = { scope.launch { state.initialize() } }, enabled = !state.starting) { Text("Retry initialization") }
					}
					Text("Evaluate PARI/GP expressions.")
					Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						listOf(
							"Factor a number" to "factor(123456)",
							"Check a prime" to "isprime(2^127-1)",
							"Pi" to "Pi",
						).forEach { (label, command) ->
							OutlinedButton(
								onClick = { state.command = command },
								shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, Border),
								colors = ButtonDefaults.outlinedButtonColors(contentColor = Foreground, containerColor = Color(0xFF1E293B)),
							) { Text(label) }
						}
					}
					Text("GP command", fontWeight = FontWeight.SemiBold)
					OutlinedTextField(
						value = state.command, onValueChange = { state.command = it },
						modifier = Modifier.fillMaxWidth().testTag("command-input").onPreviewKeyEvent { event ->
							if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed) {
								runCommand(); true
							} else false
						},
						minLines = 3, maxLines = 4, shape = Shape,
						placeholder = { Text("factor(123456)", fontFamily = FontFamily.Monospace) },
						textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
						keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
						keyboardActions = KeyboardActions(onDone = { runCommand() }),
						colors = OutlinedTextFieldDefaults.colors(
							focusedContainerColor = Panel, unfocusedContainerColor = Panel,
							focusedBorderColor = Accent, unfocusedBorderColor = Border,
						),
					)
					Text("Enter to run · Shift+Enter for a new line. Long calculations can pause the interface.", color = Muted, fontSize = 12.sp)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						Button(
							onClick = runCommand,
							enabled = state.ready && state.command.isNotBlank(),
							modifier = Modifier.testTag("run-command"), shape = RoundedCornerShape(6.dp),
							colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1), contentColor = Foreground),
						) { Text("Run command") }
						OutlinedButton(
							onClick = state::clearOutput, enabled = state.entries.isNotEmpty(),
							modifier = Modifier.testTag("clear-output"), shape = RoundedCornerShape(6.dp),
							border = BorderStroke(1.dp, Border),
							colors = ButtonDefaults.outlinedButtonColors(contentColor = Foreground),
						) { Text("Clear output") }
					}
					val historyModifier = if (shortWindow) Modifier.height(240.dp) else Modifier.weight(1f)
					LazyColumn(
						modifier = historyModifier.fillMaxWidth().clip(Shape).background(Panel)
							.border(1.dp, Border, Shape).padding(16.dp).testTag("console-output"),
						state = historyScroll, verticalArrangement = Arrangement.spacedBy(16.dp),
					) {
						if (state.entries.isEmpty()) item { Text("Command results will appear here.", color = Muted) }
						items(state.entries) { entry ->
							Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
								Text(
									"> ${entry.command}", color = Accent, fontFamily = FontFamily.Monospace,
									modifier = Modifier.clickable { state.reuse(entry) },
								)
								SelectionContainer {
									Text(entry.result.trimEnd().ifEmpty { "(no output)" }, fontFamily = FontFamily.Monospace)
								}
							}
						}
					}
				}
			}
		}
	}
}
