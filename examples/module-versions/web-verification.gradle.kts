import java.io.ByteArrayOutputStream
import java.util.Properties
import java.security.MessageDigest

@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val selections = extra["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
val checkout = extra["parikt.checkout"] as File
val buildRoot = project
val webpackVersion = extra["parikt.proof.webpackVersion"] as String
val kotlinVersion = extra["parikt.proof.kotlinVersion"] as String
val nativeCompiler = providers.provider {
    val recorded = File(checkout, "parikt/build/konan.txt")
    val dataDirectory = providers.gradleProperty("konan.data.dir")
        .orElse(providers.environmentVariable("KONAN_DATA_DIR"))
        .map { buildRoot.file(it) }.getOrElse(File(System.getProperty("user.home"), ".konan"))
    val host = if (System.getProperty("os.name").startsWith("Windows")) "windows" else
        if (System.getProperty("os.name").startsWith("Mac")) "macos" else "linux"
    val candidates = listOfNotNull(
        providers.gradleProperty("kotlin.native.home").orNull?.let(buildRoot::file),
        recorded.takeIf { it.isFile }?.readText()?.trim()?.let(::File)
    ) + dataDirectory.listFiles().orEmpty().filter { it.name.contains(host) && it.name.endsWith("-$kotlinVersion") }
    candidates.firstOrNull { File(it, "konan/lib/kotlin-native-compiler-embeddable.jar").isFile }
        ?: throw GradleException("ABI inspection needs the pinned Native klib tool. Run Native verification once to install it; Node/browser builds do not require this diagnostic tool.")
}
val dumpTasks = mutableListOf<TaskProvider<JavaExec>>()
engines.forEach { (version, path) ->
    listOf("js" to "Js", "wasmJs" to "WasmJs").forEach { (target, suffix) ->
        val library = providers.provider { buildRoot.project(path).layout.buildDirectory.dir("classes/kotlin/$target/main").get().asFile }
        val output = layout.buildDirectory.file("web-abi/$version/$target.abi")
        val captured = ByteArrayOutputStream()
        dumpTasks += tasks.register<JavaExec>("dumpWebAbi${version.replace('.', '_')}$suffix") {
            dependsOn("$path:compileKotlin$suffix")
            inputs.dir(library)
            outputs.file(output)
            classpath(files(nativeCompiler.map { fileTree(File(it, "konan/lib")) { include("*.jar") } }))
            mainClass.set("org.jetbrains.kotlin.cli.utilities.MainKt")
            standardOutput = captured
            doFirst {
                captured.reset()
                args = listOf("klib", "dump-abi", library.get().absolutePath)
            }
            doLast {
                output.get().asFile.apply { parentFile.mkdirs(); writeText(captured.toString("UTF-8")) }
            }
        }
    }
}
tasks.register("verifyWebIdentity") {
    group = "verification"
    dependsOn(dumpTasks)
    doLast {
        listOf("js", "wasmJs").forEach { target ->
            val signatures = engines.map { (version, path) ->
                val directory = buildRoot.project(path).layout.buildDirectory.dir("classes/kotlin/$target/main").get().asFile
                val metadata = Properties().apply { File(directory, "default/manifest").inputStream().use { load(it) } }
                logger.lifecycle("  $path $target unique_name=${metadata.getProperty("unique_name")} jsOutputName=${metadata.getProperty("jsOutputName")}")
                val abi = layout.buildDirectory.file("web-abi/$version/$target.abi").get().asFile.readLines()
                    .filterNot { it.startsWith("// Library unique name:") }
                check(abi.any { it.contains("PariKt") }) { "Missing public API in $target ABI dump" }
                listOf("abi_version", "metadata_version", "ir_signature_versions", "builtins_platform", "wasm_targets")
                    .associateWith { metadata.getProperty(it) } to abi
            }
            check(signatures.distinct().size == 1) { "Public web ABI or metadata format differs for $target" }
            logger.lifecycle("PASS web identity $target: matching public ABI signatures and metadata format; provider unique names recorded")
        }
    }
}
tasks.register<Exec>("verifyWebNpm") {
    group = "verification"
    dependsOn("kotlinNpmInstall", "kotlinWasmNpmInstall")
    dependsOn(listOf(":webAppA", ":webAppB").map { engines.getValue(selections.getValue(it).first) }
        .distinct().map { "$it:prepareWebEnginePackage" })
    doFirst {
        val outputs = tasks.named("kotlinNodeJsSetup").get().outputs.files.files
        val node = outputs.flatMap { listOf(it, File(it, "node.exe"), File(it, "bin/node")) }
            .filter { it.isFile && it.name in setOf("node", "node.exe") }.distinctBy { it.canonicalPath }.single()
        commandLine(listOf(node.absolutePath, file("verify-web-npm.mjs").absolutePath,
            layout.buildDirectory.get().asFile.absolutePath, webpackVersion) +
            listOf("webAppA", "webAppB").map { "$it=${selections.getValue(":$it").first}" })
    }
}
tasks.register("verifyWebBundles") {
    group = "verification"
    dependsOn(listOf("webAppA", "webAppB").flatMap { app ->
        listOf(":$app:jsBrowserDistribution", ":$app:wasmJsBrowserDistribution")
    })
    doLast {
        fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
        val knownEngines = engines.keys.associateWith { hash(File(checkout, "parikt/.native/web-$it/parikt-engine.wasm")) }
        listOf("webAppA", "webAppB").forEach { app ->
            val version = selections.getValue(":$app").first
            listOf("js", "wasmJs").forEach { target ->
                val directory = buildRoot.project(":$app").layout.buildDirectory.dir("dist/$target/productionExecutable").get().asFile
                check(File(directory, "index.html").isFile) { "Missing browser entry in $directory" }
                val wasm = directory.walkTopDown().filter { it.isFile && it.extension == "wasm" }.map(::hash).toList()
                check(wasm.count { it == knownEngines.getValue(version) } == 1) { "Expected one selected engine in $directory" }
                check(knownEngines.filterKeys { it != version }.values.none { it in wasm }) { "Another engine leaked into $directory" }
                logger.lifecycle("PASS :$app $target bundle: exactly one PARI $version engine, no other selected versions")
            }
        }
    }
}
