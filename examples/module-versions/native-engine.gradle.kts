import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import java.util.Properties

// Experimental Native extension of each generated engine. Production is unchanged.
val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
val windows = System.getProperty("os.name").startsWith("Windows")
fun File.runnerPath() = if (windows) "/mnt/${absolutePath.substring(0, 1).lowercase()}" + absolutePath.substring(2).replace('\\', '/') else absolutePath
val konanData = providers.gradleProperty("konan.data.dir")
    .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
    .map { rootProject.file(it) }.orElse(File(System.getProperty("user.home"), ".konan"))
val konanHome = providers.provider { File(layout.buildDirectory.file("konan.txt").get().asFile.readText().trim()) }
fun dependency(key: String) = konanHome.map { home ->
    val properties = Properties().apply { File(home, "konan/konan.properties").inputStream().use { load(it) } }
    File(konanData.get(), "dependencies/${checkNotNull(properties.getProperty(key))}")
}
val linuxToolchain = dependency("toolchainDependency.linux_x64")
val windowsToolchain = dependency("toolchainDependency.mingw_x64")

configure<KotlinMultiplatformExtension> {
    listOf(linuxX64(), mingwX64()).forEach { target ->
        target.compilerOptions.moduleName.set("parikt")
        val host = if (target.name == "linuxX64") "linux-x86_64" else "windows-x86_64"
        // Reuse the checkout's existing compatible builder outputs and fingerprint validation.
        val output = File(checkout, "parikt/.native/native-$version/$host")
        val nativeBuild = tasks.register<Exec>("build${target.name.replaceFirstChar { it.uppercase() }}NativeDependencies") {
            group = "native"
            dependsOn("downloadKotlinNativeDistribution")
            doNotTrackState("The native builder validates fingerprints and holds its work-cache lock.")
            outputs.dir(output)
            doFirst {
                val toolchain = if (target.name == "linuxX64") linuxToolchain.get() else windowsToolchain.get()
                commandLine((if (windows) listOf("wsl", "bash") else listOf("bash")) + listOf(
                    File(checkout, "parikt/native/build-kotlin-native.sh").runnerPath(),
                    "--pari-version", version, "--target", host, "--out", output.runnerPath(),
                    if (target.name == "linuxX64") "--toolchain-root" else "--platform-root", toolchain.runnerPath()
                ) + if (providers.gradleProperty("kotlinNativeForce").isPresent) listOf("--force") else emptyList())
            }
        }
        target.compilations.getByName("main").cinterops.create("pari") {
            definitionFile.set(File(checkout, "parikt/src/nativeInterop/cinterop/pari.def"))
            includeDirs(File(checkout, "parikt/native/kotlin-native"))
            // KGP fixes the interop identity to group + project.name + interop name.
            // Settings gives all versioned providers the same leaf name, parikt.
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
