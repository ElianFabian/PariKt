import org.gradle.api.artifacts.ProjectDependency

// getPath replaces getDependencyProject in newer Gradle versions. Resolve the public
// accessor at configuration time so the same source installer also compiles on Gradle 8.8.
fun ProjectDependency.pariktProjectPath(): String =
    ProjectDependency::class.java.methods.firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
        ?.invoke(this) as? String
        ?: (ProjectDependency::class.java.getMethod("getDependencyProject").invoke(this) as Project).path

// Shared scoped-selection infrastructure. Apply before or after consumer
// includes: the callback observes final projectDir mappings, not guessed paths.
val checkout = extra["pariktSourceDir"] as File
apply(from = File(checkout, "gradle/parikt.properties.settings.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val selectProperties = gradle.extensions.extraProperties["parikt.selectProperties"] as (String, File) -> Triple<String, String, Boolean>?
val selections = linkedMapOf<String, Triple<String, String, Boolean>>()
val directories = linkedMapOf<String, File>()
// PackageJson handlers retain this data-only set; discovery fills it before KGP
// resolves npm models. Task execution sees a frozen serialized snapshot.
val participants = linkedSetOf<String>()
gradle.extensions.extraProperties["parikt.participants"] = participants
gradle.extensions.extraProperties["parikt.selections"] = selections
gradle.settingsEvaluated {
    fun discover(descriptor: ProjectDescriptor) {
        // These names are owned by the integration. Engine projects are added
        // after this callback, so they cannot supply consumer overrides.
        if (descriptor.path == ":parikt" || descriptor.path == ":pariktAndroidNative" ||
            descriptor.path.startsWith(":pariktEngine_")) return
        directories[descriptor.path] = descriptor.projectDir
        selectProperties(descriptor.path, descriptor.projectDir)?.let { selections[descriptor.path] = it }
        descriptor.children.sortedBy { it.path }.forEach(::discover)
    }
    discover(rootProject)
}
gradle.projectsEvaluated {
    val consumers = directories.keys.associateWith { rootProject.project(it) }
    // Discover participation from declared project dependency edges after build
    // scripts have run. No graph resolution, Kotlin compilation or native build.
    val edges = consumers.mapValues { (_, consumer) ->
        consumer.configurations.flatMap { configuration ->
            configuration.dependencies.withType(ProjectDependency::class.java).map { it.pariktProjectPath() }
        }.toSet()
    }
    var changed: Boolean
    do {
        changed = false
        edges.forEach { (path, dependencies) ->
            if (dependencies.any { it == ":parikt" || it.startsWith(":pariktEngine_") || it in participants }) {
                if (participants.add(path)) changed = true
            }
        }
    } while (changed)
    participants.sorted().forEach { path ->
        if (path !in selections) {
            throw GradleException("Missing PariKt selection for $path (${File(directories.getValue(path), "parikt.properties")}). " +
                "Set pari.version there or in ${File(settingsDir, "parikt.properties")}. A sibling module's override is not a default.")
        }
    }
    val lines = directories.keys.sorted().map { path ->
        val selection = selections[path]
        val participation = if (path in participants) "participating" else "no PariKt dependency"
        if (selection == null) "$path = unselected; $participation" else
            "$path = ${selection.first}; ${if (selection.third) "explicit" else "inherited"}; ${selection.second}; $participation"
    }
    rootProject.tasks.register("pariktSelections") {
        group = "help"
        description = "Lists effective PARI selections and origins without compiling engines."
        doLast { lines.forEach { logger.lifecycle(it) } }
    }
}
