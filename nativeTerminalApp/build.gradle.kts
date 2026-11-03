plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
	linuxX64 {
		binaries.executable { entryPoint = "com.elianfabian.parikt.nativecli.main" }
	}
	mingwX64 {
		binaries.executable { entryPoint = "com.elianfabian.parikt.nativecli.main" }
	}
	sourceSets {
		commonMain.dependencies {
			implementation(project(":parikt"))
			implementation(libs.kotlinx.coroutines.core)
		}
	}
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
	doFirst {
		check(System.getProperty("os.name").startsWith("Windows")) { "Run this executable on Windows x86_64." }
		commandLine(layout.buildDirectory.file("bin/mingwX64/debugExecutable/nativeTerminalApp.exe").get().asFile.absolutePath)
	}
}

tasks.register<Exec>("runLinuxX64") {
	group = "application"
	description = "Runs the native Linux terminal, using WSL on Windows."
	dependsOn("linkDebugExecutableLinuxX64")
	doFirst {
		val executable = layout.buildDirectory.file("bin/linuxX64/debugExecutable/nativeTerminalApp.kexe").get().asFile
		commandLine(if (System.getProperty("os.name").startsWith("Windows")) {
			listOf("wsl", "/mnt/${executable.absolutePath.substring(0, 1).lowercase()}${executable.absolutePath.substring(2).replace('\\', '/')}")
		} else listOf(executable.absolutePath))
	}
}
