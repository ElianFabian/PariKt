package com.elianfabian.parikt.console

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleStateTest {
	private class Engine : ConsoleEngine {
		var initializations = 0
		var startupResult = "%1 = 4\n"
		val commands = mutableListOf<String>()
		override suspend fun initialize() { initializations++ }
		override fun version() = "2.15.0"
		override fun evaluate(command: String): String {
			commands.add(command)
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
}
