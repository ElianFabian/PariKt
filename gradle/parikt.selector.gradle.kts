// Empty public project used only when the consuming build has no root PARI default.
// Routing selects providers per consumer; a convenience task must never guess a sibling's version.
val rootProperties = rootProject.file("parikt.properties")
listOf("buildJvmNativeDependencies", "jvmJar", "build", "assemble", "check",
    "buildNativeDependencies", "assembleDebug", "bundleDebugAar").forEach { taskName ->
    tasks.register(taskName) {
        doFirst {
            throw GradleException("No root PARI default for $path. Set pari.version in $rootProperties " +
                "or invoke the consuming application's task. Module overrides are not a default.")
        }
    }
}
