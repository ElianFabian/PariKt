package com.elianfabian.parikt.nativecli

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName
import kotlinx.coroutines.runBlocking

fun main(): Unit = runBlocking {
	PariKt.init()
	try {
		println("PariKt native console (no JVM)")
		println("PARI version: ${PariKt.getVersionName()}")
		println("Startup check: 2 + 2 = ${PariKt.eval("2+2")}")
		println("Enter GP commands; quit / exit or EOF closes the console.")
		while (true) {
			print("gp> ")
			val command = readlnOrNull() ?: break
			if (command.trim().lowercase() in setOf("quit", "exit")) break
			if (command.isBlank()) continue
			try { println(PariKt.eval(command)) }
			catch (error: Exception) { println("Command failed: ${error.message}") }
		}
	} finally {
		PariKt.close()
		println("PARI/GP closed.")
	}
}
