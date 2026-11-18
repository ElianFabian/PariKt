import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.testing.Test

// Ordinary applied settings script. This private candidate currently proves the JVM path.
// The consumer extracts the engine-tools JAR; no PariKt Kotlin sources are required.
val tools = (extra.properties["pariktEngineToolsDir"] as? File)?.canonicalFile
    ?: throw GradleException("Set pariktEngineToolsDir to the extracted engine-tools artifact.")
extra["pariktSourceDir"] = tools
gradle.extensions.extraProperties["parikt.binaryConsumer"] = true
apply(from = File(tools, "gradle/parikt.selections.settings.gradle.kts"))
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    @Suppress("UNCHECKED_CAST")
    val participants = extensions.extraProperties["parikt.participants"] as Set<String>
    val buildRoot = rootProject
    val versions = participants.map { selections.getValue(it).first }.distinct()
    val windows = System.getProperty("os.name").startsWith("Windows")
    val linux = System.getProperty("os.name").startsWith("Linux")
    val cache = File(buildRoot.layout.buildDirectory.get().asFile, "parikt-binary/jvm")
    val preparations = versions.associateWith { version ->
        val destination = File(cache, version)
        buildRoot.tasks.register<Exec>("preparePariJvm_" + version.replace(Regex("[^A-Za-z0-9_]"), "_")) {
            group = "native"
            description = "Prepare the single reusable JVM engine for PARI $version."
            // The C builder checks compiler/ABI/bridge fingerprints and owns cross-build locks.
            doNotTrackState("The engine builder validates its shared compiled cache.")
            outputs.dir(destination)
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
