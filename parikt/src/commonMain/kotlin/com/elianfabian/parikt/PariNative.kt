package com.elianfabian.parikt

/**
 * Platform-specific native bindings for PariKt.
 * Each target provides an actual implementation with JNI or cinterop bindings.
 */
internal expect object PariNative {

    fun loadLibraries()

    fun setTemporaryDirectory(path: String): Boolean

    fun init(pariSize: Long)

    fun eval(cmd: String): String?

    fun getCommands(): Array<String>?

    fun paridroidClose()

    fun pariVersionCode(): Int
}
