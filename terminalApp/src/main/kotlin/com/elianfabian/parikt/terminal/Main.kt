package com.elianfabian.parikt.terminal

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

fun main() {
	PariKt.init()
	try {
		println("PariKt terminal console")
		println("PARI version: ${PariKt.getVersionName()}")
		println("Startup check: 2 + 2 = ${PariKt.eval("2 + 2")}")
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
				println(PariKt.eval(command))
			}
			catch (error: Exception) {
				System.err.println("Command failed: ${error.message}")
			}
		}
	}
	finally {
		PariKt.close()
		println("PARI/GP closed.")
	}
}
