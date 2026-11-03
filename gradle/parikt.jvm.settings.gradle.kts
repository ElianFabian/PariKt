import java.util.jar.JarFile
import java.security.MessageDigest

val checkout = extra["pariktSourceDir"] as File
gradle.settingsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    val defaultVersion = gradle.extensions.extraProperties["parikt.version"] as String
    val engines = selections.values.map { it.first }.plus(defaultVersion).distinct().associateWith { version ->
        if (version == defaultVersion) ":parikt" else ":pariktEngine_" + version.replace('.', '_')
    }
    if (engines.size > 1 && (providers.gradleProperty("jvmNativeDir").isPresent || providers.systemProperty("parikt.native.dir").isPresent)) {
        throw GradleException("A global jvmNativeDir/parikt.native.dir is ambiguous with multiple PARI versions. Remove it to use the selected engines' versioned output directories.")
    }
    engines.filterValues { it != ":parikt" }.forEach { (_, path) ->
        include(path)
        project(path).projectDir = File(settingsDir, "build/parikt/engineProjects/${path.removePrefix(":")}").apply {
            mkdirs()
            val content = providers.fileContents(layout.settingsDirectory.file(File(checkout, "gradle/parikt.jvm-engine.gradle.kts").absolutePath)).asText.get()
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
    }
    gradle.extensions.extraProperties["parikt.jvm.engines"] = engines
    gradle.beforeProject {
        val selected = if (path == ":parikt") Triple(defaultVersion, File(settingsDir, "parikt.properties").absolutePath, false) else selections[path]
        engines.entries.find { it.value == path && path != ":parikt" }?.let {
            extensions.extraProperties["parikt.checkout"] = checkout
            extensions.extraProperties["parikt.engineVersion"] = it.key
        }
        if (selected != null) {
            extensions.extraProperties["parikt.jvm.selection"] = selected
        }
    }
    // Apply after the project's plugin block has established its classloader.
    // In particular, do not create a root script scope before catalog accessors.
    gradle.afterProject {
        if (extensions.extraProperties.has("parikt.jvm.selection")) {
            apply(from = File(checkout, "gradle/parikt.jvm.gradle.kts"))
        }
    }
    gradle.projectsEvaluated {
        val buildRoot = rootProject
        buildRoot.tasks.register("verifyPariJvmIdentity") {
            group = "verification"
            dependsOn(engines.values.map { "$it:jvmJar" })
            doLast {
                val identities = engines.values.map { path ->
                    val file = buildRoot.project(path).tasks.named("jvmJar").get().outputs.files.singleFile
                    JarFile(file).use { jar ->
                        jar.entries().asSequence().filter { it.name.endsWith(".class") || it.name.endsWith(".kotlin_module") }
                            .associate { entry -> entry.name to jar.getInputStream(entry).use {
                                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
                            } }
                    }
                }
                check(identities.distinct().size == 1) { "JVM class/Kotlin metadata identity differs between PARI providers" }
                logger.lifecycle("PASS production JVM identity: ${identities.size} providers with identical classes and Kotlin module metadata")
            }
        }
    }
}
