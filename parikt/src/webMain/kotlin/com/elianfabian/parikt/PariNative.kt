@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.elianfabian.parikt

import kotlinx.coroutines.CancellationException
import kotlin.js.Promise

internal expect suspend fun awaitPariModule(loading: Promise<WebEngine>): WebEngine

internal actual object PariNative {
	private var module: WebEngine? = null
	private var pending: Promise<WebEngine>? = null

	actual suspend fun prepareLibraries() {
		if (module != null) return
		val loading = pending ?: loadPariModule().also { pending = it }
		try {
			module = awaitPariModule(loading)
		} catch (error: Throwable) {
			// Cancelling one caller must not start a second engine while the shared load continues.
			if (error !is CancellationException && pending === loading) pending = null
			throw error
		}
	}

	private fun engine(): WebEngine = checkNotNull(module) {
		"Web engine not loaded. Call PariKt.init() first."
	}

	actual fun loadLibraries() { engine() }

	// The Emscripten bridge configures its own in-memory /tmp before PARI initialization.
	actual fun setTemporaryDirectory(path: String): Boolean = path == "/tmp"

	actual fun init(pariSize: Long) {
		// The web ABI takes a signed wasm32 int; preserve the Long value without overflow.
		// This is a representation check. PARI still needs enough stack to initialize and evaluate.
		require(pariSize in 1L..Int.MAX_VALUE.toLong()) {
			"Web PARI stack size must be between 1 and 2147483647 bytes."
		}
		engine().init(pariSize.toInt())
	}

	actual fun eval(cmd: String): String? = engine().eval(cmd)

	@Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD") // JsString is already String on JS, but not Wasm.
	actual fun getSupportedCommands(): Array<String>? {
		val commands = engine().getSupportedCommands()
		return Array(commands.length) { commands[it].toString() }
	}

	actual fun close() { module?.close() }

	actual fun getVersionCode(): Int = engine().getVersionCode()
}
