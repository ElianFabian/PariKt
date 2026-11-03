import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins { id("com.android.application") }
@Suppress("UNCHECKED_CAST")
val selections = extra["parikt.selections"] as Map<String, Triple<String, String, Boolean>>
@Suppress("UNCHECKED_CAST")
val engines = extra["parikt.engineProjects"] as Map<String, String>
@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.proof.androidHelpers"] as Map<String, String>
val selectedVersion = selections.getValue(path).first
val consumerPath = path
val nativeAbis = providers.gradleProperty("nativeAbis")
    .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "x86_64"))
require(nativeAbis.isNotEmpty() && nativeAbis.all { it in setOf("arm64-v8a", "x86_64") }) { "Only arm64-v8a/x86_64 Android engines are implemented." }
android {
    namespace = "proof"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.elianfabian.parikt.proof." + if (project.name == "androidAppA") "a" else "b"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "PARI_EXPECTED_VERSION", "\"$selectedVersion\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    splits.abi {
        isEnable = true
        reset()
        include(*nativeAbis.toTypedArray())
        isUniversalApk = false
    }
    sourceSets.getByName("main") {
        kotlin.directories.clear()
        kotlin.directories.add(rootProject.file("androidAppA/src/main/kotlin").path)
        manifest.srcFile(rootProject.file("androidAppA/src/main/AndroidManifest.xml"))
    }
}
kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    implementation(project(":androidSharedLogic"))
    implementation(project(":parikt"))
}
tasks.register("verifyAndroidGraph") {
    group = "verification"
    doLast {
        listOf("debugCompileClasspath", "debugRuntimeClasspath").forEach { name ->
            val projects = configurations.getByName(name).incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? ProjectComponentIdentifier)?.projectPath
            }
            check(projects.filter { it in engines.values } == listOf(engines.getValue(selectedVersion))) { "Wrong Android engine in $consumerPath:$name" }
            val resolvedHelpers = projects.filter { it in helpers.values }
            check(resolvedHelpers.all { it == helpers.getValue(selectedVersion) } &&
                (name != "debugRuntimeClasspath" || resolvedHelpers.size == 1)) {
                "Wrong Android helper in $consumerPath:$name: $resolvedHelpers"
            }
            logger.lifecycle("PASS $consumerPath:$name Android PARI=$selectedVersion engine=${engines.getValue(selectedVersion)} helper=${helpers.getValue(selectedVersion)}")
        }
    }
}
