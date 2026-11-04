import org.gradle.api.artifacts.component.ProjectComponentIdentifier

val consumer = project
@Suppress("UNCHECKED_CAST")
val selection = extra["parikt.selection"] as Triple<String, String, Boolean>
@Suppress("UNCHECKED_CAST")
val engines = gradle.extensions.extraProperties["parikt.engines"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.android.helpers"] as Map<String, String>
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    val classpaths = consumer.configurations.filter { configuration ->
        configuration.isCanBeResolved && !configuration.name.contains("test", ignoreCase = true) &&
            (configuration.name.endsWith("CompileClasspath") || configuration.name.endsWith("RuntimeClasspath")) &&
            (configuration.name.contains("android", true) || consumer.plugins.hasPlugin("com.android.application") || consumer.plugins.hasPlugin("com.android.library"))
    }
    tasks.register("verifyPariAndroidGraph") {
        group = "verification"
        val owner = consumer.path
        val selectedEngine = engines.getValue(selection.first)
        val selectedHelper = helpers.getValue(selection.first)
        val selectedVersion = selection.first
        val availableEngines = engines.values.toSet()
        val availableHelpers = helpers.values.toSet()
        val graphs = classpaths.associate { configuration ->
            val projects = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? ProjectComponentIdentifier)?.projectPath
            }
            configuration.name to (projects.filter { it in availableEngines } to projects.filter { it in availableHelpers })
        }
        doLast {
            check(graphs.isNotEmpty()) { "No Android main classpaths found in $owner" }
            graphs.forEach { (name, graph) ->
                val (providers, nativeHelpers) = graph
                val runtime = name.endsWith("RuntimeClasspath")
                check(providers.all { it == selectedEngine } && (!runtime || providers.size == 1)) {
                    "Wrong Android engine in $owner:$name: $providers"
                }
                check(nativeHelpers.all { it == selectedHelper } && (!runtime || nativeHelpers.size == 1)) {
                    "Wrong Android helper in $owner:$name: $nativeHelpers"
                }
                logger.lifecycle("PASS $owner:$name Android PARI=$selectedVersion engine=$selectedEngine helper=$selectedHelper")
            }
        }
    }
}
