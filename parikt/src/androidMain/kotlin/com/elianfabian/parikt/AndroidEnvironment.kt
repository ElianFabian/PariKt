package com.elianfabian.parikt

/** Startup records Android configuration without touching the JNI adapter or PARI. */
internal object AndroidEnvironment {
    @Volatile var temporaryDirectory: String? = null
}
