import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.util.jar.JarFile
import java.security.MessageDigest

plugins { id("org.jetbrains.kotlin.multiplatform") }

@Suppress("UNCHECKED_CAST")
val selections = extra["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
val version = selections.getValue(path).first
val engine = engines.getValue(version)
val windows = System.getProperty("os.name").startsWith("Windows")
val consumerPath = project.path
configure<KotlinMultiplatformExtension> {
    listOf(linuxX64(), mingwX64()).forEach { target ->
        target.binaries.executable { entryPoint = "proof.main" }
        val suffix = target.name.replaceFirstChar { it.uppercase() }
        tasks.register<Exec>("verify$suffix") {
            group = "verification"
            dependsOn("linkDebugExecutable$suffix")
            doFirst {
                val file = target.binaries.getExecutable("DEBUG").outputFile
                check(target.name != "mingwX64" || windows) { "Run the Windows executable on Windows." }
                commandLine(if (windows && target.name == "linuxX64") {
                    listOf("wsl", "/mnt/${file.absolutePath.substring(0, 1).lowercase()}" + file.absolutePath.substring(2).replace('\\', '/'), version)
                } else listOf(file.absolutePath, version))
            }
        }
    }
    sourceSets.commonMain {
        kotlin.srcDir(rootProject.file("nativeAppA/src/commonMain/kotlin"))
        dependencies {
            implementation(project(":nativeSharedLogic"))
            implementation(project(":parikt"))
            implementation(pariktLibs.kotlinx.coroutines.core)
        }
    }
}
tasks.withType<KotlinNativeLink>().configureEach {
    doFirst {
        val targetName = if (name.endsWith("MingwX64")) "mingwX64" else "linuxX64"
        val configuration = configurations.getByName("${targetName}CompileKlibraries")
        val providers = configuration.incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? ProjectComponentIdentifier)?.projectPath
        }.filter { it in engines.values }
        check(providers == listOf(engine)) { "Expected one $engine provider, got $providers" }
        val engineFiles = libraries.files.filter { it.absolutePath.contains("pariktEngine_") }
        check(engineFiles.isNotEmpty()) { "No engine klib in final link inputs" }
        val selectedBuild = rootProject.project(engine).layout.buildDirectory.get().asFile.toPath()
        check(engineFiles.all { it.toPath().startsWith(selectedBuild) }) { "Mixed engine link inputs: $engineFiles" }
        val archiveHashes = libraries.files.flatMap { library ->
            if (library.isDirectory) library.walkTopDown().filter { it.name == "libparikt.a" }.map {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }.toList() else JarFile(library).use { jar ->
                jar.entries().asSequence().filter { it.name.endsWith("/libparikt.a") }.map { entry ->
                    jar.getInputStream(entry).use { MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList() }
                }.toList()
            }
        }
        val checkout = rootProject.extra["parikt.checkout"] as File
        val host = if (targetName == "linuxX64") "linux-x86_64" else "windows-x86_64"
        val expectedArchive = File(checkout, "parikt/.native/native-$version/$host/lib/libparikt.a")
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(expectedArchive.readBytes()).toList()
        check(archiveHashes == listOf(expectedHash)) { "Expected exactly one selected libparikt.a, found ${archiveHashes.size} archives" }
        logger.lifecycle("PASS $path selected PARI $version link inputs: ${engineFiles.joinToString()}")
        logger.lifecycle("PASS $path exactly one libparikt.a matching PARI $version")
    }
}

tasks.register("verifyNativeGraph") {
    group = "verification"
    doLast {
        listOf("linuxX64", "mingwX64").forEach { target ->
            val configuration = configurations.getByName("${target}CompileKlibraries")
            val providers = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? ProjectComponentIdentifier)?.projectPath
            }.filter { it in engines.values }
            check(providers == listOf(engine)) { "Expected one $engine provider, got $providers" }
            logger.lifecycle("PASS $consumerPath:$target native graph PARI=$version provider=$engine")
        }
    }
}
