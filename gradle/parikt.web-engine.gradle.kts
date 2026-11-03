import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
val packageId = "@parikt/engine-" + version.lowercase().replace('.', '-')
val nativeOutput = File(gradle.extensions.extraProperties["parikt.nativeDirectory"] as File, "web-$version")
val staged = rootProject.layout.buildDirectory.dir("parikt/web-engines/$version")
val runner = providers.gradleProperty("webNativeRunner").getOrElse(if (System.getProperty("os.name").startsWith("Windows")) "wsl" else "local")
val emsdk = providers.gradleProperty("webEmsdkDir").orNull
fun File.runnerPath() = if (runner == "wsl") "/mnt/${absolutePath.substring(0, 1).lowercase()}" + absolutePath.substring(2).replace('\\', '/') else absolutePath
if (!rootProject.extensions.extraProperties.has("parikt.web.toolsConfigured")) rootProject.run {
    extra["parikt.web.toolsConfigured"] = true
    allprojects {
        plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
            extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> { downloadBaseUrl.set(null as String?) }
        }
        plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
            extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> { downloadBaseUrl.set(null as String?) }
        }
        plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
            extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec> { downloadBaseUrl.set(null as String?) }
        }
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
        val versions = extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().versions
        versions.webpack.version = pariktLibs.versions.webpack.get()
        val webpack = versions.webpack.version
        extra["parikt.webpackVersion"] = webpack
        extensions.configure<org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension> { override("webpack", webpack) }
        tasks.named("rootPackageJson") { inputs.property("parikt.webpackVersion", webpack) }
    }
    plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
        val webpack = extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension>().versions.webpack.version
        extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension> { override("webpack", webpack) }
    }
}
val nativeBuild = tasks.register<Exec>("buildWebNativeDependencies") {
    group = "native"
    doNotTrackState("The web builder validates fingerprints and owns its work-cache lock.")
    inputs.file(File(checkout, "parikt/native/build-web.sh"))
    inputs.file(File(checkout, "parikt/native/build-lock.sh"))
    inputs.file(File(checkout, "parikt/native/pari-source.sh"))
    inputs.dir(File(checkout, "parikt/native/web"))
    inputs.file(File(checkout, "parikt/native/paridroid/paridroid.c"))
    inputs.file(File(checkout, "parikt/native/paridroid/paridroid.h"))
    inputs.property("pariVersion", version)
    inputs.property("runner", runner)
    inputs.property("emsdk", emsdk ?: "bootstrap")
    outputs.dir(nativeOutput)
    doFirst {
        check(runner in setOf("wsl", "local")) { "Unknown webNativeRunner '$runner'; use local or wsl." }
        commandLine((if (runner == "wsl") listOf("wsl", "bash") else listOf("bash")) + listOf(
            File(checkout, "parikt/native/build-web.sh").runnerPath(), "--pari-version", version,
            "--out", nativeOutput.runnerPath()
        ) + (emsdk?.let { listOf("--emsdk", if (runner == "wsl" && Regex("^[A-Za-z]:").containsMatchIn(it)) File(it).runnerPath() else it) } ?: emptyList()) +
            if (providers.gradleProperty("webNativeForce").isPresent) listOf("--force") else emptyList())
    }
}
val metadata = tasks.register("prepareWebEngineMetadata") {
    val source = File(checkout, "parikt/native/web/package.json")
    inputs.file(source)
    inputs.property("packageId", packageId)
    outputs.file(staged.map { it.file("package.json") })
    doLast {
        staged.get().file("package.json").asFile.apply {
            parentFile.mkdirs()
            writeText(source.readText().replace("\"@parikt/engine\"", "\"$packageId\""))
        }
    }
}
val stageEngine = tasks.register<Copy>("prepareWebEnginePackage") {
    dependsOn(nativeBuild, metadata)
    from(nativeOutput) { include("*.mjs", "*.wasm") }
    into(staged)
}
val generated = layout.buildDirectory.dir("generated/web-bindings")
val generateBindings = tasks.register<Sync>("generateWebBindings") {
    inputs.property("packageId", packageId)
    filteringCharset = "UTF-8"
    from(File(checkout, "parikt/src/webMain/kotlin")) {
        include("**/*.kt")
        filter { line: String -> line.replace("@JsModule(\"@parikt/engine\")", "@JsModule(\"$packageId\")") }
    }
    into(generated)
}
configure<KotlinMultiplatformExtension> {
    js {
        useEsModules()
        outputModuleName.set("parikt-bridge-${version.replace('.', '-')}-js")
        compilerOptions.moduleName.set("parikt")
        nodejs { testTask { dependsOn(stageEngine); inputs.dir(staged) } }
        browser { testTask { dependsOn(stageEngine); inputs.dir(staged) } }
    }
    wasmJs {
        outputModuleName.set("parikt-bridge-${version.replace('.', '-')}-wasm")
        compilerOptions.moduleName.set("parikt")
        nodejs { testTask { dependsOn(stageEngine); inputs.dir(staged) } }
        browser { testTask { dependsOn(stageEngine); inputs.dir(staged) } }
    }
    applyDefaultHierarchyTemplate { common { group("web") { withJs(); withWasmJs() } } }
    sourceSets {
        named("webMain") {
            kotlin.srcDir(files(generated).builtBy(generateBindings))
            dependencies {
                implementation(pariktLibs.kotlinx.coroutines.core)
                implementation(npm(packageId, "file:../../../parikt/web-engines/$version"))
            }
        }
        named("jsMain") { kotlin.srcDir(File(checkout, "parikt/src/jsMain/kotlin")) }
        named("wasmJsMain") { kotlin.srcDir(File(checkout, "parikt/src/wasmJsMain/kotlin")) }
    }
}
tasks.matching { it.name.endsWith("PackageJson") }.configureEach { dependsOn(metadata) }
rootProject.tasks.matching { it.name in setOf("kotlinNpmInstall", "kotlinWasmNpmInstall") }.configureEach { mustRunAfter(stageEngine) }
