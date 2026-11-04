package proof

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

fun verifySharedAndroidEngine(expected: String): String {
    // Deliberately do not call init: AndroidX Startup must have initialized PARI already.
    fun result(command: String) = PariKt.eval(command).substringAfterLast('=').trim()
    check(result("2+2") == "4") { "Android Startup did not prepare the engine" }
    val actual = PariKt.getVersionName()
    check(actual == expected) { "Expected PARI $expected, loaded $actual" }
    check(result("factor(123456)==[2,6;3,1;643,1]") == "1")
    check("factor" in PariKt.getSupportedCommands())
    check(PariKt.eval("").isEmpty())
    check(PariKt.eval("if(1,quit())").contains("PariKt.close"))
    PariKt.eval("for(i=1,1100,eval(Str(\"parikt_android_boundary_\",i,\"(x)=x+1\")));")
    val commands = PariKt.getSupportedCommands().toSet()
    for (i in 1..1100) check("parikt_android_boundary_$i" in commands)
    check(PariKt.eval("1/0").contains(Regex("division|inverse|zero", RegexOption.IGNORE_CASE)))
    check(result("6*7") == "42")
    return "PASS Android PARI $actual Startup/arithmetic/factorization/error recovery/JNI boundaries"
}
