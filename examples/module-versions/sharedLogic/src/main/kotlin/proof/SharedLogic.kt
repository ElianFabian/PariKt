package proof

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

suspend fun verifySharedEngine(expected: String) {
    PariKt.init()
    try {
        val actual = PariKt.getVersionName()
        if (expected !in setOf("master", "latest")) {
            check(actual == expected) { "Expected $expected, got $actual" }
        }
        check(PariKt.eval("2 + 2").substringAfter("= ").trim() == "4")
        check(PariKt.eval("factor(123456)==[2,6;3,1;643,1]").substringAfter("= ").trim() == "1")
        val resources = PariKt::class.java.classLoader.getResources("com/elianfabian/parikt/PariKt.class").toList()
        check(resources.size == 1) { "Duplicate PariKt classes: $resources" }
        println("PASS sharedLogic PARI $actual arithmetic/factorization; one PariKt class resource")
    } finally {
        PariKt.close()
    }
}
