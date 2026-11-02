@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
	// Keep AGP and KGP together: parent-loaded KGP classes must also see AGP's types.
	alias(libs.plugins.android.application) apply false
	alias(libs.plugins.android.library) apply false
	alias(libs.plugins.android.kotlin.multiplatform.library) apply false
	alias(libs.plugins.kotlin.multiplatform) apply false
	alias(libs.plugins.kotlin.jvm) apply false
	alias(libs.plugins.compose.compiler) apply false
	alias(libs.plugins.compose.multiplatform) apply false
}

// Node archives are declared in settings, respecting PREFER_SETTINGS repository policy.
allprojects {
	plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin> {
		extensions.configure<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec> {
			downloadBaseUrl.set(null as String?)
		}
	}
	plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
		extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
			downloadBaseUrl.set(null as String?)
		}
	}
	plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
		extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec> {
			downloadBaseUrl.set(null as String?)
		}
	}
}

// npm otherwise installs a second webpack for webpack-cli's peer dependency. Its plugins
// cannot exchange Compilation objects with KGP's webpack instance when the versions differ.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
	val webpackVersion = extensions.getByType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().versions.webpack.version
	extensions.configure<org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension> {
		override("webpack", webpackVersion)
	}
}
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin> {
	val webpackVersion = extensions.getByType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootExtension>().versions.webpack.version
	extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension> {
		override("webpack", webpackVersion)
	}
}
