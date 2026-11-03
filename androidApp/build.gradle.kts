plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

val nativeAbis = providers.gradleProperty("nativeAbis")
    .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "x86_64"))
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "PariKt Android supports nativeAbis=arm64-v8a,x86_64 (or a subset)."
}

android {
	namespace = "com.elianfabian.parikt.demo"
	compileSdk = 36

	defaultConfig {
		applicationId = "com.elianfabian.parikt.demo"
		minSdk = 21
		targetSdk = 35
		versionCode = 1
		versionName = "1.0"
        // Do not advertise 32-bit ABIs contributed by transitive graphics libraries
        // when the PARI backend is available only for these two 64-bit ABIs.
        ndk { abiFilters.addAll(nativeAbis) }
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		compose = true
	}
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}

dependencies {
	implementation(project(":consoleUi"))
	implementation(libs.androidx.activity.compose)
}
