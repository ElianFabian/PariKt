plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.library") apply false
}

val metadata = java.util.Properties().apply {
    file("../gradle.metadata.properties").reader(Charsets.UTF_8).use { load(it) }
}
allprojects {
    group = metadata.getProperty("group")
    version = metadata.getProperty("version")
    extra["parikt.repository"] = rootProject.file("..").canonicalFile
    extra["parikt.metadata"] = metadata
}
