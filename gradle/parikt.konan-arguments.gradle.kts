import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

// An ordinary script-provided argument bean, not a plugin. Read KGP's distribution
// metadata at execution, after downloadKotlinNativeDistribution, without retaining Project.
class PariKonanArguments(
    @get:Internal val metadata: File,
    @get:Internal val dataDirectory: File,
    @get:Input val target: String,
    @get:Input val windows: Boolean,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val home = File(metadata.readText().trim())
        val properties = Properties().apply { File(home, "konan/konan.properties").inputStream().use { load(it) } }
        val key = if (target == "linux-x86_64") "toolchainDependency.linux_x64" else "toolchainDependency.mingw_x64"
        val directory = File(dataDirectory, "dependencies/${checkNotNull(properties.getProperty(key))}")
        val path = if (windows) "/mnt/${directory.absolutePath.take(1).lowercase()}" + directory.absolutePath.drop(2).replace('\\', '/')
            else directory.absolutePath
        return listOf(if (target == "linux-x86_64") "--toolchain-root" else "--platform-root", path)
    }
}
extra["parikt.konanArguments"] = { metadata: File, data: File, target: String, windows: Boolean ->
    PariKonanArguments(metadata, data, target, windows) as CommandLineArgumentProvider
}
