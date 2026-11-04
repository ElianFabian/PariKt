import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.tasks.testing.Test
import java.util.jar.JarFile

// Applied source integration, using only Gradle APIs (no custom plugin).
val consumer = project
@Suppress("UNCHECKED_CAST")
val selection = extra["parikt.jvm.selection"] as Triple<String, String, Boolean>
@Suppress("UNCHECKED_CAST")
val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
@Suppress("UNCHECKED_CAST")
val engines = gradle.extensions.extraProperties["parikt.jvm.engines"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.android.helpers"] as Map<String, String>
val enginePath = engines.getValue(selection.first)
fun platform(configuration: Configuration): String? = configuration.attributes.keySet()
    .find { it.name == "org.jetbrains.kotlin.platform.type" }
    ?.let { configuration.attributes.getAttribute(it)?.toString() }
fun androidProject() = listOf("com.android.application", "com.android.library", "com.android.kotlin.multiplatform.library")
    .any { consumer.plugins.hasPlugin(it) }
fun jvmConfiguration(configuration: Configuration): Boolean {
    val type = platform(configuration)
    if (type != null) return type == "jvm"
    val plainJvm = !androidProject() && !consumer.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") &&
        (consumer.plugins.hasPlugin("java") || consumer.plugins.hasPlugin("org.jetbrains.kotlin.jvm"))
    val usage = configuration.attributes.getAttribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE)?.name
    // KMP's Java source-set classpaths can omit Kotlin's platform attribute.
    val environment = configuration.attributes.keySet().find { it.name == "org.gradle.jvm.environment" }
        ?.let { configuration.attributes.getAttribute(it)?.toString() }
    return (plainJvm || environment == "standard-jvm") && usage in setOf("java-api", "java-runtime")
}
fun routedConfiguration(configuration: Configuration): Boolean = jvmConfiguration(configuration) ||
    platform(configuration) in setOf("common", "native", "js", "wasm", "androidJvm") ||
    (androidProject() && configuration.attributes.getAttribute(org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE)?.name in setOf("java-api", "java-runtime"))
consumer.configurations.configureEach {
    val configuration = this
    resolutionStrategy.dependencySubstitution {
        val target = project(enginePath)
        all {
            val reference = requested as? org.gradle.api.artifacts.component.ProjectComponentSelector
            if (reference != null && reference.projectPath != ":parikt" && reference.projectPath in engines.values &&
                reference.projectPath != enginePath && routedConfiguration(configuration)) {
                throw GradleException("PARI provider conflict in ${consumer.path}:${configuration.name}: explicit provider ${reference.projectPath} " +
                    "conflicts with selected $enginePath (${selection.first}, ${selection.second}); dependency declared in ${consumer.buildFile}")
            }
            if (reference?.projectPath == ":parikt" && enginePath != ":parikt" && routedConfiguration(configuration)) {
                useTarget(target, "PARI ${selection.first} selected by ${selection.second}")
            }
            val helper = helpers.getValue(selection.first)
            if (reference != null && routedConfiguration(configuration)) {
                if (reference.projectPath != ":pariktAndroidNative" && reference.projectPath in helpers.values && reference.projectPath != helper) {
                    throw GradleException("PARI Android helper conflict in ${consumer.path}:${configuration.name}: explicit helper ${reference.projectPath} conflicts with selected $helper (${selection.first}, ${selection.second}); dependency declared in ${consumer.buildFile}")
                }
                if (reference.projectPath == ":pariktAndroidNative" && helper != ":pariktAndroidNative") {
                    useTarget(project(helper), "Android PARI ${selection.first} selected by ${selection.second}")
                }
            }
        }
    }
    incoming.afterResolve {
        if (!routedConfiguration(configuration)) return@afterResolve
        val queue = ArrayDeque<Pair<org.gradle.api.artifacts.result.ResolvedComponentResult, List<String>>>()
        queue.add(incoming.resolutionResult.rootComponent.get() to listOf(consumer.path))
        val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
        while (queue.isNotEmpty()) {
            val (node, chain) = queue.removeFirst()
            if (!visited.add(node.id)) continue
            val path = (node.id as? ProjectComponentIdentifier)?.projectPath
            val requirement = selections[path]
            if (requirement != null && requirement.third && requirement.first != selection.first) {
                throw GradleException("PARI requirement conflict in ${consumer.path}:${configuration.name}: " +
                    "${selection.first} (${selection.second}) conflicts with ${requirement.first} (${requirement.second}); " +
                    "dependency path: ${chain.joinToString(" -> ")}")
            }
            node.dependencies.forEach { edge ->
                when (edge) {
                    is ResolvedDependencyResult -> queue.add(edge.selected to (chain +
                        ((edge.selected.id as? ProjectComponentIdentifier)?.projectPath ?: edge.selected.id.displayName)))
                    is UnresolvedDependencyResult -> throw GradleException("Unresolved dependency in ${consumer.path}:${configuration.name}: ${edge.attempted}", edge.failure)
                }
            }
        }
    }
}
gradle.projectsEvaluated {
    val hasJvm = consumer.configurations.any { platform(it) == "jvm" } ||
        (!androidProject() && consumer.plugins.hasPlugin("java"))
    if (!hasJvm) return@projectsEvaluated
    val nativeTask = consumer.providers.provider {
        consumer.rootProject.project(enginePath).tasks.named("buildJvmNativeDependencies").get()
    }
    val manualDirectory = consumer.providers.systemProperty("parikt.native.dir").orNull?.let(consumer.rootProject::file)
    consumer.tasks.withType<JavaExec>().configureEach {
        dependsOn(nativeTask)
        systemProperty("parikt.expected.version", selection.first)
        doFirst {
            systemProperty("java.library.path", (manualDirectory ?: nativeTask.get().outputs.files.singleFile).absolutePath)
        }
    }
    consumer.tasks.withType<Test>().configureEach {
        if (!consumer.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") || name.contains("jvm", ignoreCase = true)) {
            dependsOn(nativeTask)
            inputs.property("parikt.version", selection.first)
            inputs.files(nativeTask.map { it.outputs.files })
            systemProperty("parikt.expected.version", selection.first)
            doFirst {
                systemProperty("java.library.path", (manualDirectory ?: nativeTask.get().outputs.files.singleFile).absolutePath)
            }
        }
    }
    val names = if (consumer.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
        listOf("jvmCompileClasspath", "jvmRuntimeClasspath") else listOf("compileClasspath", "runtimeClasspath")
    consumer.tasks.register("verifyPariJvmGraph") {
        group = "verification"
        dependsOn(names.map { consumer.configurations.getByName(it) })
        doLast {
            names.forEach { name ->
                val configuration = consumer.configurations.getByName(name)
                val providers = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                    (it.id as? ProjectComponentIdentifier)?.projectPath
                }.filter { it in engines.values }
                check(providers == listOf(enginePath)) { "${consumer.path}:$name expected one $enginePath provider, got $providers" }
                val classes = mutableSetOf<String>()
                var moduleCount = 0
                configuration.files.filter { it.extension == "jar" }.forEach { file ->
                    JarFile(file).use { jar ->
                        jar.entries().asSequence().forEach { entry ->
                            if (entry.name.startsWith("com/elianfabian/parikt/") && entry.name.endsWith(".class")) {
                                check(classes.add(entry.name)) { "Duplicate PariKt class ${entry.name} in ${consumer.path}:$name" }
                            }
                            if (entry.name == "META-INF/parikt.kotlin_module") moduleCount++
                        }
                    }
                }
                check("com/elianfabian/parikt/PariKt.class" in classes && moduleCount == 1)
                logger.lifecycle("PASS ${consumer.path}:$name PARI=${selection.first} provider=$enginePath classes=${classes.size} Kotlin module=parikt")
                configuration.incoming.resolutionResult.allDependencies.filterIsInstance<ResolvedDependencyResult>()
                    .filter { (it.selected.id as? ProjectComponentIdentifier)?.projectPath in engines.values }
                    .forEach { logger.lifecycle("  ${it.from.id.displayName} -> ${it.requested.displayName} => ${it.selected.id.displayName}") }
            }
        }
    }
}
