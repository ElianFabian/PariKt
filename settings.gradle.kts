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
		ivy("https://nodejs.org/dist") {
			name = "Node.js distributions"
			patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
			metadataSources { artifact() }
			content { includeModule("org.nodejs", "node") }
		}
		ivy("https://github.com/WebAssembly/binaryen/releases/download") {
			name = "Binaryen distributions"
			patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
			metadataSources { artifact() }
			content { includeModule("com.github.webassembly", "binaryen") }
		}
	}
}

rootProject.name = "PariKt"

include(":parikt")
include(":pariktAndroidNative")
include(":androidApp")
include(":terminalApp")
include(":webApp")
include(":consoleUi")
include(":desktopApp")
