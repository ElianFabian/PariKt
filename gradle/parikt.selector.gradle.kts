// Empty public project used only when the consuming build has no root PARI default.
// Routing selects providers per consumer; a convenience task must never guess a sibling's version.
val rootProperties = rootProject.file("parikt.properties")
listOf("buildJvmNativeDependencies", "jvmJar", "build", "assemble", "check",
    "buildNativeDependencies", "assembleDebug", "bundleDebugAar", "buildWebNativeDependencies",
    "prepareWebEngineMetadata", "prepareWebEnginePackage", "verifyWebNative",
    "buildLinuxX64NativeDependencies", "buildMingwX64NativeDependencies",
    "compileKotlinLinuxX64", "compileKotlinMingwX64", "compileNativeMainKotlinMetadata",
    "commonizeCInterop", "verifyLinuxX64", "verifyMingwX64").forEach { taskName ->
    tasks.register(taskName) {
        val propertiesFile = rootProperties
        doFirst {
            throw GradleException("No root PARI default for $path. Set pari.version in $propertiesFile " +
                "or invoke the consuming application's task. Module overrides are not a default.")
        }
    }
}
