package com.elianfabian.parikt.console

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

/** Engine access shared by the graphical and terminal consoles. Keep calls on the initialization thread. */
object ConsoleSession {
    suspend fun initialize(stackSize: Long = 4_000_000) = PariKt.init(stackSize)
    fun version(): String = PariKt.getVersionName()
    fun evaluate(command: String): String = PariKt.eval(command)
    fun commands(): List<String> = PariKt.getSupportedCommands()
    fun close() = PariKt.close()
}
