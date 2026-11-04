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
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    val names = listOf("linuxX64", "mingwX64").filter { consumer.configurations.findByName("${it}CompileKlibraries") != null }
    if (names.isEmpty()) return@projectsEvaluated
    consumer.tasks.register("verifyPariNativeGraph") {
        group = "verification"
        val owner = consumer.path
        val selectedVersion = selection.first
        val selectedEngine = engine
        val availableEngines = engines.values.toSet()
        val graphs = names.associateWith { name ->
            consumer.configurations.getByName("${name}CompileKlibraries").incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? ProjectComponentIdentifier)?.projectPath
            }.filter { it in availableEngines }
        }
        doLast {
            graphs.forEach { (name, providers) ->
                check(providers == listOf(selectedEngine)) { "$owner:$name expected one $selectedEngine provider, got $providers" }
                logger.lifecycle("PASS $owner:$name Native PARI=$selectedVersion provider=$selectedEngine")
            }
        }
    }
    consumer.tasks.matching { it.name.startsWith("link") && names.any { target -> it.name.endsWith(target.replaceFirstChar(Char::uppercaseChar)) } }.configureEach {
        val targetName = names.find { name.endsWith(it.replaceFirstChar(Char::uppercaseChar)) } ?: return@configureEach
        val owner = consumer.path
        val selectedVersion = selection.first
        val availableEngines = engines.values.toSet()
        val graphProviders = consumer.configurations.getByName("${targetName}CompileKlibraries").incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? ProjectComponentIdentifier)?.projectPath
        }.filter { it in availableEngines }
        check(graphProviders == listOf(engine)) { "$owner:$name expected one $engine provider, got $graphProviders" }
        val nativeTask = consumer.rootProject.project(engine).tasks.named("build${targetName.replaceFirstChar(Char::uppercaseChar)}NativeDependencies").get()
        val rawArchive = File(nativeTask.outputs.files.singleFile, "lib/libparikt.a")
        // Read the public input collection during configuration, retaining no link Task.
        val libraries = withGroovyBuilder { getProperty("libraries") } as FileCollection
        doFirst {
            val expected = MessageDigest.getInstance("SHA-256").digest(rawArchive.readBytes()).toList()
            val archives = libraries.files.flatMap { library ->
                if (library.isDirectory) library.walkTopDown().filter { it.isFile && it.name == "libparikt.a" }.map {
                    MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
                }.toList() else JarFile(library).use { jar ->
                    jar.entries().asSequence().filter { it.name.endsWith("/libparikt.a") }.map { entry ->
                        jar.getInputStream(entry).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
                    }.toList()
                }
            }
            check(archives == listOf(expected)) { "$owner:$name expected exactly one selected libparikt.a for PARI $selectedVersion; found ${archives.size}" }
            logger.lifecycle("PASS $owner:$name exactly one libparikt.a matching PARI $selectedVersion")
        }
    }
}
