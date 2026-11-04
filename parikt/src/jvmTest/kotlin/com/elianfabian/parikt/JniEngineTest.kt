package com.elianfabian.parikt

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class JniEngineTest {
    @Test fun commands() = probe("commands")
    @Test fun quitIsRecoverable() = probe("quit")
    @Test fun emptyCommands() = probe("empty")
    @Test fun closeIsIdempotent() = probe("lifecycle")
    @Test fun invalidInitialization() = probe("initialization")
    @Test fun defensiveNativeEntryPoints() = probe("native-validation")

    private fun probe(scenario: String) {
        val prefix = "parikt.boundary.engine."
        val engines = System.getProperties().stringPropertyNames().filter { it.startsWith(prefix) }
        check(engines.isNotEmpty()) { "Run jvmTest through the source integration to prepare its native engines." }
        val classpath = listOf(JniBoundaryProbe::class.java, PariKt::class.java, Unit::class.java,
            Continuation::class.java, CoroutineScope::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }.distinct().joinToString(File.pathSeparator)
        for (property in engines.sorted()) {
            val version = property.removePrefix(prefix)
            val log = Files.createTempFile("parikt-jni-$scenario-", ".log").toFile()
            val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
                "-XX:-CreateCoredumpOnCrash", "-Djava.library.path=${System.getProperty(property)}",
                "-cp", classpath, JniBoundaryProbe::class.java.name, scenario, version)
                .redirectErrorStream(true).redirectOutput(log).start()
            try {
                check(process.waitFor(60, TimeUnit.SECONDS)) { "JNI $version $scenario timed out:\n${log.readText()}" }
                val output = log.readText()
                assertEquals(0, process.exitValue(), "JNI $version $scenario failed:\n$output")
                check("PASS JNI $version $scenario" in output) { output }
            } finally {
                if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
                log.delete()
            }
        }
    }
}

/** Each scenario owns a process so a native regression becomes a test failure, not a worker crash. */
internal object JniBoundaryProbe {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val (scenario, version) = args
        if (scenario == "native-validation") {
            PariKt.init()
            PariKt.close()
            val native = Class.forName("com.elianfabian.parikt.PariNative")
            val instance = native.getField("INSTANCE").get(null)
            val initialize = native.getDeclaredMethod("nativeInit", Long::class.javaPrimitiveType).apply { isAccessible = true }
            val invalid = listOf(0L, -1L) + if (System.getProperty("os.name").startsWith("Windows")) listOf(Long.MAX_VALUE) else emptyList()
            for (size in invalid) {
                try { initialize.invoke(instance, size); error("Native init accepted $size") }
                catch (error: InvocationTargetException) { check(error.cause is IllegalArgumentException) { error } }
            }
            repeat(2) { native.getMethod("paridroidClose").invoke(instance) }
        } else EngineBoundaryChecks.verify(scenario)
        PariKt.init()
        if (version !in setOf("master", "latest")) check(PariKt.getVersionName() == version)
        check(PariKt.eval("6*7").substringAfterLast('=').trim() == "42")
        PariKt.close()
        println("PASS JNI $version $scenario")
    }
}
