import org.gradle.api.artifacts.component.ProjectComponentIdentifier

val consumer = project
@Suppress("UNCHECKED_CAST")
val selection = extra["parikt.selection"] as Triple<String, String, Boolean>
@Suppress("UNCHECKED_CAST")
val engines = gradle.extensions.extraProperties["parikt.engines"] as Map<String, String>
val engine = engines.getValue(selection.first)
val staged = consumer.rootProject.layout.buildDirectory.dir("parikt/web-engines/${selection.first}")
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    val names = listOf("js", "wasmJs").filter { consumer.configurations.findByName("${it}CompileClasspath") != null }
    if (names.isEmpty()) return@projectsEvaluated
    consumer.tasks.matching { it.name.endsWith("PackageJson") }.configureEach { inputs.property("parikt.selectedVersion", selection.first) }
    consumer.tasks.matching { it.name.endsWith("Webpack") }.configureEach {
        dependsOn("$engine:prepareWebEnginePackage")
        inputs.dir(staged)
    }
    consumer.tasks.matching { (it.name.contains("Node") && (it.name.endsWith("Run") || it.name.endsWith("Test"))) || it.name.endsWith("BrowserTest") }.configureEach {
        dependsOn("$engine:prepareWebEnginePackage")
    }
    consumer.tasks.register("verifyPariWebGraph") {
        group = "verification"
        doLast {
            names.forEach { name ->
                listOf("CompileClasspath", "RuntimeClasspath").forEach { scope ->
                    val providers = consumer.configurations.getByName("$name$scope").incoming.resolutionResult.allComponents.mapNotNull {
                        (it.id as? ProjectComponentIdentifier)?.projectPath
                    }.filter { it in engines.values }
                    check(providers.all { it == engine } && (scope != "RuntimeClasspath" || providers.size == 1)) {
                        "${consumer.path}:$name$scope expected selected $engine (one at runtime), got $providers"
                    }
                }
                logger.lifecycle("PASS ${consumer.path}:$name web PARI=${selection.first} provider=$engine")
            }
        }
    }
}
