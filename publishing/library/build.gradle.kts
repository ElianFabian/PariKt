@file:Suppress("OPT_IN_USAGE")
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    `maven-publish`
}

val repository = extra["parikt.repository"] as File
val backend = File(repository, "parikt")
val appleFamilies = providers.gradleProperty("parikt.apple.families").orElse("macos,ios,tvos,watchos").get().split(',').toSet()
val bindings = layout.buildDirectory.dir("generated/webBindings")
val generateWebBindings = tasks.register<Sync>("generateWebBindings") {
    from(File(backend, "src/webMain/kotlin")) {
        // JsModule is a platform annotation in Kotlin 2.0; shared metadata must only
        // contain the opaque expect declaration, not an external JS factory.
        filter { line: String -> when {
            line.startsWith("@JsModule(") || line.startsWith("private external fun createPari") -> ""
            line.startsWith("internal fun loadPariModule()") -> "internal expect fun loadPariModule(): WebEngineLoading"
            else -> line
        } }
    }
    into(bindings)
}
val platformBindings = layout.buildDirectory.dir("generated/platformBindings")
val generatePlatformBindings = tasks.register("generatePlatformBindings") {
    outputs.dir(platformBindings)
    doLast {
        listOf("jsMain", "wasmJsMain").forEach { target ->
            val output = platformBindings.get().file("$target/com/elianfabian/parikt/WebFactory.kt").asFile
            output.parentFile.mkdirs()
            output.writeText("""
                package com.elianfabian.parikt
                @JsModule("@parikt/engine")
                private external fun createPari(): WebEngineLoading
                internal actual fun loadPariModule(): WebEngineLoading = createPari()
            """.trimIndent())
        }
    }
}
kotlin {
    explicitApi()
    compilerOptions.freeCompilerArgs.add("-Xexpect-actual-classes")
    jvm {
        compilerOptions.moduleName.set("parikt")
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions.moduleName.set("parikt")
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    js { useEsModules(); browser(); nodejs(); compilerOptions.moduleName.set("parikt") }
    wasmJs { browser(); nodejs(); compilerOptions.moduleName.set("parikt") }
    val native = mutableListOf<KotlinNativeTarget>(linuxX64(), mingwX64())
    @Suppress("DEPRECATION")
    val apple = buildList {
        if ("macos" in appleFamilies) { add(macosX64()); add(macosArm64()) }
        if ("ios" in appleFamilies) { add(iosArm64()); add(iosX64()); add(iosSimulatorArm64()) }
        if ("tvos" in appleFamilies) { add(tvosArm64()); add(tvosX64()); add(tvosSimulatorArm64()) }
        if ("watchos" in appleFamilies) { add(watchosArm64()); add(watchosDeviceArm64()); add(watchosX64()); add(watchosSimulatorArm64()) }
    }
    native.addAll(apple)
    native.forEach { target ->
        target.compilerOptions.moduleName.set("parikt")
        target.compilations.getByName("main").cinterops.create("pari") {
            definitionFile.set(file("pari-bindings.def"))
            includeDirs(File(backend,"native/kotlin-native"))
        }
    }
    applyDefaultHierarchyTemplate { common { group("web") { withJs(); withWasmJs() } } }
    sourceSets {
        listOf("commonMain","jvmMain","androidMain","nativeMain","linuxX64Main","mingwX64Main","appleMain","jsMain","wasmJsMain").forEach { name ->
            findByName(name)?.kotlin?.srcDir(File(backend,"src/$name/kotlin"))
        }
        getByName("webMain").kotlin.srcDir(files(bindings).builtBy(generateWebBindings))
        listOf("jsMain", "wasmJsMain").forEach { name ->
            getByName(name).kotlin.srcDir(files(platformBindings.map { it.dir(name) }).builtBy(generatePlatformBindings))
        }
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") }
        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.15.0")
            implementation("androidx.startup:startup-runtime:1.2.0")
        }
    }
}
android {
    namespace = "com.elianfabian.parikt"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    sourceSets.getByName("main").manifest.srcFile(File(backend,"src/androidMain/AndroidManifest.xml"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
afterEvaluate {
    // KGP creates Android publications late and assigns their default coordinate then.
    publishing.publications.withType<MavenPublication>().configureEach {
        artifactId = when (name) {
            "kotlinMultiplatform" -> "parikt"
            "androidRelease" -> "parikt-android"
            else -> "parikt-" + name.lowercase()
        }
    }
}
apply(from = rootProject.file("publication.gradle.kts"))
