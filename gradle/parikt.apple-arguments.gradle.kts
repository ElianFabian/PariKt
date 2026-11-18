import org.gradle.process.CommandLineArgumentProvider
import java.util.Properties

// Read the installed consumer compiler's deployment floor, not the sample catalog version.
class PariAppleArguments(
    @get:Internal val metadata: File,
    @get:Input val konanTarget: String,
    @get:Input val family: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val home = File(metadata.readText().trim())
        val properties = Properties().apply { File(home, "konan/konan.properties").inputStream().use { load(it) } }
        var minimum = properties.getProperty("osVersionMin.$konanTarget")
            ?: properties.getProperty("minVersion.$family")
            ?: error("No deployment minimum for $konanTarget in $home")
        repeat(8) {
            if (minimum.startsWith("$")) minimum = properties.getProperty(minimum.drop(1))
                ?: error("Unknown Kotlin/Native deployment property $minimum")
        }
        check(minimum.matches(Regex("[0-9]+\\.[0-9]+(\\.[0-9]+)?"))) { "Invalid deployment minimum: $minimum" }
        return listOf("--minimum-version", minimum)
    }
}
extra["parikt.appleArguments"] = { metadata: File, target: String, family: String ->
    PariAppleArguments(metadata, target, family) as CommandLineArgumentProvider
}
