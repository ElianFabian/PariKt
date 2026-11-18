import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import java.net.JarURLConnection
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.jar.JarFile

// A value source hides incidental cache-marker reads from configuration-cache inputs.
// The buildscript artifact itself remains a normal tracked Maven classpath dependency.
abstract class PariKtToolsCache : ValueSource<String, PariKtToolsCache.Parameters> {
    interface Parameters : ValueSourceParameters {
        val archive: RegularFileProperty
        val cacheRoot: DirectoryProperty
    }
    override fun obtain(): String {
        val archive = parameters.archive.get().asFile
        val hash = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
            .joinToString("") { "%02x".format(it) }
        val cache = parameters.cacheRoot.get().asFile
        Files.createDirectories(cache.toPath())
        val destination = File(cache, hash).canonicalFile
        FileChannel.open(File(cache, "$hash.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val complete = File(destination, ".complete")
                if (!complete.isFile) {
                    Files.createDirectories(destination.toPath())
                    JarFile(archive).use { jar -> jar.entries().asSequence().forEach { entry ->
                        val target = destination.toPath().resolve(entry.name).normalize()
                        check(target.startsWith(destination.toPath())) { "Invalid engine-tools archive entry: ${entry.name}" }
                        if (entry.isDirectory) Files.createDirectories(target) else {
                            Files.createDirectories(target.parent)
                            jar.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                        }
                    } }
                    complete.writeText(hash)
                }
            }
        }
        return destination.absolutePath
    }
}
// Applied settings scripts have their own classloader. Use the public source URI
// rather than assuming that loader inherits the caller's buildscript resources.
val connection = checkNotNull(buildscript.sourceURI) { "Missing engine-tools bootstrap source URI." }.toURL().openConnection() as? JarURLConnection
    ?: throw GradleException("Apply the bootstrap resource directly from the engine-tools Maven JAR.")
val archive = File(connection.jarFileURL.toURI())
val location = providers.of(PariKtToolsCache::class) {
    parameters.archive.set(archive)
    parameters.cacheRoot.set(File(gradle.gradleUserHomeDir, "caches/parikt/engine-tools"))
}.get()
extra["pariktEngineToolsDir"] = File(location)
apply(from = File(location, "gradle/parikt.binary.settings.gradle.kts"))
