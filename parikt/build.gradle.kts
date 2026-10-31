@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.gradle.api.GradleException
import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val pariVersion = providers.gradleProperty("pariVersion").getOrElse("2.17.1")
val isWindows = System.getProperty("os.name").startsWith("Windows")
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

val buildJvmNativeDependencies = tasks.register<Exec>("buildJvmNativeDependencies") {
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

kotlin {
    explicitApi()

    android {
        namespace = "com.elianfabian.parikt"
        compileSdk = 36
        minSdk = 21
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("debug", "release"))
        }
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
        named("commonTest") {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        named("androidMain") {
            dependencies {
                implementation(libs.androidx.startup.runtime)
                implementation(project(":pariktAndroidNative"))
            }
        }
        named("jvmMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        named("jvmTest") {
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.junit.jupiter)
            }
        }
        named("wasmJsMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
