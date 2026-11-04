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
        doLast {
            check(classpaths.isNotEmpty()) { "No Android main classpaths found in ${consumer.path}" }
            classpaths.forEach { configuration ->
                val projects = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                    (it.id as? ProjectComponentIdentifier)?.projectPath
                }
                val providers = projects.filter { it in engines.values }
                val nativeHelpers = projects.filter { it in helpers.values }
                val runtime = configuration.name.endsWith("RuntimeClasspath")
                check(providers.all { it == engines.getValue(selection.first) } && (!runtime || providers.size == 1)) {
                    "Wrong Android engine in ${consumer.path}:${configuration.name}: $providers"
                }
                check(nativeHelpers.all { it == helpers.getValue(selection.first) } && (!runtime || nativeHelpers.size == 1)) {
                    "Wrong Android helper in ${consumer.path}:${configuration.name}: $nativeHelpers"
                }
                logger.lifecycle("PASS ${consumer.path}:${configuration.name} Android PARI=${selection.first} engine=${engines.getValue(selection.first)} helper=${helpers.getValue(selection.first)}")
            }
        }
    }
}
