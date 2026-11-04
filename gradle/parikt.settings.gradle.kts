// The caller supplies the pinned checkout location; all engine configuration belongs to
// the calling build. No plugin or modification of the PariKt checkout is required.
val sourceDirectory = (extra.properties["pariktSourceDir"] as? File)?.canonicalFile
	?: throw GradleException("Set extra[\"pariktSourceDir\"] to the PariKt checkout before applying parikt.settings.gradle.kts.")
apply(from = File(sourceDirectory, "gradle/parikt.selections.settings.gradle.kts"))
val configurationFile = layout.rootDirectory.file("parikt.properties")
@Suppress("UNCHECKED_CAST")
val defaultSelection = gradle.extensions.extraProperties["parikt.defaultSelection"] as Triple<String, String, Boolean>?
val selectedVersion = defaultSelection?.first
check(File(sourceDirectory, "parikt/native/build-native.sh").isFile) { "Not a PariKt checkout: $sourceDirectory" }

if (selectedVersion != null) gradle.extensions.extraProperties["parikt.version"] = selectedVersion
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
if (selectedVersion != null) {
    project(":parikt").projectDir = File(sourceDirectory, "parikt")
    project(":pariktAndroidNative").projectDir = File(sourceDirectory, "pariktAndroidNative")
} else {
    // No local override becomes an implicit default. These selectors have no engine variants
    // or Android bootstrap; JVM consumers are routed to their explicitly selected providers.
    listOf(":parikt", ":pariktAndroidNative").forEach { path ->
        project(path).projectDir = File(settingsDir, "build/parikt/selectors/${path.removePrefix(":")}").apply {
            mkdirs()
            val content = File(sourceDirectory, "gradle/parikt.selector.gradle.kts").readText()
            val script = File(this, "build.gradle.kts")
            if (!script.exists() || script.readText() != content) script.writeText(content)
        }
    }
}
// Each participating consumer can select a scoped provider for all its targets.
apply(from = File(sourceDirectory, "gradle/parikt.jvm.settings.gradle.kts"))
if (selectedVersion != null) logger.lifecycle("[PariKt] Consumer selected PARI $selectedVersion (${configurationFile.asFile})")
else logger.lifecycle("[PariKt] No root PARI default; participating modules must select pari.version locally.")
