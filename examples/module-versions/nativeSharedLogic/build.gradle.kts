plugins { id("org.jetbrains.kotlin.multiplatform") }
kotlin {
    linuxX64()
    mingwX64()
    sourceSets.commonMain.dependencies { api(project(":parikt")) }
}
