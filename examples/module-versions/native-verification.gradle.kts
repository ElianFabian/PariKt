import java.security.MessageDigest
import java.util.Properties

@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
tasks.register("verifyNativeIdentity") {
    group = "verification"
    dependsOn(engines.values.flatMap { listOf("$it:compileKotlinLinuxX64", "$it:compileKotlinMingwX64") })
    doLast {
        listOf("linuxX64", "mingwX64").forEach { target ->
            listOf("klib/parikt", "cinterop/parikt-cinterop-pari").forEach { relative ->
                val identities = engines.values.map { path ->
                    val directory = project(path).layout.buildDirectory.dir("classes/kotlin/$target/main/$relative").get().asFile
                    val manifest = Properties().apply { File(directory, "default/manifest").inputStream().use { load(it) } }
                    val properties = listOf("unique_name", "depends", "native_targets", "interop", "package")
                        .associateWith { manifest.getProperty(it) }
                    val metadata = directory.walkTopDown().filter { it.isFile &&
                        (it.relativeTo(directory).invariantSeparatorsPath.startsWith("default/linkdata/") ||
                            it.relativeTo(directory).invariantSeparatorsPath.startsWith("default/ir/")) }
                        .associate { it.relativeTo(directory).invariantSeparatorsPath to
                            MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
                    check(metadata.isNotEmpty()) { "No klib metadata in $directory" }
                    logger.lifecycle("  $path $target $relative identity=${properties["unique_name"]} depends=${properties["depends"]}")
                    properties to metadata
                }
                check(identities.distinct().size == 1) { "Native identity/metadata differs for $target $relative" }
                logger.lifecycle("PASS Native identity $target $relative: ${identities.size} providers, matching metadata and IR")
            }
        }
    }
}
