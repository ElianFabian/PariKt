package com.elianfabian.parikt.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.console.ConsoleApp

fun main() = application {
	Window(
		title = "PariKt GP Console",
		state = rememberWindowState(width = 960.dp, height = 800.dp),
		onCloseRequest = {
			PariKt.close()
			exitApplication()
		},
	) { ConsoleApp() }
}
