import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.util.jar.JarFile
import java.security.MessageDigest

plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false
}

@Suppress("UNCHECKED_CAST")
val selections = extra["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
@Suppress("UNCHECKED_CAST")
val engineProjects = extra["parikt.engineProjects"] as Map<String, String>
subprojects {
    val consumerPath = path
    val selection = selections[consumerPath] ?: return@subprojects
    val enginePath = engineProjects.getValue(selection.first)
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(project(":parikt")).using(project(enginePath))
        }
        // Inspect the actual resolved graph, not all declared dependencies (which
        // would confuse test-only edges with production requirements).
        incoming.afterResolve {
            val queue = ArrayDeque<Pair<org.gradle.api.artifacts.result.ResolvedComponentResult, List<String>>>()
            queue.add(incoming.resolutionResult.rootComponent.get() to listOf(consumerPath))
            val visited = mutableSetOf<org.gradle.api.artifacts.component.ComponentIdentifier>()
            while (queue.isNotEmpty()) {
                val (node, chain) = queue.removeFirst()
                if (!visited.add(node.id)) continue
                val nodePath = (node.id as? ProjectComponentIdentifier)?.projectPath
                val requirement = selections[nodePath]
                if (requirement != null && requirement.third && requirement.first != selection.first) {
                    throw GradleException("PARI requirement conflict in ${consumerPath}:$name: " +
                        "${selection.first} (${selection.second}) conflicts with ${requirement.first} (${requirement.second}); " +
                        "dependency path: ${chain.joinToString(" -> ")}")
                }
                node.dependencies.forEach { edge ->
                    when (edge) {
                        is ResolvedDependencyResult -> queue.add(edge.selected to (chain +
                            ((edge.selected.id as? ProjectComponentIdentifier)?.projectPath ?: edge.selected.id.displayName)))
                        is UnresolvedDependencyResult -> throw GradleException("Unresolved dependency in ${consumerPath}:$name: ${edge.attempted}", edge.failure)
                    }
                }
            }
        }
    }
    plugins.withId("application") {
        tasks.withType<JavaExec>().configureEach {
            dependsOn("$enginePath:buildJvmNativeDependencies")
            doFirst {
                val engine = project(enginePath).tasks.named("buildJvmNativeDependencies").get().outputs.files.singleFile
                systemProperty("java.library.path", engine.absolutePath)
                args(selection.first)
            }
        }
    }
    tasks.register("verifyEngineGraph") {
        dependsOn("classes", "$enginePath:jvmJar")
        doLast {
            listOf("compileClasspath", "runtimeClasspath").forEach { name ->
                val configuration = configurations.getByName(name)
                val engines = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                    (it.id as? ProjectComponentIdentifier)?.projectPath
                }.filter { it in engineProjects.values }
                check(engines == listOf(enginePath)) { "$path:$name expected $enginePath, got $engines" }
                val classOwners = mutableMapOf<String, String>()
                val metadata = mutableListOf<String>()
                configuration.files.filter { it.extension == "jar" }.forEach { file ->
                    JarFile(file).use { jar ->
                        jar.entries().asSequence().forEach { entry ->
                            if (entry.name.startsWith("com/elianfabian/parikt/") && entry.name.endsWith(".class")) {
                                check(classOwners.put(entry.name, file.name) == null) { "Duplicate PariKt class ${entry.name}" }
                            }
                            if (entry.name == "META-INF/parikt.kotlin_module") metadata.add(file.name)
                        }
                    }
                }
                check(classOwners.containsKey("com/elianfabian/parikt/PariKt.class"))
                check(metadata.size == 1) { "Expected one stable parikt Kotlin module: $metadata" }
                logger.lifecycle("PASS ${consumerPath}:$name engine=$enginePath classes=${classOwners.size} Kotlin module=parikt")
                configuration.incoming.resolutionResult.allDependencies.filterIsInstance<ResolvedDependencyResult>()
                    .filter { (it.selected.id as? ProjectComponentIdentifier)?.projectPath in engineProjects.values }
                    .forEach { logger.lifecycle("  ${it.from.id.displayName} -> ${it.requested.displayName} => ${it.selected.id.displayName}") }
            }
        }
    }
}

tasks.register("verifyEngineIdentity") {
    dependsOn(engineProjects.values.map { "$it:jvmJar" })
    doLast {
        val identities = engineProjects.values.map { path ->
            val file = project(path).tasks.named("jvmJar").get().outputs.files.singleFile
            JarFile(file).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith(".class") || it.name.endsWith(".kotlin_module") }
                    .associate { entry -> entry.name to jar.getInputStream(entry).use {
                        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
                    } }
            }
        }
        check(identities.distinct().size == 1) { "JVM class/Kotlin metadata identity differs across source components" }
        logger.lifecycle("PASS engine identity: identical JVM classes and Kotlin module metadata across ${identities.size} components")
    }
}
