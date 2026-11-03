@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import java.util.Properties
import org.gradle.api.file.Directory

plugins {
	id("org.jetbrains.kotlin.multiplatform")
	id("com.android.kotlin.multiplatform.library")
}

// Apply tool configuration in the consuming build, using KGP already on this module's classpath.
rootProject.run {
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
}

val pariVersion = gradle.extensions.extraProperties["parikt.version"] as String
val nativeCacheDirectory = gradle.extensions.extraProperties["parikt.nativeDirectory"] as File
val sourceDirectory = gradle.extensions.extraProperties["parikt.sourceDirectory"] as File
if (rootProject.projectDir.canonicalFile != sourceDirectory) {
	layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("parikt/modules/parikt"))
}
val isWindows = System.getProperty("os.name").startsWith("Windows")
val jvmHost = when {
	System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") && isWindows -> "windows-x86_64"
	System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64") && System.getProperty("os.name").startsWith("Linux") -> "linux-x86_64"
	else -> "unsupported-${System.getProperty("os.name")}-${System.getProperty("os.arch")}".lowercase().replace(' ', '-')
}
val jvmNativeScript = layout.projectDirectory.file("native/build-jvm.sh").asFile
val jvmNativeDirectory = providers.gradleProperty("jvmNativeDir")
	.map { rootProject.file(it) }
	.orElse(File(nativeCacheDirectory, "jvm-$pariVersion/$jvmHost"))
val jvmNativeRunner = providers.gradleProperty("jvmNativeRunner")
	.getOrElse(if (isWindows) "wsl" else "local")
val jvmNativePath = providers.gradleProperty("jvmNativePath").orNull

fun File.toWslPath(): String {
	val absolute = absolutePath
	val drive = absolute.substring(0, 1).lowercase()
	return "/mnt/$drive" + absolute.substring(2).replace('\\', '/')
}

val buildJvmNativeDependencies = tasks.register<Exec>("buildJvmNativeDependencies") {
	group = "native"
	// Versioned caches are managed by the builder's source/toolchain stamps, not one
	// Gradle execution history that switches between different engine directories.
	doNotTrackState("The native builder validates its own versioned cache.")
	description = "Builds GMP/PARI and the JNI bridge for the $jvmHost JVM host."
	inputs.file(jvmNativeScript)
	inputs.file(layout.projectDirectory.file("native/build-lock.sh"))
	inputs.file(layout.projectDirectory.file("native/pari-source.sh"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.c"))
	inputs.file(layout.projectDirectory.file("native/paridroid/jni_bridge.c"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.h"))
	inputs.property("pariVersion", pariVersion)
	inputs.property("host", jvmHost)
	inputs.property("runner", jvmNativeRunner)
	inputs.property("nativePath", jvmNativePath ?: "default")
	inputs.dir(File(System.getProperty("java.home"), "include"))
	outputs.dir(jvmNativeDirectory)

	doFirst {
		if (!jvmHost.startsWith("windows-") && !jvmHost.startsWith("linux-")) {
			throw GradleException("Unsupported JVM native host '$jvmHost'. PariKt currently supports Windows/Linux x86_64.")
		}
		val outputDirectory = jvmNativeDirectory.get().absolutePath
		val target = jvmHost
		val buildArgs = listOf(
			"--target", target, "--pari-version", pariVersion,
			"--out", outputDirectory, "--jni-home", System.getProperty("java.home")
		) +
			if (providers.gradleProperty("jvmNativeForce").isPresent) listOf("--force") else emptyList()
		val command = when (jvmNativeRunner) {
			"local" -> listOf("bash", jvmNativeScript.absolutePath) + buildArgs
			"wsl" -> listOf("wsl") +
				(jvmNativePath?.let { listOf("env", "PATH=$it:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") } ?: emptyList()) +
				listOf(
					"bash", jvmNativeScript.toWslPath(), "--out", jvmNativeDirectory.get().toWslPath(),
					"--target", target, "--pari-version", pariVersion, "--jni-home", File(System.getProperty("java.home")).toWslPath()
				) +
				if (providers.gradleProperty("jvmNativeForce").isPresent) listOf("--force") else emptyList()
			else -> throw GradleException("Unknown jvmNativeRunner '$jvmNativeRunner'; use 'local' or 'wsl'.")
		}
		commandLine(command)
	}
}

val webNativeDirectory = layout.dir(providers.provider { File(nativeCacheDirectory, "web-$pariVersion") }).get()
// npm resolves local dependencies relative to the consumer's KGP workspace. Keep that
// relative path stable across engine versions, even when the source checkout is elsewhere.
val webEnginePackageDirectory = rootProject.layout.buildDirectory.dir("parikt/web-engine")
val webNativeRunner = providers.gradleProperty("webNativeRunner")
	.getOrElse(if (isWindows) "wsl" else "local")
val webEmsdkDirectory = providers.gradleProperty("webEmsdkDir").orNull
val webNativeScript = layout.projectDirectory.file("native/build-web.sh")

fun webBuildCommand(smoke: Boolean): List<String> {
	fun runnerPath(file: File): String = if (webNativeRunner == "wsl") file.toWslPath() else file.absolutePath
	val prefix = when (webNativeRunner) {
		"local" -> listOf("bash")
		"wsl" -> listOf("wsl", "bash")
		else -> throw GradleException("Unknown webNativeRunner '$webNativeRunner'; use 'local' or 'wsl'.")
	}
	return prefix + listOf(
		runnerPath(webNativeScript.asFile), "--pari-version", pariVersion,
		"--out", runnerPath(webNativeDirectory.asFile),
	) + (webEmsdkDirectory?.let {
		// A WSL SDK path may already be a Linux path; translate only Windows drive paths.
		listOf("--emsdk", if (webNativeRunner == "wsl" && Regex("^[A-Za-z]:").containsMatchIn(it)) File(it).toWslPath() else it)
	} ?: emptyList()) +
		(if (!smoke && providers.gradleProperty("webNativeForce").isPresent) listOf("--force") else emptyList()) +
		(if (smoke) listOf("--smoke") else emptyList())
}

val buildWebNativeDependencies = tasks.register<Exec>("buildWebNativeDependencies") {
	group = "native"
	doNotTrackState("The native builder validates its own versioned cache.")
	description = "Builds GMP/PARI $pariVersion and the web bridge with Emscripten."
	inputs.file(webNativeScript)
	inputs.file(layout.projectDirectory.file("native/build-lock.sh"))
	inputs.file(layout.projectDirectory.file("native/pari-source.sh"))
	inputs.dir(layout.projectDirectory.dir("native/web"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.c"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.h"))
	inputs.property("pariVersion", pariVersion)
	inputs.property("runner", webNativeRunner)
	inputs.property("emsdk", webEmsdkDirectory ?: "bootstrap")
	outputs.dir(webNativeDirectory)
	doFirst { commandLine(webBuildCommand(smoke = false)) }
}

tasks.register<Exec>("verifyWebNative") {
	group = "verification"
	description = "Exercises the generated PARI web engine directly in Node.js."
	dependsOn(buildWebNativeDependencies)
	doFirst { commandLine(webBuildCommand(smoke = true)) }
}

val prepareWebEnginePackage = tasks.register<Sync>("prepareWebEnginePackage") {
	group = "native"
	description = "Stages the selected PARI engine at a stable local npm package path."
	dependsOn(buildWebNativeDependencies)
	from(webNativeDirectory) { include("*.mjs", "*.wasm", "package.json") }
	into(webEnginePackageDirectory)
}

// Engine changes must invalidate final consumer bundles even when Kotlin sources and
// the stable local npm dependency URL have not changed.
rootProject.allprojects {
	tasks.matching { it.name.endsWith("Webpack") }.configureEach {
		dependsOn(prepareWebEnginePackage)
		inputs.dir(webEnginePackageDirectory)
	}
}

val kotlinNativeDirectory = layout.dir(providers.provider { File(nativeCacheDirectory, "native-$pariVersion/linux-x86_64") }).get()
val mingwNativeDirectory = layout.dir(providers.provider { File(nativeCacheDirectory, "native-$pariVersion/windows-x86_64") }).get()
val konanDataDirectory = providers.gradleProperty("konan.data.dir")
	.orElse(providers.environmentVariable("KONAN_DATA_DIR"))
	.map { rootProject.file(it) }.orElse(File(System.getProperty("user.home"), ".konan"))
val konanHome = providers.provider {
	// Use KGP's selected distribution, including its precedence rules for overrides.
	File(layout.buildDirectory.file("konan.txt").get().asFile.readText().trim())
}
fun konanDependency(key: String) = konanHome.map { home ->
	val configuration = Properties().apply {
		File(home, "konan/konan.properties").inputStream().use { load(it) }
	}
	val dependency = checkNotNull(configuration.getProperty(key)) {
		"Kotlin/Native distribution at $home does not define $key."
	}
	File(konanDataDirectory.get(), "dependencies/$dependency")
}
val linuxCtoolchain = konanDependency("toolchainDependency.linux_x64")
val mingwPlatform = konanDependency("toolchainDependency.mingw_x64")
fun registerKotlinNativeBuild(taskName: String, target: String, directory: Directory, preparation: String) = tasks.register<Exec>(taskName) {
	group = "native"
	doNotTrackState("The native builder validates its own versioned cache.")
	description = "Builds static GMP/PARI and the C bridge for Kotlin/Native $target."
	dependsOn(preparation)
	inputs.file(layout.projectDirectory.file("native/build-kotlin-native.sh"))
	inputs.file(layout.projectDirectory.file("native/build-lock.sh"))
	inputs.file(layout.projectDirectory.file("native/pari-source.sh"))
	inputs.dir(layout.projectDirectory.dir("native/kotlin-native"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.c"))
	inputs.file(layout.projectDirectory.file("native/paridroid/paridroid.h"))
	inputs.property("pariVersion", pariVersion)
	inputs.property("kotlinVersion", pariktLibs.versions.kotlin)
	// Resolve konan.txt only during execution, after the distribution download task.
	inputs.property("konanDataDirectory", konanDataDirectory.map { it.absolutePath })
	inputs.property("nativeHomeOverride", providers.gradleProperty("kotlin.native.home").orElse("managed"))
	outputs.dir(directory)
	doFirst {
		fun nativePath(file: File) = if (isWindows) file.toWslPath() else file.absolutePath
		commandLine((if (isWindows) listOf("wsl", "bash") else listOf("bash")) + listOf(
			nativePath(layout.projectDirectory.file("native/build-kotlin-native.sh").asFile),
			"--pari-version", pariVersion, "--target", target, "--out", nativePath(directory.asFile),
		) + (if (target == "linux-x86_64") listOf("--toolchain-root", nativePath(linuxCtoolchain.get()))
			else listOf("--platform-root", nativePath(mingwPlatform.get()))) +
			if (providers.gradleProperty("kotlinNativeForce").isPresent) listOf("--force") else emptyList())
	}
}
val buildLinuxX64NativeDependencies = registerKotlinNativeBuild(
	"buildLinuxX64NativeDependencies", "linux-x86_64", kotlinNativeDirectory, "prepareLinuxX64CToolchain")
val buildMingwX64NativeDependencies = registerKotlinNativeBuild(
	"buildMingwX64NativeDependencies", "windows-x86_64", mingwNativeDirectory, "downloadKotlinNativeDistribution")

// KGP's distribution task prepares its managed target dependencies as well as the compiler.
tasks.register("prepareLinuxX64CToolchain") {
	group = "native"
	description = "Prepares Kotlin/Native's managed Linux C compiler and sysroot."
	dependsOn("downloadKotlinNativeDistribution")
	doLast {
		val toolchain = linuxCtoolchain.get()
		check(File(toolchain, "bin/x86_64-unknown-linux-gnu-gcc").isFile &&
			File(toolchain, "x86_64-unknown-linux-gnu/sysroot/usr/include/stdio.h").isFile) {
			"Kotlin/Native's Linux C compiler/sysroot is incomplete at $toolchain."
		}
	}
}

kotlin {
	explicitApi()
	compilerOptions {
		// PariNative intentionally uses an expect/actual object in platform source sets.
		freeCompilerArgs.add("-Xexpect-actual-classes")
	}

	android {
		namespace = "com.elianfabian.parikt"
		compileSdk = 36
		minSdk = 21
		localDependencySelection {
			selectBuildTypeFrom.set(listOf("debug", "release"))
		}
		compilerOptions {
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
		}
	}

	jvm("jvm") {
		compilerOptions {
			moduleName.set("parikt")
			jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
		}
	}

	listOf(linuxX64(), mingwX64()).forEach { target ->
		val directory = if (target.name == "linuxX64") kotlinNativeDirectory else mingwNativeDirectory
		target.compilations.getByName("main").cinterops.create("pari") {
			definitionFile.set(layout.projectDirectory.file("src/nativeInterop/cinterop/pari.def"))
			includeDirs(layout.projectDirectory.dir("native/kotlin-native"))
			extraOpts("-libraryPath", directory.dir("lib").asFile.absolutePath)
		}
	}

	js {
		useEsModules()
		browser { testTask { useKarma { useChromeHeadless() } } }
		nodejs { testTask { useMocha { timeout = "120s" } } }
	}

	wasmJs {
		browser { testTask { useKarma { useChromeHeadless() } } }
		nodejs()
	}

	applyDefaultHierarchyTemplate {
		common {
			group("web") {
				withJs()
				withWasmJs()
			}
		}
	}

	sourceSets {
		named("commonTest") {
			dependencies {
				implementation(kotlin("test-common"))
				implementation(kotlin("test-annotations-common"))
			}
		}
		named("androidMain") {
			dependencies {
				implementation(pariktLibs.androidx.startup.runtime)
				implementation(project(":pariktAndroidNative"))
			}
		}
		named("jvmMain") {
			dependencies {
				implementation(pariktLibs.kotlinx.coroutines.core)
			}
		}
		named("jvmTest") {
			dependencies {
				implementation(kotlin("test-junit"))
				implementation(pariktLibs.junit.jupiter)
			}
		}
		named("nativeTest") {
			dependencies { implementation(pariktLibs.kotlinx.coroutines.core) }
		}
		named("webMain") {
			dependencies {
				implementation(pariktLibs.kotlinx.coroutines.core)
				// Relative to KGP's build/{js,wasm}/packages/<module> workspace directories.
				// Keep checkout-specific absolute paths out of the checked-in npm lockfiles.
				implementation(npm("@parikt/engine", "file:../../../parikt/web-engine"))
			}
		}
		named("webTest") {
			dependencies {
				implementation(kotlin("test"))
				implementation(pariktLibs.kotlinx.coroutines.test)
			}
		}
	}
}

// Reject competing JVM implementations while preserving Gradle's project capability.
configurations.matching { it.name in setOf("jvmApiElements", "jvmRuntimeElements", "jvmSourcesElements") }.configureEach {
	outgoing.capability("${project.group.toString().ifBlank { rootProject.name }}:${project.name}:${project.version}")
	outgoing.capability("com.elianfabian.parikt:engine:$pariVersion")
}

tasks.named("cinteropPariLinuxX64") {
	dependsOn(buildLinuxX64NativeDependencies)
	inputs.dir(kotlinNativeDirectory)
}
tasks.named("cinteropPariMingwX64") {
	dependsOn(buildMingwX64NativeDependencies)
	inputs.dir(mingwNativeDirectory)
}

// Gradle's standard native test runner requires a Linux host; WSL can run the executable too.
tasks.register<Exec>("verifyLinuxX64") {
	group = "verification"
	description = "Runs Kotlin/Native linuxX64 tests locally or through WSL on Windows."
	dependsOn("linkDebugTestLinuxX64")
	doFirst {
		val executable = layout.buildDirectory.file("bin/linuxX64/debugTest/test.kexe").get().asFile
		commandLine(if (isWindows) listOf("wsl", executable.toWslPath()) else listOf(executable.absolutePath))
	}
}

if (isWindows) tasks.named("check") { dependsOn("verifyLinuxX64") }

tasks.register("verifyMingwX64") {
	group = "verification"
	description = "Runs Kotlin/Native Windows tests on a Windows host."
	dependsOn("mingwX64Test")
	doFirst { check(isWindows) { "Windows native tests require a Windows x86_64 host." } }
}

// The local npm package must exist before KGP resolves or installs web dependencies.
tasks.matching { it.name.endsWith("PackageJson") }.configureEach {
	dependsOn(prepareWebEnginePackage)
}

tasks.matching { it.name in setOf("jsNodeTest", "wasmJsNodeTest", "jsBrowserTest", "wasmJsBrowserTest") }.configureEach {
	dependsOn(prepareWebEnginePackage)
	inputs.dir(webEnginePackageDirectory)
}
