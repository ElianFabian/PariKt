package com.elianfabian.parikt.console

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException

internal data class ConsoleEntry(val command: String, val result: String)

internal interface ConsoleEngine {
	suspend fun initialize()
	fun version(): String
	fun evaluate(command: String): String
}

internal object LiveConsoleEngine : ConsoleEngine {
	override suspend fun initialize() = ConsoleSession.initialize()
	override fun version(): String = ConsoleSession.version()
	override fun evaluate(command: String): String = ConsoleSession.evaluate(command)
}

internal class ConsoleState(private val engine: ConsoleEngine = LiveConsoleEngine) {
	var command by mutableStateOf("")
	var ready by mutableStateOf(false)
		private set
	var starting by mutableStateOf(false)
		private set
	var status by mutableStateOf("Loading PARI…")
		private set
	var startupError by mutableStateOf<String?>(null)
		private set
	val entries = mutableStateListOf<ConsoleEntry>()

	suspend fun initialize() {
		if (ready || starting) return
		starting = true
		startupError = null
		status = "Loading PARI…"
		try {
			engine.initialize()
			val result = engine.evaluate("2+2")
			check(result.substringAfterLast('=').trim() == "4") { "Startup self-check failed: $result" }
			status = "PARI ${engine.version()} · Ready · 2 + 2 = 4"
			entries.add(ConsoleEntry("2+2", result))
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

	fun runCommand() {
		val input = command.trim()
		if (!ready || input.isEmpty()) return
		command = ""
		val output = try { engine.evaluate(input) }
		catch (error: Throwable) { "Error: ${error.message ?: error}" }
		entries.add(ConsoleEntry(input, output))
	}

	fun clearOutput() { entries.clear() }
	fun reuse(entry: ConsoleEntry) { command = entry.command }
}
