import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.testing.Test

// Ordinary applied settings script. Engine preparation is separate from Maven bindings.
// The consumer extracts the engine-tools JAR; no PariKt Kotlin sources are required.
val tools = (extra.properties["pariktEngineToolsDir"] as? File)?.canonicalFile
    ?: throw GradleException("Set pariktEngineToolsDir to the extracted engine-tools artifact.")
extra["pariktSourceDir"] = tools
gradle.extensions.extraProperties["parikt.binaryConsumer"] = true
apply(from = File(tools, "gradle/parikt.selections.settings.gradle.kts"))
dependencyResolutionManagement.versionCatalogs.create("pariktLibs") {
    from(files(File(tools, "gradle/libs.versions.toml")))
}
gradle.extensions.extraProperties["parikt.sourceDirectory"] = tools
gradle.extensions.extraProperties["parikt.nativeDirectory"] = File(settingsDir, "build/parikt-binary/engines")
gradle.settingsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    val helpers = selections.values.map { it.first }.distinct().associateWith { version ->
        ":pariktEngine_" + version.replace('.', '_') + ":androidNative"
    }
    gradle.extensions.extraProperties["parikt.binary.androidHelpers"] = helpers
    helpers.forEach { (_, path) ->
        include(path)
        val parent = project(path.substringBeforeLast(':'))
        parent.projectDir = File(settingsDir, "build/parikt-binary/projects/${path.removePrefix(":").substringBefore(':')}").apply { mkdirs() }
        parent.buildFileName = "namespace.gradle.kts"
        File(parent.projectDir, parent.buildFileName).writeText("// Namespace for one selected C-only engine helper.\n")
        project(path).projectDir = File(parent.projectDir, "androidNative").apply {
            mkdirs()
            File(this, "build.gradle.kts").writeText(File(tools, "pariktAndroidNative/build.gradle.kts").readText())
        }
    }
    gradle.beforeProject {
        helpers.entries.find { it.value == path }?.let { extensions.extraProperties["parikt.engineVersion"] = it.key }
    }
}
gradle.afterProject {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    if (plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") && path in selections) {
        extensions.extraProperties["parikt.binary.tools"] = tools
        apply(from = File(tools, "gradle/parikt.binary.native.gradle.kts"))
        apply(from = File(tools, "gradle/parikt.binary.web.gradle.kts"))
    }
}
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    @Suppress("UNCHECKED_CAST")
    val participants = extensions.extraProperties["parikt.participants"] as Set<String>
    val buildRoot = rootProject
    val versions = participants.map { selections.getValue(it).first }.distinct()
    @Suppress("UNCHECKED_CAST")
    val androidHelpers = extensions.extraProperties["parikt.binary.androidHelpers"] as Map<String, String>
    val windows = System.getProperty("os.name").startsWith("Windows")
    val linux = System.getProperty("os.name").startsWith("Linux")
    val x64 = System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")
    val cache = File(buildRoot.layout.buildDirectory.get().asFile, "parikt-binary/jvm")
    val preparations = versions.associateWith { version ->
        val destination = File(cache, version)
        buildRoot.tasks.register<Exec>("preparePariJvm_" + version.replace(Regex("[^A-Za-z0-9_]"), "_")) {
            group = "native"
            description = "Prepare the single reusable JVM engine for PARI $version."
            // The C builder checks compiler/ABI/bridge fingerprints and owns cross-build locks.
            doNotTrackState("The engine builder validates its shared compiled cache.")
            outputs.dir(destination)
            doFirst { check(x64 && (windows || linux)) { "PariKt JVM engine provisioning supports Windows/WSL and Linux x86_64." } }
            val script = File(tools, "parikt/native/build-jvm.sh")
            val jdk = File(System.getProperty("java.home"))
            fun wslPath(file: File): String {
                val path = file.canonicalPath.replace('\\', '/')
                require(Regex("[A-Za-z]:/.*").matches(path)) { "WSL requires an absolute drive path: $file" }
                return "/mnt/" + path[0].lowercaseChar() + path.substring(2)
            }
            when {
                windows -> commandLine("wsl.exe", "--exec", "bash", wslPath(script), "--pari-version", version,
                    "--target", "windows-x86_64", "--out", wslPath(destination), "--jni-home", wslPath(jdk))
                linux -> commandLine("bash", script, "--pari-version", version,
                    "--target", "linux-x86_64", "--out", destination, "--jni-home", jdk)
                else -> doFirst { throw GradleException("The candidate JVM engine installer currently supports Windows/WSL and Linux x86_64 only.") }
            }
        }
    }
    participants.forEach { path ->
        val consumer = buildRoot.project(path)
        val selection = selections.getValue(path)
        if (consumer.plugins.hasPlugin("com.android.application")) {
            // Shared Android libraries remain engine-free. Each final APK/AAB selects
            // its own C-only helper, so app B can override the shared library's default.
            consumer.dependencies.add("implementation", consumer.dependencies.project(mapOf("path" to androidHelpers.getValue(selection.first))))
        }
        // Validate actual resolved project edges, rather than assuming every declared
        // configuration is an application dependency (KGP has synthetic inventories).
        consumer.configurations.configureEach {
            val configuration = this
            incoming.afterResolve {
                if (configuration.name == "swiftPMDependenciesForLockFilesMetadataClasspath") return@afterResolve
                val queue = ArrayDeque<Pair<org.gradle.api.artifacts.result.ResolvedComponentResult, List<String>>>()
                queue.add(incoming.resolutionResult.rootComponent.get() to listOf(path))
                val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
                while (queue.isNotEmpty()) {
                    val (node, chain) = queue.removeFirst()
                    if (!visited.add(node.id)) continue
                    val requirement = selections[(node.id as? ProjectComponentIdentifier)?.projectPath]
                    if (requirement != null && requirement.third && requirement.first != selection.first) {
                        throw GradleException("PARI requirement conflict in $path:${configuration.name}: ${selection.first} (${selection.second}) conflicts with ${requirement.first} (${requirement.second}); dependency path: ${chain.joinToString(" -> ")}")
                    }
                    node.dependencies.forEach { edge -> when (edge) {
                        is ResolvedDependencyResult -> queue.add(edge.selected to (chain + edge.selected.id.displayName))
                        is UnresolvedDependencyResult -> throw GradleException("Unresolved dependency: ${edge.attempted}", edge.failure)
                    } }
                }
            }
        }
        val preparation = preparations.getValue(selection.first)
        val directory = File(cache, selection.first).absolutePath
        consumer.tasks.withType<JavaExec>().configureEach {
            dependsOn(preparation)
            systemProperty("java.library.path", directory)
        }
        consumer.tasks.withType<Test>().configureEach {
            if (consumer.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") && !name.contains("jvm", true)) return@configureEach
            dependsOn(preparation)
            systemProperty("java.library.path", directory)
        }
    }
}
