import java.util.jar.JarFile
import java.security.MessageDigest

@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.proof.androidHelpers"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
tasks.register("verifyAndroidIdentity") {
    dependsOn(helpers.keys.map { "${engines.getValue(it)}:bundleAndroidMainClassesToCompileJar" })
    doLast {
        val identities = helpers.keys.map { version ->
            val task = project(engines.getValue(version)).tasks.named("bundleAndroidMainClassesToCompileJar").get()
            val file = task.outputs.files.files.single { it.extension == "jar" }
            JarFile(file).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") || it.name.endsWith(".kotlin_module") }
                    .associate { entry -> entry.name to jar.getInputStream(entry).use {
                        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
                    } }
            }
        }
        check(identities.all { it.containsKey("META-INF/parikt.kotlin_module") }) { "Missing stable Android Kotlin module" }
        check(identities.distinct().size == 1) { "Android classes or Kotlin metadata differ across providers" }
        logger.lifecycle("PASS Android identity: identical classes and Kotlin module metadata across ${identities.size} components")
    }
}
