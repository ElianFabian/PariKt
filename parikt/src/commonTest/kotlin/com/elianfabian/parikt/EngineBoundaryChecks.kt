package com.elianfabian.parikt

/** Public API contracts shared by the real JVM, Native and web backends. */
internal object EngineBoundaryChecks {
    val scenarios = listOf("commands", "quit", "empty", "lifecycle", "initialization")

    suspend fun all() { scenarios.forEach { verify(it) } }

    suspend fun verify(scenario: String) {
        PariKt.close()
        if (scenario == "initialization") {
            for (size in listOf(0L, -1L, Long.MIN_VALUE)) {
                try { PariKt.init(size); error("Accepted invalid stack size $size") }
                catch (_: IllegalArgumentException) { }
                PariKt.close()
            }
        }
        PariKt.init()
        try {
            when (scenario) {
                "commands" -> {
                    val original = PariKt.getSupportedCommands().toSet()
                    check("factor" in original)
                    // The broken half-table traversal returned only 305 on this release.
                    if (PariKt.getVersionName() == "2.19.0") check(original.size >= 500) { "Incomplete command table: ${original.size}" }
                    // Exceed JNI's former 1000-pointer buffer and exercise every hash bucket.
                    PariKt.eval("for(i=1,1200,eval(Str(\"parikt_boundary_fn_\",i,\"(x)=x+1\")));")
                    val expanded = PariKt.getSupportedCommands().toSet()
                    check(expanded.containsAll(original))
                    for (i in 1..1200) check("parikt_boundary_fn_$i" in expanded) { "Missing function $i" }
                    check(expanded.size >= original.size + 1200)
                }
                "quit" -> for (command in listOf("quit", "quit()", "quit(0)", "if(1,quit())")) {
                    check(PariKt.eval(command).contains("PariKt.close")) { "No recoverable error for $command" }
                    check(result("6*7") == "42")
                }
                "empty" -> repeat(3) { check(PariKt.eval("").isEmpty()); check(result("6*7") == "42") }
                "lifecycle" -> {
                    PariKt.close()
                    PariKt.close()
                    PariKt.init()
                }
                "initialization" -> Unit
                else -> error("Unknown boundary scenario: $scenario")
            }
            check(result("6*7") == "42")
        } finally { PariKt.close() }
        PariKt.close()
    }

    private fun result(command: String) = PariKt.eval(command).substringAfterLast('=').trim()
}
