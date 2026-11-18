plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    val nativeTargets = if (System.getProperty("os.name").startsWith("Mac")) {
        @Suppress("DEPRECATION")
        val apple = listOf(macosX64(), macosArm64())
        apple
    } else listOf(linuxX64(), mingwX64())
    nativeTargets.forEach { it.binaries.executable { entryPoint = "com.elianfabian.parikt.nativecli.main" } }
	sourceSets {
		commonMain.dependencies {
			implementation(project(":parikt"))
			implementation(libs.kotlinx.coroutines.core)
		}
	}
}

if (System.getProperty("os.name").startsWith("Mac")) tasks.register<Exec>("runMacos") {
    group = "application"
    description = "Runs the native macOS terminal on the host architecture."
    val target = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "macosArm64" else "macosX64"
    dependsOn("linkDebugExecutable${target.replaceFirstChar(Char::uppercaseChar)}")
    commandLine(layout.buildDirectory.file("bin/$target/debugExecutable/nativeTerminalApp.kexe").get().asFile.absolutePath)
    standardInput = System.`in`
}

// Include KGP's generated IDE run tasks as well as our custom Windows/WSL launchers.
// Exec otherwise supplies empty stdin, causing readlnOrNull() to report EOF immediately.
tasks.withType<Exec>().matching { it.name.startsWith("run") }.configureEach {
	standardInput = System.`in`
}

tasks.register<Exec>("runMingwX64") {
	group = "application"
	description = "Runs the native Windows terminal."
	dependsOn("linkDebugExecutableMingwX64")
	val executable = layout.buildDirectory.file("bin/mingwX64/debugExecutable/nativeTerminalApp.exe").get().asFile
	doFirst {
		check(System.getProperty("os.name").startsWith("Windows")) { "Run this executable on Windows x86_64." }
		commandLine(executable.absolutePath)
	}
}

tasks.register<Exec>("runLinuxX64") {
	group = "application"
	description = "Runs the native Linux terminal, using WSL on Windows."
	dependsOn("linkDebugExecutableLinuxX64")
	val executable = layout.buildDirectory.file("bin/linuxX64/debugExecutable/nativeTerminalApp.kexe").get().asFile
	commandLine(if (System.getProperty("os.name").startsWith("Windows")) {
			listOf("wsl", "/mnt/${executable.absolutePath.substring(0, 1).lowercase()}${executable.absolutePath.substring(2).replace('\\', '/')}")
		} else listOf(executable.absolutePath))
}
