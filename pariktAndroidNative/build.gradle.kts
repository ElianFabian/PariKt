plugins {
	alias(libs.plugins.android.library)
}

val pariVersion = providers.gradleProperty("pariVersion").getOrElse("2.17.1")
val nativeAbis = providers.gradleProperty("nativeAbis")
	.map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
	.getOrElse(listOf("arm64-v8a", "x86_64"))
val minAndroidApi = 21
val nativeDepsDir = rootProject.layout.projectDirectory.dir("parikt/.native/android-$pariVersion").asFile
val legacyNativeDepsDirs = listOf(
	rootProject.layout.projectDirectory.dir("parikt/.native/android-pari-$pariVersion").asFile,
	rootProject.layout.projectDirectory.dir("parikt/.native/pari-$pariVersion").asFile,
)
val nativeScript = rootProject.layout.projectDirectory.file("parikt/native/build-native.sh").asFile
val forceNativeBuild = providers.gradleProperty("nativeForce").isPresent
val isWindows = System.getProperty("os.name").startsWith("Windows")
val nativeRunner = providers.gradleProperty("nativeRunner").getOrElse(if (isWindows) "wsl" else "local")

fun File.toWslPath(): String {
	val absolute = absolutePath
	val drive = absolute.substring(0, 1).lowercase()
	return "/mnt/$drive" + absolute.substring(2).replace('\\', '/')
}

fun hasValidNativeDependencies(directory: File = nativeDepsDir): Boolean = nativeAbis.isNotEmpty() && nativeAbis.all { abi ->
	File(directory, "$abi/gmp/lib/libgmp.so").isFile &&
		File(directory, "$abi/pari/lib/libpari.so").isFile
}

fun migrateLegacyNativeDependencies() {
	if (nativeDepsDir.exists()) return
	val reusableLegacyDirectory = legacyNativeDepsDirs.firstOrNull(::hasValidNativeDependencies) ?: return

	logger.lifecycle(
		"[PariKt] Reusing cached Android native libraries from ${reusableLegacyDirectory.name}; " +
			"copying them to ${nativeDepsDir.name}."
	)
	reusableLegacyDirectory.copyRecursively(nativeDepsDir, overwrite = false)
}

fun nativeBuildArguments(force: Boolean = false): List<String> =
	listOf(
		"--pari-version", pariVersion, "--api", minAndroidApi.toString(),
		"--abis", nativeAbis.joinToString(" ")
	) + if (force) listOf("--force") else emptyList()

fun nativeBuildCommand(force: Boolean = false): List<String> {
	val commonArgs = nativeBuildArguments(force)
	return when (nativeRunner) {
		"local" -> listOf(
			"bash", nativeScript.path,
			"--ndk", androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath,
			"--out", nativeDepsDir.absolutePath,
		) + commonArgs
		"wsl" -> listOf(
			"wsl", "bash", nativeScript.toWslPath(),
			"--out", nativeDepsDir.toWslPath(),
		) + commonArgs
		else -> throw GradleException("Unknown nativeRunner '$nativeRunner'; use 'local' or 'wsl'.")
	}
}

fun buildNativeDependenciesDuringConfiguration() {
	if (hasValidNativeDependencies()) return

	logger.lifecycle("[PariKt] Native libraries for PARI $pariVersion are missing. Building before Android Studio requests the NDK model...")
	nativeDepsDir.mkdirs()
	val process = ProcessBuilder(nativeBuildCommand())
		.redirectErrorStream(true)
		.start()

	process.outputStream.close()
	process.inputStream.bufferedReader().useLines { lines -> lines.forEach { println(it) } }
	val exitCode = process.waitFor()
	if (exitCode != 0 || !hasValidNativeDependencies()) {
		throw GradleException(
			"Failed to prepare GMP/PARI native libraries for PARI $pariVersion " +
				"(runner=$nativeRunner, exit=$exitCode, output=${nativeDepsDir.absolutePath})."
		)
	}
	logger.lifecycle("[PariKt] Native dependencies are ready at ${nativeDepsDir.absolutePath}.")
}

val buildNativeDependencies = tasks.register<Exec>("buildNativeDependencies") {
	group = "native"
	description = "Builds GMP and PARI/GP $pariVersion for Android."
	inputs.file(nativeScript)
	inputs.property("pariVersion", pariVersion)
	inputs.property("abis", nativeAbis)
	inputs.property("api", minAndroidApi)
	inputs.property("nativeRunner", nativeRunner)
	outputs.dir(nativeDepsDir)
	if (forceNativeBuild) outputs.upToDateWhen { false }

	doFirst {
		nativeDepsDir.mkdirs()
		commandLine(nativeBuildCommand(forceNativeBuild))
	}
}

android {
	namespace = "com.elianfabian.parikt.nativebridge"
	compileSdk = 36
	ndkVersion = "28.2.13676358"

	// This module packages only the JNI/native libraries; Kotlin compilation is unnecessary.
	enableKotlin = false

	defaultConfig {
		minSdk = minAndroidApi
		ndk { abiFilters.addAll(nativeAbis) }
		externalNativeBuild {
			ndkBuild.arguments("NATIVE_DEPS_DIR=${nativeDepsDir.absolutePath.replace('\\', '/')}")
		}
	}

	externalNativeBuild {
		ndkBuild { path = file("../parikt/src/androidMain/jni/Android.mk") }
	}
}

// Configure ndkVersion before resolving sdkComponents.ndkDirectory on fresh checkouts.
// Android Studio requests the native model during sync, before Gradle tasks run.
migrateLegacyNativeDependencies()
buildNativeDependenciesDuringConfiguration()

tasks.configureEach {
	if (name.startsWith("configureNdkBuild") ||
		name.startsWith("buildNdkBuild") ||
		name.startsWith("generateJsonModel") ||
		name.startsWith("externalNativeBuild")
	) {
		dependsOn(buildNativeDependencies)
	}
}
