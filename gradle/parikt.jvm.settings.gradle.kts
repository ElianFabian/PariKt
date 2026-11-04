import java.util.jar.JarFile
import java.security.MessageDigest
import java.util.Properties
import org.gradle.kotlin.dsl.withGroovyBuilder

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
            // Compile one provider definition with the project's KGP classpath. Applied typed
            // scripts would have a separate classloader; this is a build script, not a plugin.
            val content = providers.fileContents(layout.settingsDirectory.file(File(checkout, "parikt/build.gradle.kts").absolutePath)).asText.get()
            val script = File(this, "build.gradle.kts")
            script.writeText(content)
            val manifest = File(this, "src/androidMain/AndroidManifest.xml").apply { parentFile.mkdirs() }
            manifest.writeText(File(checkout, "parikt/src/androidMain/AndroidManifest.xml").readText())
        }
    }
    gradle.extensions.extraProperties["parikt.jvm.engines"] = engines
    gradle.extensions.extraProperties["parikt.engines"] = engines
    val helpers = engines.mapValues { (_, engine) ->
        if (engine == ":parikt") ":pariktAndroidNative" else engine.substringBeforeLast(':') + ":androidNative"
    }
    gradle.extensions.extraProperties["parikt.android.helpers"] = helpers
    helpers.values.filter { it != ":pariktAndroidNative" }.forEach { path ->
        include(path)
        project(path).projectDir = File(settingsDir, "build/parikt/android-helper-projects/${path.substringBeforeLast(':').removePrefix(":")}").apply {
            mkdirs()
            File(this, "build.gradle.kts").writeText(File(checkout, "pariktAndroidNative/build.gradle.kts").readText())
        }
    }
    gradle.beforeProject {
        val selected = if (path == ":parikt") defaultVersion?.let {
            Triple(it, File(settingsDir, "parikt.properties").absolutePath, false)
        } else selections[path]
        engines.entries.find { it.value == path && path != ":parikt" }?.let {
            extensions.extraProperties["parikt.checkout"] = checkout
            extensions.extraProperties["parikt.engineVersion"] = it.key

        }
        helpers.entries.find { it.value == path && path != ":pariktAndroidNative" }?.let {
            extensions.extraProperties["parikt.engineVersion"] = it.key

        }
        if (selected != null) {
            extensions.extraProperties["parikt.jvm.selection"] = selected
            extensions.extraProperties["parikt.selection"] = selected
            if (path !in engines.values) {
                val consumer = this
                // Package handlers survive in KGP task state. Freeze only serializable
                // selection data into them; project discovery finishes before tasks execute.
                @Suppress("UNCHECKED_CAST")
                val participatingPaths = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
                val consumerPath = consumer.path
                val selectedPackage = "@parikt/engine-" + selected.first.lowercase().replace('.', '-')
                val packageLocation = "file:../../../parikt/web-engines/${selected.first}"
                plugins.withId("org.jetbrains.kotlin.multiplatform") {
                    @Suppress("UNCHECKED_CAST")
                    val targets = consumer.extensions.getByName("kotlin").withGroovyBuilder { getProperty("targets") } as NamedDomainObjectCollection<Any>
                    targets.configureEach {
                        val target = this
                        if (target.withGroovyBuilder { getProperty("platformType") }.toString() in setOf("js", "wasm")) {
                            @Suppress("UNCHECKED_CAST")
                            val compilations = target.withGroovyBuilder { getProperty("compilations") } as NamedDomainObjectCollection<Any>
                            compilations.configureEach {
                                withGroovyBuilder {
                                    "packageJson"(Action<Any> {
                                        if (consumerPath in participatingPaths) {
                                            @Suppress("UNCHECKED_CAST")
                                            val dependencies = withGroovyBuilder { getProperty("dependencies") } as MutableMap<String, String>
                                            dependencies.keys.removeAll { it == "@parikt/engine" || (it.startsWith("@parikt/engine-") && it != selectedPackage) }
                                            dependencies[selectedPackage] = packageLocation
                                        }
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Apply after the project's plugin block has established its classloader.
    // In particular, do not create a root script scope before catalog accessors.
    gradle.afterProject {
        if (extensions.extraProperties.has("parikt.jvm.selection")) {
            apply(from = File(checkout, "gradle/parikt.jvm.gradle.kts"))
            if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") && path !in engines.values) {
                apply(from = File(checkout, "gradle/parikt.native.gradle.kts"))
                apply(from = File(checkout, "gradle/parikt.web.gradle.kts"))
            }
            if (listOf("com.android.application", "com.android.library", "com.android.kotlin.multiplatform.library").any(plugins::hasPlugin) && path !in engines.values) {
                apply(from = File(checkout, "gradle/parikt.android.gradle.kts"))
            }
        }
    }
    gradle.projectsEvaluated {
        val buildRoot = rootProject
        buildRoot.tasks.register("verifyPariNativeIdentity") {
            group = "verification"
            dependsOn(engines.values.flatMap { listOf("$it:compileKotlinLinuxX64", "$it:compileKotlinMingwX64") })
            val providers = engines.values.associateWith { path -> buildRoot.project(path).layout.buildDirectory.get().asFile }
            doLast {
                check(providers.isNotEmpty()) { "No PARI providers selected." }
                listOf("linuxX64", "mingwX64").forEach { target ->
                    listOf("klib/parikt", "cinterop/parikt-cinterop-pari").forEach { relative ->
                        val identities = providers.map { (path, buildDirectory) ->
                            val directory = File(buildDirectory, "classes/kotlin/$target/main/$relative")
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
            val jars = engines.values.map { path -> buildRoot.project(path).tasks.named("jvmJar").get().outputs.files }
            doLast {
                check(jars.isNotEmpty()) { "No JVM PARI providers selected. Set pari.version in a participating module's parikt.properties or the root file." }
                val identities = jars.map { files ->
                    val file = files.singleFile
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
