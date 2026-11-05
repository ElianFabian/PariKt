package com.elianfabian.parikt.console

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.assertFailsWith

class ConsoleStateTest {
	private class Engine : ConsoleEngine {
		var initializations = 0
		var startupResult = "%1 = 4\n"
		val commands = mutableListOf<String>()
		var stackSize = 0L
		var maximum = 0L
		var closes = 0
		override suspend fun initialize(stackSize: Long) { initializations++; this.stackSize = stackSize }
		override fun close() { closes++ }
		override fun commands() = listOf("factor", "factorback", "isprime", "Pi")
		override fun version() = "2.15.0"
		override fun evaluate(command: String): String {
			commands.add(command)
			if (command.startsWith("default(parisizemax,")) maximum = command.substringAfter(',').substringBefore(')').toLong()
			if (command == "default(parisizemax)") return maximum.toString()
			return if (command == "2+2") startupResult else "result: $command"
		}
	}

	@Test
	fun startupCommandsReuseAndClearShareOneEngine() = runTest {
		val engine = Engine()
		val state = ConsoleState(engine)
		state.command = "Pi"
		state.runCommand()
		assertTrue(engine.commands.isEmpty())
		state.initialize()
		state.initialize()
		assertTrue(state.ready)
		assertEquals(1, engine.initializations)
		assertTrue(state.status.contains("PARI 2.15.0"))
		state.command = "  factor(123456)  "
		state.runCommand()
		assertEquals("", state.command)
		assertEquals("factor(123456)", state.entries.last().command)
		state.reuse(state.entries.last())
		assertEquals("factor(123456)", state.command)
		state.clearOutput()
		assertTrue(state.entries.isEmpty())
		assertTrue(state.ready)
		assertEquals(listOf("2+2", "factor(123456)"), engine.commands)
	}

	@Test
	fun failedSelfCheckIsVisibleAndCanBeRetried() = runTest {
		val engine = Engine().also { it.startupResult = "5" }
		val state = ConsoleState(engine)
		state.initialize()
		assertFalse(state.ready)
		assertTrue(state.startupError.orEmpty().contains("self-check"))
		engine.startupResult = "4"
		state.initialize()
		assertTrue(state.ready)
		assertEquals(null, state.startupError)
	}

	@Test fun historyRestoresDraftAndSurvivesOutputClearing() = runTest {
		val state = ConsoleState(Engine())
		state.initialize()
		for (command in listOf("Pi", "factor(12)")) { state.command = command; state.runCommand() }
		state.command = "unfinished + "
		state.clearOutput()
		state.navigateHistory(-1)
		assertEquals("factor(12)", state.command)
		state.navigateHistory(-1)
		state.navigateHistory(-1)
		assertEquals("Pi", state.command)
		assertFalse(state.canGoBack)
		state.navigateHistory(1)
		state.navigateHistory(1)
		assertEquals("unfinished + ", state.command)
		assertFalse(state.canGoForward)
		state.navigateHistory(-1)
		state.command = "new draft"
		state.navigateHistory(1)
		assertEquals("new draft", state.command)
	}

	@Test fun completionReplacesOnlyTheTokenAtTheCursor() = runTest {
		val state = ConsoleState(Engine())
		state.initialize()
		state.updateInput(TextFieldValue("1 + fac_old(12)", TextRange(7)))
		assertEquals(listOf("factor", "factorback"), state.completions)
		state.complete("factor")
		assertEquals("1 + factor(12)", state.command)
		assertEquals(TextRange(10), state.input.selection)
		state.command = "factor("
		assertTrue(state.completions.isEmpty())
		state.updateInput(TextFieldValue("factor", TextRange(0, 6)))
		assertTrue(state.completions.isEmpty())
	}

	@Test fun scriptsPersistMultilineUnicodeAndRenamesRejectDuplicates() {
		val store = MemoryConsoleStore()
		val state = ConsoleState(Engine(), store)
		val code = "f(x) = {\n  x^2; \\\\ π:🙂\n};\nf(12)"
		assertTrue(state.saveScript(null, "Squares: π🙂", code))
		assertFalse(state.saveScript(null, "Squares: π🙂", "Pi"))
		val restored = ConsoleState(Engine(), store)
		assertEquals(listOf(SavedScript("Squares: π🙂", code)), restored.scripts)
		assertTrue(restored.saveScript("Squares: π🙂", "Renamed", "f(3)"))
		assertEquals("Renamed", ConsoleState(Engine(), store).scripts.single().name)
		restored.deleteScript(restored.scripts.single())
		assertTrue(ConsoleState(Engine(), store).scripts.isEmpty())
	}

	@Test fun settingsValidateBeforeSavingAndRestartWithRequestedSizes() = runTest {
		val store = MemoryConsoleStore()
		val engine = Engine()
		val state = ConsoleState(engine, store)
		state.initialize()
		for ((stack, maximum) in listOf("0" to "0", "abc" to "0", "8000000" to "4000000", "2147483648" to "0")) {
			assertFalse(state.applySettings(stack, maximum))
		}
		assertEquals(0, engine.closes)
		assertEquals(null, store.read())
		assertTrue(state.applySettings("8000000", "16000000"))
		assertEquals(1, engine.closes)
		assertEquals(8_000_000L, engine.stackSize)
		assertEquals(16_000_000L, engine.maximum)
		assertTrue(state.ready)
		assertEquals(EngineSettings(8_000_000, 16_000_000), ConsoleState(Engine(), store).settings)
	}

	@Test fun storageFailuresAreVisibleWithoutChangingInMemoryPreferences() = runTest {
		val store = object : ConsoleStore {
			override fun read() = "broken"
			override fun write(value: String) { error("Storage full") }
		}
		val engine = Engine()
		val state = ConsoleState(engine, store)
		assertTrue(state.notice.orEmpty().contains("Could not load"))
		state.initialize()
		assertTrue(state.ready)
		assertFalse(state.saveScript(null, "Test", "Pi"))
		assertTrue(state.scripts.isEmpty())
		assertTrue(state.notice.orEmpty().contains("Storage full"))
		assertFalse(state.applySettings("8000000", "0"))
		assertEquals(EngineSettings(), state.settings)
		assertEquals(0, engine.closes)
	}

	@Test fun truncatedPreferenceRecordsAreRejected() {
		val encoded = ConsolePreferencesCodec.encode(ConsolePreferences(scripts = listOf(SavedScript("Test", "factor(12)"))))
		assertFailsWith<IllegalArgumentException> { ConsolePreferencesCodec.decode(encoded.dropLast(2)) }
	}
}
