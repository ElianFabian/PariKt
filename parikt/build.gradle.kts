import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

val pariVersion = providers.gradleProperty("pariVersion").getOrElse("2.17.1")
val nativeAbis = providers.gradleProperty("nativeAbis")
    .map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "x86_64"))
val minAndroidApi = 21
val nativeDepsDir = layout.projectDirectory.dir(".native/pari-$pariVersion").asFile
val nativeScript = layout.projectDirectory.file("native/build-native.sh").asFile
val forceNativeBuild = providers.gradleProperty("nativeForce").isPresent
val isWindows = System.getProperty("os.name").startsWith("Windows")
val nativeRunner = providers.gradleProperty("nativeRunner").getOrElse(if (isWindows) "wsl" else "local")

fun File.toWslPath(): String {
    val absolute = absolutePath
    val drive = absolute.substring(0, 1).lowercase()
    return "/mnt/$drive" + absolute.substring(2).replace('\\', '/')
}

fun hasValidNativeDependencies(): Boolean = nativeAbis.isNotEmpty() && nativeAbis.all { abi ->
    File(nativeDepsDir, "$abi/gmp/lib/libgmp.so").isFile &&
        File(nativeDepsDir, "$abi/pari/lib/libpari.so").isFile
}

fun nativeBuildArguments(force: Boolean = false): List<String> =
    listOf("--pari-version", pariVersion, "--api", minAndroidApi.toString(),
        "--abis", nativeAbis.joinToString(" ")) + if (force) listOf("--force") else emptyList()

fun nativeBuildCommand(force: Boolean = false): List<String> {
    val commonArgs = nativeBuildArguments(force)
    return when (nativeRunner) {
        "local" -> listOf(
            "bash", nativeScript.path,
            "--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath,
            "--out", nativeDepsDir.absolutePath,
        ) + commonArgs
        "wsl" -> listOf(
            "wsl", "bash", nativeScript.toWslPath(),
            "--out", nativeDepsDir.toWslPath(),
        ) + commonArgs
        else -> throw GradleException("Unknown nativeRunner '$nativeRunner'; use 'local' or 'wsl'.")
    }
}

fun buildNativeDependenciesDuringConfiguration() {
    if (hasValidNativeDependencies()) return

    logger.lifecycle("[PariKt] Native libraries for PARI $pariVersion are missing. Building before Android NDK model generation...")
    nativeDepsDir.mkdirs()
    val process = ProcessBuilder(nativeBuildCommand())
        .redirectErrorStream(true)
        .start()

    process.outputStream.close()
    process.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it) } }
    val exitCode = process.waitFor()
    if (exitCode != 0 || !hasValidNativeDependencies()) {
        throw GradleException(
            "Failed to prepare GMP/PARI native libraries for PARI $pariVersion " +
                "(runner=$nativeRunner, exit=$exitCode, output=${nativeDepsDir.absolutePath})."
        )
    }
    logger.lifecycle("[PariKt] Native dependencies are ready at ${nativeDepsDir.absolutePath}.")
}

// Android Studio asks AGP to configure ndk-build while syncing, outside the Gradle
// task graph. Make the prebuilt GMP/PARI libraries available before that model request.
buildNativeDependenciesDuringConfiguration()

val buildNativeDependencies by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds GMP and PARI/GP $pariVersion for Android."
    inputs.file(nativeScript)
    inputs.property("pariVersion", pariVersion)
    inputs.property("abis", nativeAbis)
    inputs.property("api", minAndroidApi)
    inputs.property("nativeRunner", nativeRunner)
    outputs.dir(nativeDepsDir)
    if (forceNativeBuild) outputs.upToDateWhen { false }

    doFirst {
        nativeDepsDir.mkdirs()
        commandLine(nativeBuildCommand(forceNativeBuild))
    }
}

kotlin {
    explicitApi()

    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    jvm("jvm")

    wasmJs {
        browser()
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.startup.runtime)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.junit.jupiter)
            }
        }
        val wasmJsMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

android {
    namespace = "com.elianfabian.parikt"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = minAndroidApi
        ndk { abiFilters.addAll(nativeAbis) }
        externalNativeBuild {
            ndkBuild.arguments("NATIVE_DEPS_DIR=${nativeDepsDir.absolutePath.replace('\\', '/')}")
        }
    }

    externalNativeBuild {
        ndkBuild { path = file("src/androidMain/jni/Android.mk") }
    }
}

tasks.configureEach {
    if (name.startsWith("configureNdkBuild") ||
        name.startsWith("buildNdkBuild") ||
        name.startsWith("generateJsonModel") ||
        name.startsWith("externalNativeBuild")
    ) {
        dependsOn(buildNativeDependencies)
    }
}
