@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
plugins { id("org.jetbrains.kotlin.multiplatform") }
kotlin {
    js { useEsModules(); nodejs(); browser() }
    wasmJs { nodejs(); browser() }
    sourceSets.commonMain.dependencies { api(project(":parikt")) }
}
