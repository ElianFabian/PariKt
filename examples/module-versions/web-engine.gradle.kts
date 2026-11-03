import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
val packageId = "@parikt/engine-" + version.lowercase().replace('.', '-')
val windows = System.getProperty("os.name").startsWith("Windows")
fun File.runnerPath() = if (windows) "/mnt/${absolutePath.substring(0, 1).lowercase()}" + absolutePath.substring(2).replace('\\', '/') else absolutePath
val nativeOutput = File(checkout, "parikt/.native/web-$version")
val staged = rootProject.layout.buildDirectory.dir("parikt/web-engines/$version")
val nativeBuild = tasks.register<Exec>("buildWebNativeDependencies") {
    group = "native"
    doNotTrackState("The existing web builder validates fingerprints and holds its work-cache lock.")
    outputs.dir(nativeOutput)
    doFirst {
        commandLine((if (windows) listOf("wsl", "bash") else listOf("bash")) + listOf(
            File(checkout, "parikt/native/build-web.sh").runnerPath(), "--pari-version", version,
            "--out", nativeOutput.runnerPath()
        ) + if (providers.gradleProperty("webNativeForce").isPresent) listOf("--force") else emptyList())
    }
}
val metadata = tasks.register("prepareWebEngineMetadata") {
    val source = File(checkout, "parikt/native/web/package.json")
    inputs.property("packageId", packageId)
    inputs.file(source)
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
val generateBindings = tasks.register("generateWebBindings") {
    val source = File(checkout, "parikt/src/webMain/kotlin/com/elianfabian/parikt")
    inputs.dir(source)
    inputs.property("packageId", packageId)
    outputs.dir(generated)
    doLast {
        val destination = generated.get().asFile.apply { mkdirs() }
        source.listFiles()!!.filter { it.extension == "kt" }.forEach { file ->
            File(destination, file.name).writeText(file.readText().replace("@JsModule(\"@parikt/engine\")", "@JsModule(\"$packageId\")"))
        }
    }
}
configure<KotlinMultiplatformExtension> {
    js {
        useEsModules()
        outputModuleName.set("parikt-bridge-${version.replace('.', '-')}-js")
        compilerOptions.moduleName.set("parikt")
        nodejs { testTask { dependsOn(stageEngine) } }
        browser { testTask { dependsOn(stageEngine) } }
    }
    wasmJs {
        outputModuleName.set("parikt-bridge-${version.replace('.', '-')}-wasm")
        compilerOptions.moduleName.set("parikt")
        nodejs { testTask { dependsOn(stageEngine) } }
        browser { testTask { dependsOn(stageEngine) } }
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
// npm can link metadata-only packages for unused providers. Build payloads only for selected
// run/test/bundle consumers. Ordering alone does not schedule unused native engines.
tasks.matching { it.name.endsWith("PackageJson") }.configureEach { dependsOn(metadata) }
rootProject.tasks.matching { it.name in setOf("kotlinNpmInstall", "kotlinWasmNpmInstall") }
    .configureEach { mustRunAfter(stageEngine) }
