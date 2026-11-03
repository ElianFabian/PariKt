pluginManagement {
	repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
	repositories {
		google()
		mavenCentral()
	}
}
rootProject.name = "PariKtConsumer"

// In your project this can be a pinned Git submodule, e.g. file("vendor/PariKt").
val pariKtCheckout = file("../..")
extra["pariktSourceDir"] = pariKtCheckout
apply(from = File(pariKtCheckout, "gradle/parikt.settings.gradle.kts"))
include(":app")
