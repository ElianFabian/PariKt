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
if (providers.gradleProperty("nativeProof").isPresent) {
    include(":nativeAppA", ":nativeAppB", ":nativeSharedLogic")
    listOf(":nativeAppA", ":nativeAppB").forEach {
        project(it).buildFileName = "../native-app.gradle.kts"
    }
}

// Default JVM scaffold, with opt-in Native linking proof. Production platform gates remain.
gradle.settingsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    @Suppress("UNCHECKED_CAST")
    val defaultSelection = gradle.extensions.extraProperties["parikt.defaultSelection"] as Triple<String, String, Boolean>?
    val engineProjects = (selections.values.map { it.first } + listOfNotNull(defaultSelection?.first))
        .distinct().associateWith { version -> ":pariktEngine_" + version.replace('.', '_') +
            if (providers.gradleProperty("nativeProof").isPresent) ":parikt" else "" }
    if (engineProjects.size > 1 && providers.gradleProperty("jvmNativeDir").isPresent) {
        throw GradleException("jvmNativeDir is ambiguous with multiple PARI engines; use the versioned default directories.")
    }
    include(":parikt")
    project(":parikt").projectDir = file("build/parikt/selector").apply { mkdirs() }
    engineProjects.forEach { (_, path) ->
        include(path)
        if (path.endsWith(":parikt")) {
            val parent = project(path.substringBeforeLast(':'))
            parent.projectDir = file("build/parikt/engineProjects/${path.removePrefix(":").substringBefore(':')}").apply { mkdirs() }
            // This directory can also contain the ordinary JVM fixture's generated build file.
            parent.buildFileName = "namespace.gradle.kts"
            File(parent.projectDir, parent.buildFileName).writeText("// Namespace for a selected Native provider.\n")
        }
        project(path).projectDir = file("build/parikt/engineProjects/${path.removePrefix(":").replace(':', '/')}").apply {
            mkdirs()
            val base = providers.fileContents(layout.settingsDirectory.file("engine.gradle.kts")).asText.get()
            val content = if (providers.gradleProperty("nativeProof").isPresent) {
                val native = providers.fileContents(layout.settingsDirectory.file("native-engine.gradle.kts")).asText.get()
                val (imports, body) = native.lines().partition { it.startsWith("import ") }
                imports.joinToString("\n") + "\n" + base + "\nrun {\n" + body.joinToString("\n") + "\n}\n"
            } else base
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
