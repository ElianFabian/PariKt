@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins {
	id("org.jetbrains.kotlin.multiplatform")
	id("com.android.kotlin.multiplatform.library")
	id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
	android {
		namespace = "com.elianfabian.parikt.console"
		compileSdk = 36
		minSdk = 21
		localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
		withHostTest {}
		compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
	}
	jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
	js { useEsModules(); browser() }
	wasmJs { browser() }
	sourceSets {
		commonMain.dependencies {
			implementation(project(":parikt"))
			// Keep the UI libraries on the API-21-compatible release. This module only
			// needs the Kotlin Compose compiler plugin (no generated Compose resources).
			val composeVersion = libs.versions.composeMultiplatform.get()
			implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
			implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
			implementation("org.jetbrains.compose.material3:material3:$composeVersion")
			implementation("org.jetbrains.compose.ui:ui:$composeVersion")
			implementation(libs.kotlinx.coroutines.core)
		}
		commonTest.dependencies {
			implementation(kotlin("test"))
			implementation(libs.kotlinx.coroutines.test)
		}
	}
}
