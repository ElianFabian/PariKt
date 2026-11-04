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
            val base = providers.fileContents(layout.settingsDirectory.file(File(checkout, "gradle/parikt.jvm-engine.gradle.kts").absolutePath)).asText.get()
            val extensions = listOf("native", "web").map { platform ->
                providers.fileContents(layout.settingsDirectory.file(File(checkout, "gradle/parikt.$platform-engine.gradle.kts").absolutePath)).asText.get()
                    .lines().partition { it.startsWith("import ") }
            }
            val content = "@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)\n" +
                extensions.flatMap { it.first }.distinct().joinToString("\n") + "\n" + base +
                extensions.joinToString("\n") { "\nrun {\n" + it.second.joinToString("\n") + "\n}\n" }
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
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
            // Gradle chooses an empty script source if the file is absent at registration.
            File(this, "build.gradle.kts").takeUnless { it.exists() }?.writeText("// Scoped Android helper; configured before project evaluation.\n")
        }
    }
    // Inspect the root's established plugin classpath only when a child starts evaluation.
    // This keeps module-only JVM/Native/web builds free of an Android plugin prerequisite.
    fun androidAvailable(root: Project): Boolean = root.buildscript.configurations.findByName("classpath")
        ?.incoming?.resolutionResult?.allComponents?.any {
            it.moduleVersion?.group == "com.android.tools.build" && it.moduleVersion?.name == "gradle"
        } == true || root.buildscript.classLoader.getResource("com/android/build/gradle/LibraryPlugin.class") != null
    gradle.beforeProject {
        val selected = if (path == ":parikt") defaultVersion?.let {
            Triple(it, File(settingsDir, "parikt.properties").absolutePath, false)
        } else selections[path]
        engines.entries.find { it.value == path && path != ":parikt" }?.let {
            extensions.extraProperties["parikt.checkout"] = checkout
            extensions.extraProperties["parikt.engineVersion"] = it.key
            if (androidAvailable(rootProject)) {
                val script = File(projectDir, "build.gradle.kts")
                val template = File(checkout, "gradle/parikt.android-engine.gradle.kts").readText()
                val (imports, body) = template.lines().partition { it.startsWith("import ") }
                var content = script.readText()
                content = content.replace("plugins { id(\"org.jetbrains.kotlin.multiplatform\") }",
                    "plugins { id(\"org.jetbrains.kotlin.multiplatform\"); id(\"com.android.kotlin.multiplatform.library\") }")
                val headerEnd = content.indexOf('\n') + 1
                content = content.substring(0, headerEnd) + imports.filter { it !in content.lines() }.joinToString("\n") + "\n" + content.substring(headerEnd) +
                    "\nrun {\n" + body.joinToString("\n") + "\n}\n"
                if (script.readText() != content) script.writeText(content)
                val manifest = File(projectDir, "src/androidMain/AndroidManifest.xml").apply { parentFile.mkdirs() }
                val xml = File(checkout, "parikt/src/androidMain/AndroidManifest.xml").readText()
                if (!manifest.exists() || manifest.readText() != xml) manifest.writeText(xml)
            }
        }
        helpers.entries.find { it.value == path && path != ":pariktAndroidNative" }?.let {
            extensions.extraProperties["parikt.engineVersion"] = it.key
            val content = if (androidAvailable(rootProject)) File(checkout, "pariktAndroidNative/build.gradle.kts").readText()
                else "// Android helper is inactive: the consuming root does not load AGP.\n"
            val script = File(projectDir, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
        if (selected != null) {
            extensions.extraProperties["parikt.jvm.selection"] = selected
            extensions.extraProperties["parikt.selection"] = selected
            if (path !in engines.values) {
                val consumer = this
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
                                        @Suppress("UNCHECKED_CAST")
                                        val participants = gradle.extensions.extraProperties.properties["parikt.participants"] as? Set<String> ?: emptySet()
                                        if (consumer.path in participants) {
                                            @Suppress("UNCHECKED_CAST")
                                            val dependencies = withGroovyBuilder { getProperty("dependencies") } as MutableMap<String, String>
                                            val packageId = "@parikt/engine-" + selected.first.lowercase().replace('.', '-')
                                            dependencies.keys.removeAll { it == "@parikt/engine" || (it.startsWith("@parikt/engine-") && it != packageId) }
                                            dependencies[packageId] = "file:../../../parikt/web-engines/${selected.first}"
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
