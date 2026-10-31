package com.elianfabian.parikt

/**
 * Extension functions for PariKt.
 * These are defined in commonMain and available on all platforms.
 */

public fun PariKt.getVersionName(): String {
    val code = getVersionCode()

    val major = (code ushr 16) and 0xFF
    val minor = (code ushr 8) and 0xFF
    val patch = code and 0xFF

    return "${major}.${minor}.${patch}"
}
