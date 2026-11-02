package com.elianfabian.parikt

/**
 * Platform-specific native bindings for PariKt.
 * Targets provide JNI, direct C interop, or a JavaScript adapter to the Emscripten engine.
 */
internal expect object PariNative {

	suspend fun prepareLibraries()

	fun loadLibraries()

	fun setTemporaryDirectory(path: String): Boolean

	fun init(pariSize: Long)

	fun eval(cmd: String): String?

	fun getCommands(): Array<String>?

	fun paridroidClose()

	fun pariVersionCode(): Int
}
