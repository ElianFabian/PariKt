package com.elianfabian.parikt

import kotlinx.coroutines.*

/**
 * WasmJS implementation of PariNative using Emscripten-compiled PARI/GP.
 * Uses Kotlin/JS interop to call the WebAssembly module.
 */
internal actual object PariNative {

    private var _module: PariModule? = null

    actual fun loadLibraries() {
        _module = PariModule.create()
    }

    actual fun setTemporaryDirectory(path: String): Boolean = true

    actual fun init(pariSize: Long) {
        _module = PariModule.create()
        runBlocking {
            await(_module!!.ready)
        }
        _module!!.paridroidInit(pariSize)
    }

    actual fun eval(cmd: String): String? {
        return _module?.paridroidEval(cmd)
    }

    actual fun getCommands(): Array<String>? {
        return _module?.getCommands()
    }

    actual fun paridroidClose() {
        _module?.paridroidClose()
    }

    actual fun pariVersionCode(): Int {
        return _module?.pariVersionCode() ?: error("Engine not initialized")
    }
}

// External interface for the Emscripten module
@JsExternal
external interface PariModule {
    val ready: Promise<Unit>

    fun paridroidInit(parisize: Long): Unit
    fun paridroidEval(cmd: String): String?
    fun getCommands(): Array<String>?
    fun paridroidClose(): Unit
    fun pariVersionCode(): Int

    companion object {
        fun create(): PariModule
    }
}
