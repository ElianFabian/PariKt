package com.elianfabian.parikt.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.elianfabian.parikt.PariKt

private data class ConsoleEntry(
    val command: String,
    val result: String,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PariKtConsoleScreen()
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PariKtConsoleScreen() {
    var command by remember { mutableStateOf("") }
    var isReady by remember { mutableStateOf(false) }
    var startupMessage by remember { mutableStateOf("Starting PARI/GP…") }
    var pariVersion by remember { mutableStateOf<String?>(null) }
    val history = remember { mutableStateListOf<ConsoleEntry>() }
    val historyScrollState = rememberLazyListState()

    fun runCommand(source: String = command) {
        val gpCommand = source.trim()
        if (!isReady || gpCommand.isEmpty()) return

        command = ""
        val result = runCatching { PariKt.eval(gpCommand) }
            .getOrElse { error -> "GP call failed: ${error.message ?: error}" }
        history.add(ConsoleEntry(gpCommand, result))
    }

    LaunchedEffect(Unit) {
        runCatching {
            val versionCode = PariKt.getVersionCode()
            val checkResult = PariKt.eval("2 + 2")
            pariVersion = formatPariVersion(versionCode)
            history.add(ConsoleEntry("2 + 2", checkResult))
            isReady = true
            startupMessage = "Engine ready. Startup check: 2 + 2 returned 4."
        }.onFailure { error ->
            isReady = false
            startupMessage = "Engine did not start: ${error.message ?: error}"
        }
    }

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) historyScrollState.animateScrollToItem(history.lastIndex)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PariKt GP Console") }) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isReady) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (isReady) "PARI/GP is ready" else "PARI/GP status",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(startupMessage, style = MaterialTheme.typography.bodyMedium)
                    pariVersion?.let { version ->
                        Text("PARI version $version", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Text("Try an example", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("factor(123456)", "isprime(101)", "nextprime(100)").forEach { example ->
                    AssistChip(
                        onClick = { command = example },
                        label = { Text(example, fontFamily = FontFamily.Monospace) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Results", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { history.clear() },
                    enabled = history.isNotEmpty(),
                ) {
                    Text("Clear")
                }
            }

            if (history.isEmpty()) {
                Text(
                    "Your command results will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = historyScrollState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(history) { _, entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { command = entry.command },
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "? ${entry.command}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = entry.result,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    "Tap result to edit and run again",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 140.dp),
                label = { Text("GP command") },
                placeholder = { Text("For example: 2 + 2") },
                supportingText = { Text("Type a PARI/GP expression, then press Run") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { runCommand() }),
                enabled = isReady,
            )
            Button(
                onClick = { runCommand() },
                modifier = Modifier.fillMaxWidth(),
                enabled = isReady && command.isNotBlank(),
            ) {
                Text("Run command")
            }
        }
    }
}

private fun formatPariVersion(versionCode: Int): String =
    "${versionCode ushr 16}.${(versionCode ushr 8) and 0xff}.${versionCode and 0xff}"
