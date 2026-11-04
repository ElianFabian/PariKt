@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.elianfabian.parikt

import com.elianfabian.parikt.cinterop.*
import kotlinx.cinterop.toKString

internal actual object PariNative {
    actual suspend fun prepareLibraries() {}
    actual fun loadLibraries() {} // Static libraries are linked into the executable.

    private fun checkThread() {
        check(parikt_native_is_owner_thread() != 0) {
            "PARI must be used on the same OS thread that initialized it."
        }
    }

    actual fun setTemporaryDirectory(path: String): Boolean {
        checkThread()
        require('\u0000' !in path) { "Temporary directory cannot contain a NUL character." }
        return parikt_native_set_tmpdir(path) != 0
    }

    actual fun init(pariSize: Long) {
        checkThread()
        require(pariSize > 0) { "PARI stack size must be positive." }
        val directory = checkNotNull(parikt_native_default_tmpdir()).toKString()
        check(setTemporaryDirectory(directory)) { "Unable to configure PARI's temporary directory." }
        check(parikt_native_init(pariSize) != 0) { "Unable to initialize PARI." }
    }

    actual fun eval(cmd: String): String? {
        checkThread()
        require('\u0000' !in cmd) { "GP commands cannot contain a NUL character." }
        return parikt_native_eval(cmd)?.toKString()
    }

    actual fun getSupportedCommands(): Array<String>? {
        checkThread()
        val count = parikt_native_command_count()
        check(count >= 0) { "Unable to read PARI's command list (uninitialized engine or capacity exceeded)." }
        return Array(count) { index -> checkNotNull(parikt_native_command(index)).toKString() }
    }

    actual fun close() {
        checkThread()
        parikt_native_close()
    }

    actual fun getVersionCode(): Int = parikt_native_version()
}
