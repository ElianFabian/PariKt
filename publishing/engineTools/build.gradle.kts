plugins { base; `maven-publish` }
val repository = extra["parikt.repository"] as File
val tools = tasks.register<Jar>("engineToolsJar") {
    archiveBaseName.set("parikt-engine-tools")
    from(File(repository,"gradle")) {
        include("parikt.properties.settings.gradle.kts", "parikt.selections.settings.gradle.kts", "libs.versions.toml")
        into("gradle")
    }
    from("parikt.binary.settings.gradle.kts") { into("gradle") }
    from(File(repository,"parikt/native")) { into("parikt/native") }
    from(File(repository,"parikt/src/androidMain/jni")) { into("parikt/src/androidMain/jni") }
    from(File(repository,"LICENSE"))
    from(File(repository,"docs/RELEASING.md"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
val sources = tasks.register<Jar>("engineToolsSourcesJar") {
    archiveClassifier.set("sources")
    from(tools.map { zipTree(it.archiveFile) })
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
publishing.publications.create<MavenPublication>("tools") {
    artifactId = "parikt-engine-tools"
    artifact(tools)
    artifact(sources)
}
apply(from = rootProject.file("publication.gradle.kts"))
