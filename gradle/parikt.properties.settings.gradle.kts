import java.util.Properties

// Ordinary applied settings script: share parsing without a plugin or script-local
// model classes crossing Kotlin script classloaders. Missing files return null;
// present malformed files always fail, with their owning project and origin.
val rootFile = layout.rootDirectory.file("parikt.properties").asFile
if (providers.gradleProperty("pariVersion").isPresent) {
    throw GradleException("pariVersion is no longer supported. Set pari.version in $rootFile instead.")
}
val readProperties: (String, File) -> String? = { projectPath, file ->
    val text = providers.fileContents(layout.settingsDirectory.file(file.absolutePath)).asText.orNull
    if (text == null) null else {
        val properties = try {
            Properties().apply { text.reader().use { load(it) } }
        } catch (failure: IllegalArgumentException) {
            throw GradleException("Invalid PariKt properties for $projectPath in $file: ${failure.message}", failure)
        }
        val unknown = properties.stringPropertyNames() - "pari.version"
        if (unknown.isNotEmpty()) {
            throw GradleException("Unknown PariKt settings for $projectPath in $file: ${unknown.sorted().joinToString()}. Supported key: pari.version.")
        }
        val version = properties.getProperty("pari.version")?.trim().orEmpty()
        if (!Regex("[0-9]+\\.[0-9]+\\.[0-9]+([.-][A-Za-z0-9]+)*|master|latest").matches(version)) {
            throw GradleException("Invalid or missing pari.version for $projectPath in $file: '$version'. Use a release such as 2.19.0, master or latest.")
        }
        version
    }
}
val defaultVersion = readProperties(":", rootFile)
val selectProperties: (String, File) -> Triple<String, String, Boolean>? = { projectPath, directory ->
    val localFile = File(directory, "parikt.properties")
    val localVersion = if (directory.canonicalFile == settingsDir.canonicalFile) null else readProperties(projectPath, localFile)
    when {
        localVersion != null -> Triple(localVersion, localFile.absolutePath, true)
        defaultVersion != null -> Triple(defaultVersion, rootFile.absolutePath, false)
        else -> null
    }
}
gradle.extensions.extraProperties["parikt.readProperties"] = readProperties
gradle.extensions.extraProperties["parikt.selectProperties"] = selectProperties
gradle.extensions.extraProperties["parikt.defaultSelection"] = defaultVersion?.let { Triple(it, rootFile.absolutePath, false) }
