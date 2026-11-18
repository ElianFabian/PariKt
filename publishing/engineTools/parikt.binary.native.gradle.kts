import org.gradle.kotlin.dsl.withGroovyBuilder
import org.gradle.api.file.FileCollection
import java.util.jar.JarFile

// The Maven binding supplies declarations; only a final binary links a selected C archive.
val consumer = project
val tools = extra["parikt.binary.tools"] as File
apply(from = File(tools, "gradle/parikt.konan-arguments.gradle.kts"))
apply(from = File(tools, "gradle/parikt.apple-arguments.gradle.kts"))
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    val version = selections.getValue(consumer.path).first
    val windows = System.getProperty("os.name").startsWith("Windows")
    fun nativePath(file: File): String {
        val absolute = file.absolutePath.replace('\\', '/')
        return if (windows) "/mnt/" + absolute[0].lowercaseChar() + absolute.substring(2) else absolute
    }
    @Suppress("UNCHECKED_CAST")
    val targets = consumer.extensions.getByName("kotlin").withGroovyBuilder { getProperty("targets") } as NamedDomainObjectCollection<Any>
    val nativeTargets = targets.filter { it.withGroovyBuilder { getProperty("platformType") }.toString() == "native" }
    if (nativeTargets.isEmpty()) return@projectsEvaluated
    val download = consumer.tasks.findByName("downloadKotlinNativeDistribution")
    val metadata = consumer.layout.buildDirectory.file("konan.txt").get().asFile
    val data = consumer.providers.gradleProperty("konan.data.dir")
        .orElse(consumer.providers.environmentVariable("KONAN_DATA_DIR"))
        .map { consumer.rootProject.file(it) }.orElse(File(System.getProperty("user.home"), ".konan")).get()
    fun compilation(target: Any): org.gradle.api.tasks.TaskProvider<Task> {
        @Suppress("UNCHECKED_CAST")
        val compilations = target.withGroovyBuilder { getProperty("compilations") } as NamedDomainObjectCollection<Any>
        @Suppress("UNCHECKED_CAST")
        return compilations.getByName("main").withGroovyBuilder { getProperty("compileTaskProvider") } as org.gradle.api.tasks.TaskProvider<Task>
    }
    val compilation = compilation(nativeTargets.first())
    // Legacy KGP installs the compiler while executing the archive-free consumer compilation.
    val legacyHome = if (download == null) compilation.get().withGroovyBuilder {
        (getProperty("konanHome") as org.gradle.api.provider.Provider<*>).get().toString()
    } else null
    val prepare = consumer.tasks.register("preparePariBinaryKonan") {
        if (download != null) dependsOn(download) else {
            dependsOn(compilation)
            outputs.file(metadata)
            inputs.property("compilerHome", checkNotNull(legacyHome))
            doLast { metadata.parentFile.mkdirs(); metadata.writeText(checkNotNull(legacyHome)) }
        }
    }
    @Suppress("UNCHECKED_CAST")
    val konanArguments = consumer.extra["parikt.konanArguments"] as (File, File, String, Boolean) -> org.gradle.process.CommandLineArgumentProvider
    @Suppress("UNCHECKED_CAST")
    val appleArguments = consumer.extra["parikt.appleArguments"] as (File, String, String) -> org.gradle.process.CommandLineArgumentProvider
    val compiler = consumer.plugins.getPlugin("org.jetbrains.kotlin.multiplatform").javaClass.classLoader
        .loadClass("org.jetbrains.kotlin.config.KotlinCompilerVersion").getField("VERSION").get(null).toString()
    nativeTargets.forEach { target ->
        val targetName = target.withGroovyBuilder { getProperty("name") }.toString()
        val targetId = target.withGroovyBuilder { getProperty("konanTarget") }.withGroovyBuilder { getProperty("name") }.toString()
        val ownerPath = consumer.path
        val requirements = consumer.tasks.register("verifyPariBinary" + targetName.replaceFirstChar(Char::uppercaseChar) + "Requirements") {
            group = "verification"
            // Resolve before KGP's cross-compilation onlyIf predicate, which otherwise
            // hides the explicit requirement diagnostic behind a generic predicate error.
            inputs.files(consumer.configurations.getByName("${targetName}CompileKlibraries"))
            doLast { logger.lifecycle("PASS $ownerPath $targetName PARI requirements") }
        }
        compilation(target).configure { dependsOn(requirements) }
        val appleFamily = when {
            targetId.startsWith("macos_") -> "macos"
            targetId.startsWith("ios_") -> "ios"
            targetId.startsWith("tvos_") -> "tvos"
            targetId.startsWith("watchos_") -> "watchos"
            else -> null
        }
        val destination = File(consumer.rootProject.layout.buildDirectory.get().asFile,
            "parikt-binary/native/$compiler/$version/$targetName")
        val archive = File(destination, "lib/libparikt.a")
        // Older KGP does not download target C SDKs until linking. The engine needs
        // those headers first, so link a tiny independent program to provision them.
        val sdkPreparation = if (download == null && appleFamily == null) consumer.tasks.register<Exec>(
            "preparePariBinarySdk" + targetName.replaceFirstChar(Char::uppercaseChar)
        ) {
            dependsOn(prepare)
            val home = checkNotNull(legacyHome)
            val output = consumer.layout.buildDirectory.dir("parikt-sdk/$targetId").get().asFile
            val source = File(output, "Probe.kt")
            inputs.property("compilerHome", home)
            inputs.property("target", targetId)
            outputs.dir(output)
            environment("KONAN_DATA_DIR", data.absolutePath)
            commandLine((if (windows) listOf("cmd", "/d", "/c", "$home/bin/konanc.bat") else listOf("$home/bin/konanc")) +
                listOf(source.absolutePath, "-target", targetId, "-produce", "program", "-o", File(output, "probe").absolutePath))
            doFirst { output.mkdirs(); source.writeText("fun main() {}\n") }
        } else null
        val taskName = "preparePariNative_" + "${compiler}_${version}_${targetName}".replace(Regex("[^A-Za-z0-9_]"), "_")
        val existing = consumer.rootProject.tasks.findByName(taskName)
        val engine = if (existing != null) consumer.rootProject.tasks.named(taskName) else consumer.rootProject.tasks.register<Exec>(taskName) {
            group = "native"
            doNotTrackState("The C builder checks source, ABI, compiler and SDK fingerprints.")
            dependsOn(prepare, compilation(target))
            if (sdkPreparation != null) dependsOn(sdkPreparation)
            outputs.dir(destination)
            if (appleFamily != null) {
                commandLine("bash", File(tools, "parikt/native/build-apple.sh"), "--pari-version", version,
                    "--target", targetName, "--out", destination)
                argumentProviders.add(appleArguments(metadata, targetId, appleFamily))
            } else {
                val cTarget = when (targetId) {
                    "linux_x64" -> "linux-x86_64"
                    "mingw_x64" -> "windows-x86_64"
                    else -> throw GradleException("Unsupported PariKt Native engine target: $targetId")
                }
                commandLine((if (windows) listOf("wsl.exe", "--exec", "bash") else listOf("bash")) +
                    listOf(nativePath(File(tools, "parikt/native/build-kotlin-native.sh")), "--pari-version", version,
                        "--target", cTarget, "--out", nativePath(destination)))
                argumentProviders.add(konanArguments(metadata, data, cTarget, windows))
            }
        }
        @Suppress("UNCHECKED_CAST")
        val binaries = target.withGroovyBuilder { getProperty("binaries") } as DomainObjectCollection<Any>
        binaries.configureEach {
            @Suppress("UNCHECKED_CAST")
            val linkerOptions = withGroovyBuilder { getProperty("linkerOpts") } as MutableList<String>
            linkerOptions.add(archive.absolutePath)
            @Suppress("UNCHECKED_CAST")
            val linkTask = withGroovyBuilder { getProperty("linkTaskProvider") } as org.gradle.api.tasks.TaskProvider<Task>
            val libraries = linkTask.get().withGroovyBuilder { getProperty("libraries") } as FileCollection
            val options = linkerOptions.toList()
            val owner = consumer.path
            linkTask.configure {
                dependsOn(engine)
                inputs.file(archive)
                doFirst {
                    check(options.count { it == archive.absolutePath } == 1) { "Expected exactly one selected engine archive in linker options." }
                    // A dependency with an older embedded engine must not silently add another.
                    libraries.files.forEach { library ->
                        val duplicate = if (library.isDirectory) library.walkTopDown().any { it.name == "libparikt.a" }
                        else if (library.extension in setOf("klib", "jar")) JarFile(library).use { jar ->
                            jar.entries().asSequence().any { it.name.endsWith("/libparikt.a") }
                        } else false
                        check(!duplicate) { "Embedded PARI engine conflicts with selected $version: $library" }
                    }
                    check(archive.isFile) { "Missing selected engine archive: $archive" }
                    logger.lifecycle("PASS $owner:$name exactly one selected PARI $version archive")
                }
            }
        }
    }
}
