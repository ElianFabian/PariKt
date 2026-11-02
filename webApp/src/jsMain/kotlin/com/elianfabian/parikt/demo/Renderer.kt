@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.elianfabian.parikt.demo

import androidx.compose.ui.window.ComposeViewport
import com.elianfabian.parikt.console.ConsoleApp
import org.jetbrains.skiko.wasm.onWasmReady
import kotlinx.browser.document
import org.w3c.dom.HTMLScriptElement

internal actual fun launchConsoleUi() {
	// The JS renderer needs Skiko's global loader; Kotlin/Wasm imports its renderer itself.
	val script = document.createElement("script") as HTMLScriptElement
	script.src = "skiko.js"
	script.onload = { onWasmReady { ComposeViewport("app") { ConsoleApp() } } }
	script.addEventListener("error", {
		document.getElementById("app")?.textContent = "Unable to load the graphics engine. Reload to retry."
	})
	document.head!!.appendChild(script)
}
