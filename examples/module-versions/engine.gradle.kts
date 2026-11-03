plugins { id("org.jetbrains.kotlin.multiplatform") }

val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
group = "com.elianfabian.parikt.proof"
project.version = version
kotlin {
    explicitApi()
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }
    jvm {
        // Gradle project identities differ; Kotlin ABI/module identity must not.
        compilerOptions {
            moduleName.set("parikt")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir(File(checkout, "parikt/src/commonMain/kotlin")) }
        jvmMain {
            kotlin.srcDir(File(checkout, "parikt/src/jvmMain/kotlin"))
            dependencies { implementation(pariktLibs.kotlinx.coroutines.core) }
        }
    }
}
configurations.matching { it.isCanBeConsumed }.configureEach {
    outgoing.capability("${project.group}:${project.name}:${project.version}")
    outgoing.capability("com.elianfabian.parikt:engine:$version")
}

val windows = System.getProperty("os.name").startsWith("Windows")
val host = if (windows) "windows-x86_64" else "linux-x86_64"
val runner = providers.gradleProperty("jvmNativeRunner").getOrElse(if (windows) "wsl" else "local")
val nativePath = providers.gradleProperty("jvmNativePath").orNull
val output = providers.gradleProperty("jvmNativeDir").map { rootProject.file(it) }
    .orElse(rootProject.layout.buildDirectory.dir("parikt/native/jvm-$version/$host").map { it.asFile })
fun File.wslPath() = "/mnt/${absolutePath.substring(0, 1).lowercase()}" + absolutePath.substring(2).replace('\\', '/')
tasks.register<Exec>("buildJvmNativeDependencies") {
    group = "native"
    doNotTrackState("The existing native builder validates version/source/toolchain stamps and owns the work lock.")
    outputs.dir(output)
    doFirst {
        check(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") &&
            (windows || System.getProperty("os.name").startsWith("Linux"))) { "The JVM proof requires Windows/Linux x86_64." }
        fun runnerPath(file: File) = if (runner == "wsl") file.wslPath() else file.absolutePath
        val prefix = when (runner) {
            "wsl" -> listOf("wsl") + (nativePath?.let { listOf("env", "PATH=$it:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") } ?: emptyList()) + "bash"
            "local" -> listOf("bash")
            else -> throw GradleException("Unknown jvmNativeRunner '$runner'; use local or wsl.")
        }
        commandLine(prefix + listOf(runnerPath(File(checkout, "parikt/native/build-jvm.sh")),
            "--pari-version", version, "--target", host, "--out", runnerPath(output.get()),
            "--jni-home", runnerPath(File(System.getProperty("java.home")))) +
            if (providers.gradleProperty("jvmNativeForce").isPresent) listOf("--force") else emptyList())
    }
}
