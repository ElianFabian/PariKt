pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	repositories {
		google()
		mavenCentral()
	}
}

rootProject.name = "PariKt"

extra["pariktSourceDir"] = settingsDir
apply(from = file("gradle/parikt.settings.gradle.kts"))
include(":androidApp")
include(":terminalApp")
include(":webApp")
include(":consoleUi")
include(":desktopApp")
include(":nativeTerminalApp")
