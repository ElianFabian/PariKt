@file:OptIn(ExperimentalWasmJsInterop::class)

package com.elianfabian.parikt

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.Promise

@JsModule("@parikt/engine")
private external fun createPari(): Promise<WebEngine>

internal fun loadPariModule(): Promise<WebEngine> = createPari()

internal external interface WebEngine : JsAny {
	fun init(stackBytes: Int)
	fun eval(command: String): String
	fun getSupportedCommands(): JsArray<JsString>
	fun getVersionCode(): Int
	fun close()
}
