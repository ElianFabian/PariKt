pluginManagement {
    val toolchain = java.util.Properties().apply { file("toolchain.properties").reader().use { load(it) } }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version toolchain.getProperty("kotlin")
        id("com.android.library") version toolchain.getProperty("agp")
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "PariKtPublications"
include(":library", ":engineTools")
