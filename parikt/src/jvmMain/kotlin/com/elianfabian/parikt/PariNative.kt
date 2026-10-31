package com.elianfabian.parikt

// JVM-specific implementation of PariNative using JNI
internal actual object PariNative {

    const val MSG_TAG: String = "PariKt::PariNative"

    actual fun loadLibraries() {
        try {
            System.loadLibrary("gmp")
            println("[$MSG_TAG] Loaded libgmp.so")
        } catch (ex: Exception) {
            System.err.println("[$MSG_TAG] Error loading libgmp: ${ex.message}")
        }

        try {
            System.loadLibrary("pari")
            println("[$MSG_TAG] Loaded libpari.so")
        } catch (ex: Exception) {
            System.err.println("[$MSG_TAG] Error loading libpari: ${ex.message}")
        }

        try {
            System.loadLibrary("paridroid")
            println("[$MSG_TAG] Loaded libparidroid.so")
        } catch (ex: Exception) {
            System.err.println("[$MSG_TAG] Error loading libparidroid: ${ex.message}")
        }
    }

    actual fun setTemporaryDirectory(path: String): Boolean = true

    actual external fun init(pariSize: Long)

    actual external fun eval(cmd: String): String?

    actual external fun getCommands(): Array<String>?

    actual external fun paridroidClose()

    actual external fun pariVersionCode(): Int
}
