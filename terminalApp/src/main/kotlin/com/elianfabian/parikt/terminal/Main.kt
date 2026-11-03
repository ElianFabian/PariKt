package com.elianfabian.parikt.terminal

import com.elianfabian.parikt.console.ConsoleSession
import com.elianfabian.parikt.console.checkConsoleEngine

suspend fun main(args: Array<String>) {
	if ("--check-engine" in args) {
		checkConsoleEngine(requireNotNull(System.getProperty("parikt.expected.version")) { "Run verifyPariEngine through Gradle to select the expected version." })
		return
	}
	ConsoleSession.initialize()
	try {
		println("PariKt terminal console")
		println("PARI version: ${ConsoleSession.version()}")
		println("Startup check: 2 + 2 = ${ConsoleSession.evaluate("2 + 2")}")
		println("Enter GP commands, or type quit / exit (Ctrl+D/Ctrl+Z to end input).")

		while (true) {
			print("gp> ")
			System.out.flush()
			val command = readlnOrNull() ?: break
			if (command.trim().equals("quit", ignoreCase = true) ||
				command.trim().equals("exit", ignoreCase = true)
			) {
				break
			}
			if (command.isBlank()) continue

			try {
				println(ConsoleSession.evaluate(command))
			}
			catch (error: Exception) {
				System.err.println("Command failed: ${error.message}")
			}
		}
	}
	finally {
		ConsoleSession.close()
		println("PARI/GP closed.")
	}
}
