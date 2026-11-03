import java.util.jar.JarFile
import java.security.MessageDigest
import java.util.Properties

val checkout = extra["pariktSourceDir"] as File
gradle.settingsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    val defaultVersion = gradle.extensions.extraProperties.properties["parikt.version"] as? String
    val engines = (selections.values.map { it.first } + listOfNotNull(defaultVersion)).distinct().associateWith { version ->
        if (version == defaultVersion) ":parikt" else ":pariktEngine_" + version.replace('.', '_') + ":parikt"
    }
    if (engines.size > 1 && (providers.gradleProperty("jvmNativeDir").isPresent || providers.systemProperty("parikt.native.dir").isPresent)) {
        throw GradleException("A global jvmNativeDir/parikt.native.dir is ambiguous with multiple PARI versions. Remove it to use the selected engines' versioned output directories.")
    }
    engines.filterValues { it != ":parikt" }.forEach { (_, path) ->
        include(path)
        val parent = project(path.substringBeforeLast(':'))
        parent.projectDir = File(settingsDir, "build/parikt/engineProjects/${path.removePrefix(":").substringBefore(':')}").apply { mkdirs() }
        parent.buildFileName = "namespace.gradle.kts"
        File(parent.projectDir, parent.buildFileName).writeText("// Namespace for a scoped PARI source provider.\n")
        project(path).projectDir = File(parent.projectDir, "parikt").apply {
            mkdirs()
            val base = providers.fileContents(layout.settingsDirectory.file(File(checkout, "gradle/parikt.jvm-engine.gradle.kts").absolutePath)).asText.get()
            val extension = providers.fileContents(layout.settingsDirectory.file(File(checkout, "gradle/parikt.native-engine.gradle.kts").absolutePath)).asText.get()
                .lines().partition { it.startsWith("import ") }
            val content = extension.first.joinToString("\n") + "\n" + base + "\nrun {\n" + extension.second.joinToString("\n") + "\n}\n"
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
    }
    gradle.extensions.extraProperties["parikt.jvm.engines"] = engines
    gradle.extensions.extraProperties["parikt.engines"] = engines
    gradle.beforeProject {
        val selected = if (path == ":parikt") defaultVersion?.let {
            Triple(it, File(settingsDir, "parikt.properties").absolutePath, false)
        } else selections[path]
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
            if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") && path !in engines.values) {
                apply(from = File(checkout, "gradle/parikt.native.gradle.kts"))
            }
        }
    }
    gradle.projectsEvaluated {
        val buildRoot = rootProject
        buildRoot.tasks.register("verifyPariNativeIdentity") {
            group = "verification"
            dependsOn(engines.values.flatMap { listOf("$it:compileKotlinLinuxX64", "$it:compileKotlinMingwX64") })
            doLast {
                check(engines.isNotEmpty()) { "No PARI providers selected." }
                listOf("linuxX64", "mingwX64").forEach { target ->
                    listOf("klib/parikt", "cinterop/parikt-cinterop-pari").forEach { relative ->
                        val identities = engines.values.map { path ->
                            val directory = buildRoot.project(path).layout.buildDirectory.dir("classes/kotlin/$target/main/$relative").get().asFile
                            val manifest = Properties().apply { File(directory, "default/manifest").inputStream().use { load(it) } }
                            val properties = listOf("unique_name", "depends", "native_targets", "interop", "package").associateWith { manifest.getProperty(it) }
                            val metadata = directory.walkTopDown().filter { it.isFile &&
                                (it.relativeTo(directory).invariantSeparatorsPath.startsWith("default/linkdata/") ||
                                    it.relativeTo(directory).invariantSeparatorsPath.startsWith("default/ir/")) }
                                .associate { it.relativeTo(directory).invariantSeparatorsPath to
                                    MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
                            check(metadata.isNotEmpty()) { "No Native metadata in $directory" }
                            logger.lifecycle("  $path $target $relative identity=${properties["unique_name"]}")
                            properties to metadata
                        }
                        check(identities.distinct().size == 1) { "Native identity/metadata differs for $target $relative" }
                        logger.lifecycle("PASS production Native identity $target $relative: ${identities.size} providers")
                    }
                }
            }
        }
        buildRoot.tasks.register("verifyPariJvmIdentity") {
            group = "verification"
            dependsOn(engines.values.map { "$it:jvmJar" })
            doLast {
                check(engines.isNotEmpty()) { "No JVM PARI providers selected. Set pari.version in a participating module's parikt.properties or the root file." }
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
