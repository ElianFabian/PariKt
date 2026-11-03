package com.elianfabian.parikt.console

import com.elianfabian.parikt.PariKt

/** Headless verification used by both console launchers and CI. */
suspend fun checkConsoleEngine(expectedVersion: String) {
    ConsoleSession.initialize()
    try {
        val actual = ConsoleSession.version()
        if (expectedVersion !in setOf("master", "latest")) check(actual == expectedVersion) {
            "Expected PARI $expectedVersion, loaded $actual"
        }
        check(ConsoleSession.evaluate("2+2").substringAfterLast('=').trim() == "4")
        check(ConsoleSession.evaluate("factor(123456)==[2,6;3,1;643,1]").substringAfterLast('=').trim() == "1")
        val resources = PariKt::class.java.classLoader.getResources("com/elianfabian/parikt/PariKt.class").toList()
        check(resources.size == 1) { "Duplicate PariKt classes: $resources" }
        println("PASS shared console engine PARI $actual: arithmetic, factorization, one implementation")
    } finally { ConsoleSession.close() }
}
