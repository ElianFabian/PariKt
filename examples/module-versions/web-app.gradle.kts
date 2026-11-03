@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("org.jetbrains.kotlin.multiplatform") }
@Suppress("UNCHECKED_CAST")
val selections = extra["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
val selectedVersion = selections.getValue(path).first
val enginePath = engines.getValue(selectedVersion)
val consumerPath = path
val packageId = "@parikt/engine-" + selectedVersion.lowercase().replace('.', '-')
val settingsSource = layout.buildDirectory.dir("generated/web-settings")
val generateSettings = tasks.register("generateWebSettings") {
    inputs.property("version", selectedVersion)
    outputs.dir(settingsSource)
    doLast {
        settingsSource.get().file("Settings.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package proof\ninternal const val expectedWebVersion = \"$selectedVersion\"\n")
        }
    }
}
kotlin {
    js {
        useEsModules()
        nodejs()
        browser { commonWebpackConfig {
            outputFileName = "app.js"
            configDirectory = rootProject.file("web-webpack.config.d")
        } }
        binaries.executable()
        compilations.configureEach {
            packageJson {
                dependencies.keys.removeAll { it.startsWith("@parikt/engine-") && it != packageId }
                dependencies[packageId] = "file:../../../parikt/web-engines/$selectedVersion"
            }
        }
    }
    wasmJs {
        nodejs()
        browser { commonWebpackConfig {
            outputFileName = "app.js"
            configDirectory = rootProject.file("web-webpack.config.d")
        } }
        binaries.executable()
        compilations.configureEach {
            packageJson {
                dependencies.keys.removeAll { it.startsWith("@parikt/engine-") && it != packageId }
                dependencies[packageId] = "file:../../../parikt/web-engines/$selectedVersion"
            }
        }
    }
    sourceSets.commonMain {
        kotlin.srcDir(rootProject.file("webAppA/src/commonMain/kotlin"))
        kotlin.srcDir(files(settingsSource).builtBy(generateSettings))
        resources.setSrcDirs(listOf(rootProject.file("webAppA/src/commonMain/resources")))
        dependencies {
            implementation(project(":webSharedLogic"))
            implementation(project(":parikt"))
            implementation(pariktLibs.kotlinx.coroutines.core)
        }
    }
}
// Each bundle tracks only its selected engine assets; there is no all-engines webpack hook.
val staged = rootProject.layout.buildDirectory.dir("parikt/web-engines/$selectedVersion")
tasks.matching { it.name.endsWith("Webpack") }.configureEach {
    dependsOn("$enginePath:prepareWebEnginePackage")
    inputs.dir(staged)
}
tasks.matching { (it.name.contains("Node") && (it.name.endsWith("Run") || it.name.endsWith("Test"))) ||
    it.name.endsWith("BrowserTest") }.configureEach {
    dependsOn("$enginePath:prepareWebEnginePackage")
}
tasks.register("verifyWebGraph") {
    group = "verification"
    doLast {
        listOf("jsCompileClasspath", "wasmJsCompileClasspath").forEach { name ->
            val providers = configurations.getByName(name).incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? ProjectComponentIdentifier)?.projectPath
            }.filter { it in engines.values }
            check(providers == listOf(enginePath)) { "Expected one $enginePath provider, got $providers" }
            logger.lifecycle("PASS $consumerPath:$name web graph PARI=$selectedVersion provider=$enginePath")
        }
    }
}
