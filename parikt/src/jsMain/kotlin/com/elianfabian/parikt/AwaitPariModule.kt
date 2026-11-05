package com.elianfabian.parikt

import kotlinx.coroutines.await
import kotlin.js.Promise
import kotlin.js.unsafeCast

internal actual external interface WebEngineLoading

internal actual external interface WebEngine {
    actual fun init(stackBytes: Int)
    actual fun eval(command: String): String
    actual fun getVersionCode(): Int
    actual fun close()
    fun getSupportedCommands(): Array<String>
}

internal actual fun engineCommands(engine: WebEngine): Array<String> = engine.getSupportedCommands()

// The Emscripten factory always returns a Promise; only this target knows its generic type.
internal actual suspend fun awaitPariModule(loading: WebEngineLoading): WebEngine =
    loading.unsafeCast<Promise<WebEngine>>().await()
