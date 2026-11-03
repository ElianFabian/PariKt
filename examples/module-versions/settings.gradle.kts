pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
val checkout = file(providers.gradleProperty("pariktSourceDir").getOrElse("../..")).canonicalFile

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs.create("pariktLibs") { from(files(File(checkout, "gradle/libs.versions.toml"))) }
}
rootProject.name = "PariKtModuleVersions"
extra["pariktSourceDir"] = checkout
// Keep the existing placement before consumer includes; discovery is deferred.
apply(from = File(checkout, "gradle/parikt.selections.settings.gradle.kts"))
include(":appA", ":appB", ":sharedLogic")

// JVM-only scaffold. Native/web/Android selection remains gated on platform proofs.
gradle.settingsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    @Suppress("UNCHECKED_CAST")
    val defaultSelection = gradle.extensions.extraProperties["parikt.defaultSelection"] as Triple<String, String, Boolean>?
    val engineProjects = (selections.values.map { it.first } + listOfNotNull(defaultSelection?.first))
        .distinct().associateWith { version -> ":pariktEngine_" + version.replace('.', '_') }
    if (engineProjects.size > 1 && providers.gradleProperty("jvmNativeDir").isPresent) {
        throw GradleException("jvmNativeDir is ambiguous with multiple PARI engines; use the versioned default directories.")
    }
    include(":parikt")
    project(":parikt").projectDir = file("build/parikt/selector").apply { mkdirs() }
    engineProjects.forEach { (_, path) ->
        include(path)
        project(path).projectDir = file("build/parikt/engineProjects/${path.removePrefix(":")}").apply {
            mkdirs()
            val content = providers.fileContents(layout.settingsDirectory.file("engine.gradle.kts")).asText.get()
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
    }
    gradle.beforeProject {
        extensions.extraProperties["parikt.checkout"] = checkout
        extensions.extraProperties["parikt.selections"] = selections
        extensions.extraProperties["parikt.engineProjects"] = engineProjects
        engineProjects.entries.find { it.value == path }?.let {
            extensions.extraProperties["parikt.engineVersion"] = it.key
        }
        if (path == ":parikt") {
            listOf("buildJvmNativeDependencies", "jvmJar", "build", "assemble", "check").forEach { taskName ->
                tasks.register(taskName) {
                    if (defaultSelection != null) dependsOn("${engineProjects.getValue(defaultSelection.first)}:$taskName")
                    else doFirst {
                        throw GradleException("No root PARI default for :parikt:$taskName. Set pari.version in ${File(settingsDir, "parikt.properties")} " +
                            "or invoke the consuming application's task. Module overrides are not a default.")
                    }
                }
            }
        }
    }
}
