package com.elianfabian.parikt.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elianfabian.parikt.console.ConsoleApp
import com.elianfabian.parikt.console.ConsoleSession
import com.elianfabian.parikt.console.checkConsoleEngine
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
	if ("--check-engine" in args) {
		runBlocking { checkConsoleEngine(requireNotNull(System.getProperty("parikt.expected.version")) { "Run verifyPariEngine through Gradle to select the expected version." }) }
		return
	}
	showConsole()
}

private fun showConsole() = application {
	Window(
		title = "PariKt GP Console",
		state = rememberWindowState(width = 960.dp, height = 800.dp),
		onCloseRequest = {
			ConsoleSession.close()
			exitApplication()
		},
	) { ConsoleApp() }
}
