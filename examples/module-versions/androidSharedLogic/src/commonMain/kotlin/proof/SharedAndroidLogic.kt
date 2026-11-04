package proof

import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName

suspend fun verifySharedAndroidEngine(expected: String): String {
    fun requireClosed() {
        check(runCatching { PariKt.eval("2+2") }.exceptionOrNull() is IllegalStateException) {
            "Engine initialized before the application requested it"
        }
    }
    requireClosed()
    PariKt.close() // Safe even though Startup has only recorded the temporary directory.
    for (size in listOf(0L, -1L)) {
        try { PariKt.init(size); error("Accepted invalid stack size $size") }
        catch (_: IllegalArgumentException) { }
        requireClosed()
    }
    PariKt.init(8_000_000L) // The application chooses the initial stack size.
    try {
        fun result(command: String) = PariKt.eval(command).substringAfterLast('=').trim()
        check(result("2+2") == "4") { "Explicit Android initialization failed" }
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
    } finally { PariKt.close() }
    PariKt.close()
    requireClosed()
    PariKt.init()
    try { check(PariKt.eval("6*7").substringAfterLast('=').trim() == "42") }
    finally { PariKt.close() }
    return "PASS Android PARI $expected deferred startup/explicit init/reinit/arithmetic/factorization/error recovery/JNI boundaries"
}
