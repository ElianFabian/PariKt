plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
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
	implementation(project(":parikt"))
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
}
