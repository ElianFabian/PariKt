pluginManagement {
    repositories {
        if (providers.gradleProperty("androidProof").isPresent) google()
        mavenCentral()
        gradlePluginPortal()
    }
    if (providers.gradleProperty("androidProof").isPresent) {
        val source = file(providers.gradleProperty("pariktSourceDir").getOrElse("../..")).canonicalFile
        val agp = File(source, "gradle/libs.versions.toml").readLines()
            .first { it.startsWith("agp = ") }.substringAfter('"').substringBefore('"')
        plugins {
            id("com.android.application") version agp
            id("com.android.library") version agp
            id("com.android.kotlin.multiplatform.library") version agp
        }
    }
}
val checkout = file(providers.gradleProperty("pariktSourceDir").getOrElse("../..")).canonicalFile

dependencyResolutionManagement {
    repositories {
        if (providers.gradleProperty("androidProof").isPresent) google()
        mavenCentral()
    }
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
if (providers.gradleProperty("webProof").isPresent) {
    include(":webAppA", ":webAppB", ":webSharedLogic")
    listOf(":webAppA", ":webAppB").forEach {
        project(it).buildFileName = "../web-app.gradle.kts"
    }
}
if (providers.gradleProperty("androidProof").isPresent) {
    // Share AGP with KGP in the root classloader, without loading Android for the default proof.
    val rootScript = file("build/parikt/android-root.gradle.kts").apply { parentFile.mkdirs() }
    val content = file("build.gradle.kts").readText().replace(
        "alias(pariktLibs.plugins.kotlin.jvm) apply false",
        "alias(pariktLibs.plugins.kotlin.jvm) apply false\n" +
            "    id(\"com.android.application\") apply false\n" +
            "    id(\"com.android.library\") apply false\n" +
            "    id(\"com.android.kotlin.multiplatform.library\") apply false"
    )
    if (!rootScript.exists() || rootScript.readText() != content) rootScript.writeText(content)
    rootProject.buildFileName = "build/parikt/android-root.gradle.kts"
    include(":androidAppA", ":androidAppB", ":androidSharedLogic")
    listOf(":androidAppA", ":androidAppB").forEach {
        project(it).buildFileName = "../android-app.gradle.kts"
    }
    // Reuse a configured checkout SDK only for this opt-in development fixture.
    val sdkFile = file("local.properties")
    if (!sdkFile.exists()) {
        val properties = java.util.Properties()
        File(checkout, "local.properties").takeIf { it.isFile }?.inputStream()?.use { properties.load(it) }
        val sdk = properties.getProperty("sdk.dir") ?: providers.environmentVariable("ANDROID_HOME")
            .orElse(providers.environmentVariable("ANDROID_SDK_ROOT")).orNull
        if (sdk != null) java.util.Properties().apply { setProperty("sdk.dir", sdk) }
            .also { data -> sdkFile.outputStream().use { data.store(it, "Generated Android proof SDK location") } }
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
            if (providers.gradleProperty("nativeProof").isPresent || providers.gradleProperty("androidProof").isPresent) ":parikt" else "" }
    val androidVersions = if (providers.gradleProperty("androidProof").isPresent) {
        listOf(":androidAppA", ":androidAppB", ":androidSharedLogic").mapNotNull { selections[it]?.first }.toSet()
    } else emptySet()
    val androidHelpers = androidVersions.associateWith { ":pariktEngine_${it.replace('.', '_')}:androidNative" }
    gradle.extensions.extraProperties["parikt.proof.androidHelpers"] = androidHelpers
    if (androidVersions.isNotEmpty()) {
        gradle.extensions.extraProperties["parikt.sourceDirectory"] = checkout
        gradle.extensions.extraProperties["parikt.nativeDirectory"] = File(checkout, "parikt/.native")
    }
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
            var base = providers.fileContents(layout.settingsDirectory.file("engine.gradle.kts")).asText.get()
            val version = engineProjects.entries.single { it.value == path }.key
            if (version in androidVersions) {
                base = base.replace("plugins { id(\"org.jetbrains.kotlin.multiplatform\") }",
                    "plugins { id(\"org.jetbrains.kotlin.multiplatform\"); id(\"com.android.kotlin.multiplatform.library\") }")
                val manifest = File(this, "src/androidMain/AndroidManifest.xml").apply { parentFile.mkdirs() }
                val content = File(checkout, "parikt/src/androidMain/AndroidManifest.xml").readText()
                if (!manifest.exists() || manifest.readText() != content) manifest.writeText(content)
            }
            val templates = listOfNotNull(
                "native-engine.gradle.kts".takeIf { providers.gradleProperty("nativeProof").isPresent },
                "web-engine.gradle.kts".takeIf { providers.gradleProperty("webProof").isPresent },
                "android-engine.gradle.kts".takeIf { version in androidVersions }
            )
            val extensions = templates.map { template ->
                    providers.fileContents(layout.settingsDirectory.file(template)).asText.get().lines()
                        .partition { it.startsWith("import ") }
                }
            val header = if (providers.gradleProperty("webProof").isPresent)
                "@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)\n" else ""
            val content = header + extensions.flatMap { it.first }.distinct().joinToString("\n") + "\n" + base +
                extensions.joinToString("\n") { "\nrun {\n" + it.second.joinToString("\n") + "\n}\n" }
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
    }
    androidHelpers.forEach { (_, path) ->
        include(path)
        project(path).projectDir = file("build/parikt/android-helper-projects/${path.substringBeforeLast(':').removePrefix(":")}").apply {
            mkdirs()
            val content = File(checkout, "pariktAndroidNative/build.gradle.kts").readText()
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
        androidHelpers.entries.find { it.value == path }?.let {
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
