plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}
kotlin {
    android {
        namespace = "proof.shared"
        compileSdk = 36
        minSdk = 21
        withHostTest {}
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    sourceSets.commonMain.dependencies { api(project(":parikt")) }
}
