import org.gradle.kotlin.dsl.withGroovyBuilder

// A stable Gradle-only script: Kotlin-only roots do not load Android plugins or models.
if (rootProject.buildscript.classLoader.getResource("com/android/build/gradle/LibraryPlugin.class") != null) {
pluginManager.apply("com.android.library")

val scopedVersion = extra.properties["parikt.engineVersion"] as? String
val pariVersion = scopedVersion ?: gradle.extensions.extraProperties["parikt.version"] as String
val nativeCacheDirectory = gradle.extensions.extraProperties["parikt.nativeDirectory"] as File
val sourceDirectory = gradle.extensions.extraProperties["parikt.sourceDirectory"] as File
if (scopedVersion != null) {
    group = "com.elianfabian.parikt.nativebridge.v${pariVersion.replace('.', '_').replace('-', '_')}"
    project.version = pariVersion
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("parikt/android-helpers/$pariVersion"))
} else if (rootProject.projectDir.canonicalFile != sourceDirectory) {
	layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("parikt/modules/pariktAndroidNative"))
}
val ndkVersion = extensions.getByType<VersionCatalogsExtension>().named("pariktLibs").findVersion("ndk").get().requiredVersion
val nativeAbis = providers.gradleProperty("nativeAbis")
	.map { value -> value.split(',').map(String::trim).filter(String::isNotEmpty) }
	.getOrElse(listOf("arm64-v8a", "x86_64"))
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in setOf("arm64-v8a", "x86_64") }) {
    "Only arm64-v8a/x86_64 Android engines are implemented."
}
val minAndroidApi = 21
val nativeDepsDir = File(nativeCacheDirectory, "android-$pariVersion")
val legacyNativeDepsDirs = listOf(
	File(nativeCacheDirectory, "android-pari-$pariVersion"),
	File(nativeCacheDirectory, "pari-$pariVersion"),
)
val nativeScript = File(sourceDirectory, "parikt/native/build-native.sh")
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

fun configuredNdkDirectory(): File {
	val components = extensions.getByName("androidComponents")
	val sdk = components.withGroovyBuilder { getProperty("sdkComponents") }
	@Suppress("UNCHECKED_CAST")
	val directory = sdk.withGroovyBuilder { getProperty("ndkDirectory") } as org.gradle.api.provider.Provider<org.gradle.api.file.Directory>
	return directory.get().asFile
}

fun nativeBuildCommand(force: Boolean = false): List<String> {
	val commonArgs = nativeBuildArguments(force)
	return when (nativeRunner) {
		"local" -> listOf(
			"bash", nativeScript.path,
			"--ndk", configuredNdkDirectory().absolutePath,
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
	val preparation = providers.exec {
		commandLine(nativeBuildCommand())
		isIgnoreExitValue = true
	}
	val output = preparation.standardOutput.asText.get()
	if (output.isNotBlank()) logger.lifecycle(output.trimEnd())
	val errors = preparation.standardError.asText.get()
	if (errors.isNotBlank()) logger.lifecycle(errors.trimEnd())
	val exitCode = preparation.result.get().exitValue
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
	// Sync may already have populated this cache. Always let the builder inspect its
	// stamp, preserving reusable outputs when the consumer switches PARI versions.
	doNotTrackState("The native builder validates a cache also populated during Gradle sync.")
	description = "Builds GMP and PARI/GP $pariVersion for Android."
	inputs.file(nativeScript)
	inputs.file(File(sourceDirectory, "parikt/native/build-lock.sh"))
	inputs.file(File(sourceDirectory, "parikt/native/pari-source.sh"))
	inputs.property("pariVersion", pariVersion)
	inputs.property("abis", nativeAbis)
	inputs.property("api", minAndroidApi)
	inputs.property("nativeRunner", nativeRunner)
	outputs.dir(nativeDepsDir)

	commandLine(nativeBuildCommand(forceNativeBuild))
}

extensions.getByName("android").withGroovyBuilder {
    setProperty("namespace", "com.elianfabian.parikt.nativebridge" +
        if (scopedVersion != null) ".v${pariVersion.replace('.', '_').replace('-', '_')}" else "")
    setProperty("compileSdk", 36)
    setProperty("ndkVersion", ndkVersion)
    if (hasProperty("enableKotlin")) setProperty("enableKotlin", false)
    getProperty("defaultConfig").withGroovyBuilder {
        setProperty("minSdk", minAndroidApi)
        getProperty("ndk").withGroovyBuilder {
            @Suppress("UNCHECKED_CAST")
            (getProperty("abiFilters") as MutableSet<String>).addAll(nativeAbis)
        }
        getProperty("externalNativeBuild").withGroovyBuilder {
            getProperty("ndkBuild").withGroovyBuilder {
                @Suppress("UNCHECKED_CAST")
                (getProperty("arguments") as MutableList<String>).add("NATIVE_DEPS_DIR=${nativeDepsDir.invariantSeparatorsPath}")
            }
        }
    }
    getProperty("externalNativeBuild").withGroovyBuilder {
        getProperty("ndkBuild").withGroovyBuilder {
            setProperty("path", File(sourceDirectory, "parikt/src/androidMain/jni/Android.mk"))
            if (scopedVersion != null) setProperty("buildStagingDirectory", layout.buildDirectory.dir("cxx").get().asFile)
            else if (rootProject.projectDir.canonicalFile != sourceDirectory)
                setProperty("buildStagingDirectory", rootProject.layout.buildDirectory.dir("parikt/cxx").get().asFile)
        }
    }
}
configurations.matching { it.isCanBeConsumed }.configureEach {
    outgoing.capability("${project.group}:${project.name}:${project.version}")
    outgoing.capability("com.elianfabian.parikt:android-native-engine:$pariVersion")
}

// Configure ndkVersion before resolving sdkComponents.ndkDirectory on fresh checkouts.
// Android Studio requests the native model during sync, before Gradle tasks run.
migrateLegacyNativeDependencies()
gradle.projectsEvaluated {
    // CLI builds prepare only versions selected by real Android consumers. During IDE
    // sync any helper's native model can be requested, so prepare every configured helper.
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties.properties["parikt.selections"] as? Map<String, Triple<String, String, Boolean>>
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties.properties["parikt.participants"] as? Set<String>
    val androidConsumers = rootProject.allprojects.filter { candidate ->
        candidate.path in (selections?.keys ?: emptySet()) &&
            (participants == null || candidate.path in participants) &&
            listOf("com.android.application", "com.android.library", "com.android.kotlin.multiplatform.library")
                .any(candidate.plugins::hasPlugin)
    }
    val modelRequested = gradle.startParameter.taskNames.isEmpty() ||
        providers.gradleProperty("android.injected.invoked.from.ide").orNull == "true"
    if (selections == null || modelRequested || androidConsumers.any { selections[it.path]?.first == pariVersion }) {
        buildNativeDependenciesDuringConfiguration()
    }
}

tasks.configureEach {
	if (name.startsWith("configureNdkBuild") ||
		name.startsWith("buildNdkBuild") ||
		name.startsWith("generateJsonModel") ||
		name.startsWith("externalNativeBuild")
	) {
		dependsOn(buildNativeDependencies)
	}
}

}
