import java.util.Properties

// The caller supplies the pinned checkout location; all engine configuration belongs to
// the calling build. No plugin or modification of the PariKt checkout is required.
val sourceDirectory = (extra.properties["pariktSourceDir"] as? File)?.canonicalFile
	?: throw GradleException("Set extra[\"pariktSourceDir\"] to the PariKt checkout before applying parikt.settings.gradle.kts.")
val configurationFile = layout.rootDirectory.file("parikt.properties")
if (!configurationFile.asFile.isFile) {
	throw GradleException("Missing ${configurationFile.asFile}. Create it with pari.version=<PARI release>, for example pari.version=2.19.0.")
}
if (providers.gradleProperty("pariVersion").isPresent) {
	throw GradleException("pariVersion is no longer supported. Set pari.version in ${configurationFile.asFile} instead.")
}
val configuration = Properties().apply {
	providers.fileContents(configurationFile).asText.get().reader().use { load(it) }
}
val unknownKeys = configuration.stringPropertyNames() - setOf("pari.version")
if (unknownKeys.isNotEmpty()) throw GradleException("Unknown PariKt settings in ${configurationFile.asFile}: ${unknownKeys.sorted().joinToString()}. Supported key: pari.version.")
val selectedVersion = configuration.getProperty("pari.version")?.trim().orEmpty()
if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)*|master|latest").matches(selectedVersion)) {
	throw GradleException("Invalid or missing pari.version in ${configurationFile.asFile}: '$selectedVersion'. Use a release such as 2.19.0, master or latest.")
}
check(File(sourceDirectory, "parikt/native/build-native.sh").isFile) { "Not a PariKt checkout: $sourceDirectory" }

gradle.extensions.extraProperties["parikt.version"] = selectedVersion
// Repository development retains its existing caches; external consumers own their outputs.
gradle.extensions.extraProperties["parikt.nativeDirectory"] = if (settingsDir.canonicalFile == sourceDirectory) {
	File(sourceDirectory, "parikt/.native")
} else File(settingsDir, "build/parikt/native")
gradle.extensions.extraProperties["parikt.sourceDirectory"] = sourceDirectory

dependencyResolutionManagement.versionCatalogs.create("pariktLibs") {
	from(files(File(sourceDirectory, "gradle/libs.versions.toml")))
}
// KGP-managed web tools must also resolve when the consumer prefers settings repositories.
dependencyResolutionManagement.repositories {
	ivy("https://nodejs.org/dist") {
		name = "PariKt Node.js distributions"
		patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
		metadataSources { artifact() }
		content { includeModule("org.nodejs", "node") }
	}
	ivy("https://github.com/WebAssembly/binaryen/releases/download") {
		name = "PariKt Binaryen distributions"
		patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
		metadataSources { artifact() }
		content { includeModule("com.github.webassembly", "binaryen") }
	}
}
include(":parikt", ":pariktAndroidNative")
project(":parikt").projectDir = File(sourceDirectory, "parikt")
project(":pariktAndroidNative").projectDir = File(sourceDirectory, "pariktAndroidNative")
logger.lifecycle("[PariKt] Consumer selected PARI $selectedVersion (${configurationFile.asFile})")
