package com.elianfabian.parikt

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.JsString
import kotlin.js.unsafeCast

internal actual external interface WebEngineLoading : JsAny

internal actual external interface WebEngine : JsAny {
    actual fun init(stackBytes: Int)
    actual fun eval(command: String): String
    actual fun getVersionCode(): Int
    actual fun close()
    fun getSupportedCommands(): JsArray<JsString>
}

internal actual fun engineCommands(engine: WebEngine): Array<String> {
    val commands = engine.getSupportedCommands()
    return Array(commands.length) { commands[it].toString() }
}

// The Emscripten factory always returns a Promise; only this target knows its generic type.
internal actual suspend fun awaitPariModule(loading: WebEngineLoading): WebEngine =
    loading.unsafeCast<Promise<WebEngine>>().await()
