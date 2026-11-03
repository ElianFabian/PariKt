package proof

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

suspend fun verifySharedNativeEngine(expected: String) {
    PariKt.init()
    try {
        val actual = PariKt.getVersionName()
        check(actual == expected) { "Expected PARI $expected, loaded $actual" }
        fun result(command: String) = PariKt.eval(command).substringAfterLast('=').trim()
        check(result("2+2") == "4")
        check(result("factor(123456)==[2,6;3,1;643,1]") == "1")
        check(PariKt.eval("1/0").contains(Regex("division|inverse|zero", RegexOption.IGNORE_CASE)))
        check(result("6*7") == "42") { "Engine did not recover after division by zero" }
        println("PASS native sharedLogic PARI $actual arithmetic/factorization/error recovery")
    } finally { PariKt.close() }
}
