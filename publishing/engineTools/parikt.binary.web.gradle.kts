import org.gradle.kotlin.dsl.withGroovyBuilder
import java.util.concurrent.atomic.AtomicReference

val consumer = project
val tools = extra["parikt.binary.tools"] as File
fun jsString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\r", "\\r").replace("\n", "\\n") + "\""
gradle.projectsEvaluated {
    @Suppress("UNCHECKED_CAST")
    val participants = gradle.extensions.extraProperties["parikt.participants"] as Set<String>
    if (consumer.path !in participants) return@projectsEvaluated
    @Suppress("UNCHECKED_CAST")
    val targets = consumer.extensions.getByName("kotlin").withGroovyBuilder { getProperty("targets") } as NamedDomainObjectCollection<Any>
    if (targets.none { it.withGroovyBuilder { getProperty("platformType") }.toString() in setOf("js", "wasm") }) return@projectsEvaluated
    @Suppress("UNCHECKED_CAST")
    val selections = gradle.extensions.extraProperties["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
    val version = selections.getValue(consumer.path).first
    val directory = File(consumer.rootProject.layout.buildDirectory.get().asFile, "parikt-binary/web/$version")
    val factory = File(directory, "parikt.mjs")
    val windows = System.getProperty("os.name").startsWith("Windows")
    fun runnerPath(file: File): String {
        val path = file.absolutePath.replace('\\', '/')
        return if (windows) "/mnt/" + path[0].lowercaseChar() + path.substring(2) else path
    }
    val taskName = "preparePariWeb_" + version.replace(Regex("[^A-Za-z0-9_]"), "_")
    val engine = if (consumer.rootProject.tasks.findByName(taskName) != null) consumer.rootProject.tasks.named(taskName)
    else consumer.rootProject.tasks.register<Exec>(taskName) {
        group = "native"
        doNotTrackState("The Emscripten builder verifies source/toolchain/bridge fingerprints.")
        outputs.dir(directory)
        val emsdk = consumer.providers.gradleProperty("webEmsdkDir").orNull?.let(consumer.rootProject::file)
        commandLine((if (windows) listOf("wsl.exe", "--exec", "bash") else listOf("bash")) +
            listOf(runnerPath(File(tools, "parikt/native/build-web.sh")), "--pari-version", version, "--out", runnerPath(directory)) +
            (emsdk?.let { listOf("--emsdk", runnerPath(it)) } ?: emptyList()))
    }
    val generated = consumer.layout.buildDirectory.dir("parikt-web").get().asFile
    val loader = File(generated, "selected-engine-loader.mjs")
    val alias = File(generated, "zzzz-parikt-selected-engine.js")
    val loaderCode = """
        export async function resolve(specifier, context, nextResolve) {
          if (specifier === '@parikt/engine') return {url: ${jsString(factory.toURI().toASCIIString())}, shortCircuit: true};
          return nextResolve(specifier, context);
        }
    """.trimIndent()
    val aliasCode = """
        config.resolve = config.resolve || {};
        config.resolve.alias = config.resolve.alias || {};
        const pariktSelectedEngine = ${jsString(factory.absolutePath)};
        const pariktExistingEngine = config.resolve.alias['@parikt/engine${'$'}'] || config.resolve.alias['@parikt/engine'];
        if (pariktExistingEngine && pariktExistingEngine !== pariktSelectedEngine) throw new Error('PARI engine alias conflicts with parikt.properties');
        config.resolve.alias['@parikt/engine${'$'}'] = pariktSelectedEngine;
        config.resolve.fallback = {module: false, fs: false, path: false, url: false, ...config.resolve.fallback};
    """.trimIndent()
    val routing = consumer.tasks.register("preparePariWebRouting") {
        inputs.property("engine", factory.absolutePath)
        outputs.files(loader, alias)
        doLast {
            generated.mkdirs()
            loader.writeText(loaderCode)
            alias.writeText(aliasCode)
        }
    }
    fun fragments(name: String, original: AtomicReference<File?>): Pair<org.gradle.api.tasks.TaskProvider<Sync>, File> {
        val output = File(generated, "webpack-$name")
        val userFiles = consumer.providers.provider { listOfNotNull(original.get()) }
        return consumer.tasks.register<Sync>("preparePariWebConfig" + name.replaceFirstChar(Char::uppercaseChar)) {
            dependsOn(routing)
            from(userFiles)
            from(alias)
            into(output)
            duplicatesStrategy = DuplicatesStrategy.FAIL
        } to output
    }
    consumer.tasks.matching { it.name.contains("Node") && (it.name.endsWith("Run") || it.name.endsWith("Test")) }.configureEach {
        dependsOn(engine, routing)
        inputs.dir(directory)
        @Suppress("UNCHECKED_CAST")
        val environment = withGroovyBuilder { getProperty("environment") } as MutableMap<String, Any>
        val existing = environment["NODE_OPTIONS"]?.toString().orEmpty()
        environment["NODE_OPTIONS"] = (existing + " --experimental-loader=" + loader.toURI().toASCIIString()).trim()
    }
    // Query names without realizing unrelated KGP tasks or resolving their classpaths.
    // Register fragment tasks before any task/model configuration callbacks execute.
    val webpackNames = consumer.tasks.names.filter { it.endsWith("Webpack") && !it.startsWith("preparePari") }
    val browserNames = consumer.tasks.names.filter { it.endsWith("BrowserTest") && !it.startsWith("preparePari") }
    val configs = (webpackNames + browserNames).associateWith {
        val original = AtomicReference<File?>()
        val (prepare, output) = fragments(it, original)
        Triple(prepare, output, original)
    }
    consumer.tasks.matching { it.name in webpackNames }.configureEach {
        val task = this
        val original = task.withGroovyBuilder { getProperty("configDirectory") } as? File
        val (prepare, output, source) = configs.getValue(task.name)
        source.set(original)
        task.dependsOn(engine, prepare)
        task.inputs.dir(directory)
        // commonWebpackConfig applies its directory only to the effective config,
        // after the task's configDirectory getter. Merge at that point and rerun this
        // lightweight bundling step so edits to those late-selected fragments are seen.
        task.doNotTrackState("User Webpack fragment directories are selected by configuration appliers.")
        task.withGroovyBuilder {
            "webpackConfigApplier"(Action<Any> {
                withGroovyBuilder {
                    val original = getProperty("configDirectory") as? File
                    if (original?.canonicalFile != output.canonicalFile) {
                        check(output.canonicalFile.toPath().startsWith(generated.canonicalFile.toPath()) && output.canonicalFile != generated.canonicalFile)
                        if (output.exists()) output.deleteRecursively()
                        if (original?.isDirectory == true) original.copyRecursively(output, overwrite = true)
                        output.mkdirs()
                        val selected = File(output, alias.name)
                        check(!selected.exists()) { "Reserved PariKt fragment name in user Webpack directory: ${alias.name}" }
                        selected.writeText(aliasCode)
                    }
                    setProperty("configDirectory", output)
                }
            })
        }
    }
    consumer.tasks.matching { it.name in browserNames }.configureEach {
        val test = this
        val (prepare, output, source) = configs.getValue(test.name)
        test.dependsOn(engine, routing, prepare)
        test.inputs.dir(directory)
        test.withGroovyBuilder {
            "onTestFrameworkSet"(Action<Any> {
                withGroovyBuilder {
                    if (hasProperty("webpackConfig")) {
                        val config = getProperty("webpackConfig")
                        val original = config.withGroovyBuilder { getProperty("configDirectory") } as? File
                        if (original != output) source.set(original)
                        config.withGroovyBuilder { setProperty("configDirectory", output) }
                    }
                }
            })
        }
    }
}
