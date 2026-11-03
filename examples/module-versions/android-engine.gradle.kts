import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.proof.androidHelpers"] as Map<String, String>
configure<KotlinMultiplatformExtension> {
    android {
        namespace = "com.elianfabian.parikt"
        compileSdk = 36
        minSdk = 21
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            moduleName.set("parikt")
        }
        localDependencySelection { selectBuildTypeFrom.set(listOf("debug", "release")) }
    }
    sourceSets.named("androidMain") {
        kotlin.srcDir(File(checkout, "parikt/src/androidMain/kotlin"))
        dependencies {
            implementation(pariktLibs.androidx.startup.runtime)
            implementation(project(helpers.getValue(version)))
        }
    }
}
