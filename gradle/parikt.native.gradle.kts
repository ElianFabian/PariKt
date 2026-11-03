import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.kotlin.dsl.withGroovyBuilder
import java.security.MessageDigest
import java.util.jar.JarFile

val consumer = project
@Suppress("UNCHECKED_CAST")
val selection = extra["parikt.jvm.selection"] as Triple<String, String, Boolean>
@Suppress("UNCHECKED_CAST")
val engines = gradle.extensions.extraProperties["parikt.engines"] as Map<String, String>
val engine = engines.getValue(selection.first)
fun archiveHashes(library: File): List<List<Byte>> = if (library.isDirectory) {
    library.walkTopDown().filter { it.isFile && it.name == "libparikt.a" }.map {
        MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
    }.toList()
} else JarFile(library).use { jar ->
    jar.entries().asSequence().filter { it.name.endsWith("/libparikt.a") }.map { entry ->
        jar.getInputStream(entry).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
    }.toList()
}
fun selectedProvider(configurationName: String) {
    val providers = consumer.configurations.getByName(configurationName).incoming.resolutionResult.allComponents.mapNotNull {
        (it.id as? ProjectComponentIdentifier)?.projectPath
    }.filter { it in engines.values }
    check(providers == listOf(engine)) { "${consumer.path}:$configurationName expected one $engine provider, got $providers" }
}
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    val names = listOf("linuxX64", "mingwX64").filter { consumer.configurations.findByName("${it}CompileKlibraries") != null }
    if (names.isEmpty()) return@projectsEvaluated
    consumer.tasks.register("verifyPariNativeGraph") {
        group = "verification"
        doLast {
            names.forEach { name ->
                selectedProvider("${name}CompileKlibraries")
                logger.lifecycle("PASS ${consumer.path}:$name Native PARI=${selection.first} provider=$engine")
            }
        }
    }
    consumer.tasks.matching { it.name.startsWith("link") && names.any { target -> it.name.endsWith(target.replaceFirstChar(Char::uppercaseChar)) } }.configureEach {
        val targetName = names.find { name.endsWith(it.replaceFirstChar(Char::uppercaseChar)) } ?: return@configureEach
        val linkTask = this
        doFirst {
            selectedProvider("${targetName}CompileKlibraries")
            val provider = consumer.rootProject.project(engine)
            val nativeTask = provider.tasks.named("build${targetName.replaceFirstChar(Char::uppercaseChar)}NativeDependencies").get()
            val rawArchive = File(nativeTask.outputs.files.singleFile, "lib/libparikt.a")
            val expected = MessageDigest.getInstance("SHA-256").digest(rawArchive.readBytes()).toList()
            // Applied scripts do not share KGP's compile classpath. Read the task's public
            // libraries property through Gradle's standard Groovy interoperability API.
            val libraries = linkTask.withGroovyBuilder { getProperty("libraries") } as FileCollection
            val archives = libraries.files.flatMap(::archiveHashes)
            check(archives == listOf(expected)) { "${consumer.path}:$name expected exactly one selected libparikt.a for PARI ${selection.first}; found ${archives.size}" }
            logger.lifecycle("PASS ${consumer.path}:$name exactly one libparikt.a matching PARI ${selection.first}")
        }
    }
}
