@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
	id("org.jetbrains.kotlin.multiplatform")
	id("org.jetbrains.compose")
	id("org.jetbrains.kotlin.plugin.compose")
}

// Native engine changes must rebuild bundles even when Kotlin sources remain identical.
tasks.matching { it.name.endsWith("Webpack") }.configureEach {
	dependsOn(":parikt:prepareWebEnginePackage")
	inputs.dir(rootProject.layout.projectDirectory.dir("parikt/build/generated/web-engine"))
}

kotlin {
	js {
		useEsModules()
		browser { commonWebpackConfig { outputFileName = "parikt-web-demo.js" } }
		binaries.executable()
	}
	wasmJs {
		browser { commonWebpackConfig { outputFileName = "parikt-web-demo.js" } }
		binaries.executable()
	}
	applyDefaultHierarchyTemplate {
		common { group("web") { withJs(); withWasmJs() } }
	}
	sourceSets {
		commonMain.dependencies {
			implementation(project(":consoleUi"))
			implementation("org.jetbrains.compose.ui:ui:${libs.versions.composeMultiplatform.get()}")
		}
		named("webMain") {
			dependencies {
				implementation(libs.kotlinx.coroutines.core)
				implementation(libs.kotlinx.browser)
				implementation("org.jetbrains.skiko:skiko:0.9.4.2")
			}
		}
	}
}
