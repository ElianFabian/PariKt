// The caller supplies the pinned checkout location; all engine configuration belongs to
// the calling build. No plugin or modification of the PariKt checkout is required.
val sourceDirectory = (extra.properties["pariktSourceDir"] as? File)?.canonicalFile
	?: throw GradleException("Set extra[\"pariktSourceDir\"] to the PariKt checkout before applying parikt.settings.gradle.kts.")
apply(from = File(sourceDirectory, "gradle/parikt.properties.settings.gradle.kts"))
val configurationFile = layout.rootDirectory.file("parikt.properties")
@Suppress("UNCHECKED_CAST")
val defaultSelection = gradle.extensions.extraProperties["parikt.defaultSelection"] as Triple<String, String, Boolean>?
val selectedVersion = defaultSelection?.first
	?: throw GradleException("Missing ${configurationFile.asFile}. Create it with pari.version=<PARI release>, for example pari.version=2.19.0.")
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
