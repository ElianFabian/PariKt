import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Combined with the ordinary generated engine script so KGP's APIs share its plugin classpath.
val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
val nativeCache = gradle.extensions.extraProperties["parikt.nativeDirectory"] as File
val windows = System.getProperty("os.name").startsWith("Windows")
fun File.runnerPath() = if (windows) "/mnt/${absolutePath.substring(0, 1).lowercase()}" + absolutePath.substring(2).replace('\\', '/') else absolutePath
val konanData = providers.gradleProperty("konan.data.dir")
    .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
    .map { rootProject.file(it) }.orElse(File(System.getProperty("user.home"), ".konan"))
apply(from = File(checkout, "gradle/parikt.konan-arguments.gradle.kts"))
@Suppress("UNCHECKED_CAST")
val konanArguments = extra["parikt.konanArguments"] as (File, File, String, Boolean) -> org.gradle.process.CommandLineArgumentProvider
configure<KotlinMultiplatformExtension> {
    listOf(linuxX64(), mingwX64()).forEach { target ->
        target.compilerOptions.moduleName.set("parikt")
        val host = if (target.name == "linuxX64") "linux-x86_64" else "windows-x86_64"
        val output = File(nativeCache, "native-$version/$host")
        val nativeBuild = tasks.register<Exec>("build${target.name.replaceFirstChar { it.uppercase() }}NativeDependencies") {
            group = "native"
            dependsOn("downloadKotlinNativeDistribution")
            doNotTrackState("The native builder validates fingerprints and owns its work-cache lock.")
            inputs.file(File(checkout, "parikt/native/build-kotlin-native.sh"))
            inputs.file(File(checkout, "parikt/native/build-lock.sh"))
            inputs.file(File(checkout, "parikt/native/pari-source.sh"))
            inputs.dir(File(checkout, "parikt/native/kotlin-native"))
            inputs.file(File(checkout, "parikt/native/paridroid/paridroid.c"))
            inputs.file(File(checkout, "parikt/native/paridroid/paridroid.h"))
            inputs.property("pariVersion", version)
            inputs.property("kotlinVersion", pariktLibs.versions.kotlin)
            inputs.property("konanDataDirectory", konanData.map { it.absolutePath })
            inputs.property("nativeHomeOverride", providers.gradleProperty("kotlin.native.home").orElse("managed"))
            outputs.dir(output)
            run {
                commandLine((if (windows) listOf("wsl", "bash") else listOf("bash")) + listOf(
                    File(checkout, "parikt/native/build-kotlin-native.sh").runnerPath(),
                    "--pari-version", version, "--target", host, "--out", output.runnerPath()
                ) + if (providers.gradleProperty("kotlinNativeForce").isPresent) listOf("--force") else emptyList())
            }
            argumentProviders.add(konanArguments(layout.buildDirectory.file("konan.txt").get().asFile, konanData.get(), host, windows))
        }
        target.compilations.getByName("main").cinterops.create("pari") {
            definitionFile.set(File(checkout, "parikt/src/nativeInterop/cinterop/pari.def"))
            includeDirs(File(checkout, "parikt/native/kotlin-native"))
            extraOpts("-libraryPath", File(output, "lib").absolutePath)
        }
        tasks.named("cinteropPari${target.name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(nativeBuild)
            inputs.dir(output)
        }
    }
    applyDefaultHierarchyTemplate()
    sourceSets.named("nativeMain") { kotlin.srcDir(File(checkout, "parikt/src/nativeMain/kotlin")) }
}
