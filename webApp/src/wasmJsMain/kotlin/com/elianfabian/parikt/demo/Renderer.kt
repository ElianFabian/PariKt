@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.elianfabian.parikt.demo

import androidx.compose.ui.window.ComposeViewport
import com.elianfabian.parikt.console.ConsoleApp

// Kotlin/Wasm loads Skiko's Wasm imports before invoking main().
internal actual fun launchConsoleUi() {
	ComposeViewport("app") { ConsoleApp() }
}
