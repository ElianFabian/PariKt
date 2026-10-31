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
val jvmHost = when {
    System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") && isWindows -> "windows-x86_64"
    System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") && System.getProperty("os.name").startsWith("Linux") -> "linux-x86_64"
    else -> "unsupported-${System.getProperty("os.name")}-${System.getProperty("os.arch")}".lowercase().replace(' ', '-')
}
val jvmNativeScript = layout.projectDirectory.file("native/build-jvm.sh").asFile
val jvmNativeDirectory = providers.gradleProperty("jvmNativeDir")
    .map { rootProject.file(it) }
    .orElse(layout.projectDirectory.dir(".native/jvm-$pariVersion/$jvmHost").asFile)
val jvmNativeRunner = providers.gradleProperty("jvmNativeRunner")
    .getOrElse(if (isWindows) "wsl" else "local")
val jvmNativePath = providers.gradleProperty("jvmNativePath").orNull

fun File.toWslPath(): String {
    val absolute = absolutePath
    val drive = absolute.substring(0, 1).lowercase()
    return "/mnt/$drive" + absolute.substring(2).replace('\\', '/')
}

val buildJvmNativeDependencies by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds GMP/PARI and the JNI bridge for the $jvmHost JVM host."
    inputs.file(jvmNativeScript)
    inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.c"))
    inputs.file(layout.projectDirectory.file("native/paridroid/jni_bridge.c"))
    inputs.property("pariVersion", pariVersion)
    inputs.property("host", jvmHost)
    inputs.property("runner", jvmNativeRunner)
    outputs.dir(jvmNativeDirectory)
    if (providers.gradleProperty("jvmNativeForce").isPresent) outputs.upToDateWhen { false }

    doFirst {
        if (!jvmHost.startsWith("windows-") && !jvmHost.startsWith("linux-")) {
            throw GradleException("Unsupported JVM native host '$jvmHost'. PariKt currently supports Windows/Linux x86_64.")
        }
        val outputDirectory = jvmNativeDirectory.get().absolutePath
        val target = jvmHost
        val buildArgs = listOf("--target", target, "--pari-version", pariVersion,
            "--out", outputDirectory, "--jni-home", System.getProperty("java.home")) +
            if (providers.gradleProperty("jvmNativeForce").isPresent) listOf("--force") else emptyList()
        val command = when (jvmNativeRunner) {
            "local" -> listOf("bash", jvmNativeScript.absolutePath) + buildArgs
            "wsl" -> listOf("wsl") +
                (jvmNativePath?.let { listOf("env", "PATH=$it:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") } ?: emptyList()) +
                listOf("bash", jvmNativeScript.toWslPath(), "--out", jvmNativeDirectory.get().toWslPath(),
                "--target", target, "--pari-version", pariVersion, "--jni-home", File(System.getProperty("java.home")).toWslPath()) +
                if (providers.gradleProperty("jvmNativeForce").isPresent) listOf("--force") else emptyList()
            else -> throw GradleException("Unknown jvmNativeRunner '$jvmNativeRunner'; use 'local' or 'wsl'.")
        }
        commandLine(command)
    }
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

    jvm("jvm") {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

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
