plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
	linuxX64 {
		binaries.executable { entryPoint = "com.elianfabian.parikt.nativecli.main" }
	}
	sourceSets {
		commonMain.dependencies {
			implementation(project(":parikt"))
			implementation(libs.kotlinx.coroutines.core)
		}
	}
}

tasks.register<Exec>("runLinuxX64") {
	group = "application"
	description = "Runs the native Linux terminal, using WSL on Windows."
	dependsOn("linkDebugExecutableLinuxX64")
	standardInput = System.`in`
	doFirst {
		val executable = layout.buildDirectory.file("bin/linuxX64/debugExecutable/nativeTerminalApp.kexe").get().asFile
		commandLine(if (System.getProperty("os.name").startsWith("Windows")) {
			listOf("wsl", "/mnt/${executable.absolutePath.substring(0, 1).lowercase()}${executable.absolutePath.substring(2).replace('\\', '/')}")
		} else listOf(executable.absolutePath))
	}
}
