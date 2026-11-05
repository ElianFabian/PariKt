package com.elianfabian.parikt.console

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException

internal data class ConsoleEntry(val command: String, val result: String, val id: Long)

internal interface ConsoleEngine {
    suspend fun initialize(stackSize: Long)
    fun version(): String
    fun evaluate(command: String): String
    fun commands(): List<String>
    fun close()
}

internal object LiveConsoleEngine : ConsoleEngine {
    override suspend fun initialize(stackSize: Long) = ConsoleSession.initialize(stackSize)
    override fun version() = ConsoleSession.version()
    override fun evaluate(command: String) = ConsoleSession.evaluate(command)
    override fun commands() = ConsoleSession.commands()
    override fun close() = ConsoleSession.close()
}

internal class ConsoleState(
    private val engine: ConsoleEngine = LiveConsoleEngine,
    private val store: ConsoleStore = MemoryConsoleStore(),
) {
    var notice by mutableStateOf<String?>(null)
        private set
    var preferences by mutableStateOf(loadPreferences())
        private set
    val settings get() = preferences.settings
    val scripts get() = preferences.scripts
    var input by mutableStateOf(TextFieldValue())
        private set
    var command: String
        get() = input.text
        set(value) { updateInput(TextFieldValue(value, TextRange(value.length))) }
    var ready by mutableStateOf(false)
        private set
    var starting by mutableStateOf(false)
        private set
    var status by mutableStateOf("Loading PARI…")
        private set
    var version by mutableStateOf("")
        private set
    var startupError by mutableStateOf<String?>(null)
        private set
    private var availableCommands by mutableStateOf(emptyList<String>())
    val entries = mutableStateListOf<ConsoleEntry>()
    private val history = mutableStateListOf<String>()
    private var historyIndex by mutableStateOf<Int?>(null)
    private var draft = ""
    private var entryId = 0L
    val canGoBack get() = history.isNotEmpty() && (historyIndex == null || historyIndex!! > 0)
    val canGoForward get() = historyIndex != null

    val completions: List<String>
        get() {
            val range = tokenRange() ?: return emptyList()
            val prefix = input.text.substring(range.first, input.selection.end)
            if (prefix.isEmpty() || prefix.first().isDigit()) return emptyList()
            return availableCommands.asSequence()
                .filter { it.startsWith(prefix, ignoreCase = true) && it != prefix }.take(8).toList()
        }

    fun updateInput(value: TextFieldValue) {
        input = value
        historyIndex = null
    }

    fun complete(value: String) {
        if (value !in completions) return
        val range = tokenRange() ?: return
        val text = input.text.replaceRange(range.first, range.last + 1, value)
        updateInput(TextFieldValue(text, TextRange(range.first + value.length)))
    }

    private fun tokenRange(): IntRange? {
        if (!input.selection.collapsed || input.selection.end == 0) return null
        val cursor = input.selection.end.coerceIn(0, input.text.length)
        fun isToken(char: Char) = char.isLetterOrDigit() || char == '_'
        if (cursor == 0 || !isToken(input.text[cursor - 1])) return null
        var start = cursor
        var end = cursor
        while (start > 0 && isToken(input.text[start - 1])) start--
        while (end < input.text.length && isToken(input.text[end])) end++
        return start until end
    }

    fun navigateHistory(direction: Int) {
        if (history.isEmpty() || direction == 0) return
        val index = historyIndex
        if (index == null) {
            if (direction > 0) return
            draft = command
        }
        val next = ((index ?: history.size) + if (direction < 0) -1 else 1).coerceAtLeast(0)
        historyIndex = next.takeIf { it < history.size }
        val text = historyIndex?.let { history[it] } ?: draft
        input = TextFieldValue(text, TextRange(text.length))
    }

    suspend fun initialize() {
        if (ready || starting) return
        starting = true
        startupError = null
        status = "Loading PARI…"
        try {
            engine.initialize(settings.stackSize)
            if (settings.maximumStackSize != 0L) {
                engine.evaluate("default(parisizemax,${settings.maximumStackSize})")
                val actual = engine.evaluate("default(parisizemax)").substringAfterLast('=').trim().toLongOrNull()
                check(actual != null && actual >= settings.maximumStackSize) { "PARI did not accept parisizemax." }
            }
            val result = engine.evaluate("2+2")
            check(result.substringAfterLast('=').trim() == "4") { "Startup self-check failed: $result" }
            version = engine.version()
            availableCommands = engine.commands().distinct().sorted()
            status = "PARI $version · Ready · 2 + 2 = 4"
            addEntry("2+2", result)
            ready = true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            startupError = error.message ?: error.toString()
            status = "Unable to initialize PARI"
        } finally {
            starting = false
        }
    }

    suspend fun restart() {
        if (starting) return
        ready = false
        availableCommands = emptyList()
        try { engine.close() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            startupError = error.message ?: error.toString()
            status = "Unable to close PARI"
            return
        }
        initialize()
    }

    suspend fun applySettings(stack: String, maximum: String): Boolean {
        if (starting) return false
        val next = try { EngineSettings.parse(stack, maximum) }
        catch (error: IllegalArgumentException) { notice = error.message; return false }
        if (!savePreferences(preferences.copy(settings = next))) return false
        restart()
        return true
    }

    fun runCommand() {
        val text = command.trim()
        if (!ready || text.isEmpty()) return
        command = ""
        history.add(text)
        val output = try { engine.evaluate(text) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { "Error: ${error.message ?: error}" }
        addEntry(text, output)
    }

    fun saveScript(originalName: String?, name: String, code: String): Boolean {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty() || code.isBlank()) { notice = "Enter a script name and GP code."; return false }
        if (scripts.any { it.name == trimmedName && it.name != originalName }) {
            notice = "A script with that name already exists."; return false
        }
        val next = scripts.toMutableList()
        val index = next.indexOfFirst { it.name == originalName }
        val script = SavedScript(trimmedName, code)
        if (index >= 0) next[index] = script else next.add(script)
        return savePreferences(preferences.copy(scripts = next))
    }

    fun deleteScript(script: SavedScript) = savePreferences(preferences.copy(scripts = scripts.filterNot { it.name == script.name }))
    fun clearOutput() { entries.clear() }
    fun removeEntry(entry: ConsoleEntry) { entries.remove(entry) }
    fun reuse(entry: ConsoleEntry) { command = entry.command }
    fun dismissNotice() { notice = null }
    fun outputText() = entries.asReversed().joinToString("\n\n") { "? ${it.command}\n${it.result.trimEnd()}" }
    private fun addEntry(command: String, result: String) { entries.add(ConsoleEntry(command, result, ++entryId)) }

    private fun loadPreferences(): ConsolePreferences = try {
        store.read()?.let(ConsolePreferencesCodec::decode) ?: ConsolePreferences()
    } catch (error: Exception) {
        notice = "Could not load saved settings/scripts: ${error.message}. Using defaults."
        ConsolePreferences()
    }

    private fun savePreferences(next: ConsolePreferences): Boolean = try {
        store.write(ConsolePreferencesCodec.encode(next))
        preferences = next
        notice = null
        true
    } catch (error: Exception) {
        notice = "Could not save settings/scripts: ${error.message}"
        false
    }
}
