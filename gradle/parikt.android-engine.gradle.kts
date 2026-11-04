import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.gradle.kotlin.dsl.withGroovyBuilder

val checkout = extra["parikt.checkout"] as File
val version = extra["parikt.engineVersion"] as String
@Suppress("UNCHECKED_CAST")
val helpers = gradle.extensions.extraProperties["parikt.android.helpers"] as Map<String, String>
// Keep this script stable before Gradle reads it. Android remains optional for
// Kotlin-only roots; public model access avoids a compile-time AGP prerequisite.
if (rootProject.buildscript.classLoader.getResource("com/android/build/gradle/LibraryPlugin.class") != null) {
    pluginManager.apply("com.android.kotlin.multiplatform.library")
    configure<KotlinMultiplatformExtension> {
        (this as ExtensionAware).extensions.getByName("android").withGroovyBuilder {
            setProperty("namespace", "com.elianfabian.parikt")
            setProperty("compileSdk", 36)
            setProperty("minSdk", 21)
            (getProperty("compilerOptions") as KotlinJvmCompilerOptions).apply {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                moduleName.set("parikt")
            }
            getProperty("localDependencySelection").withGroovyBuilder {
                @Suppress("UNCHECKED_CAST")
                (getProperty("selectBuildTypeFrom") as org.gradle.api.provider.ListProperty<String>).set(listOf("debug", "release"))
            }
        }
        sourceSets.named("androidMain") {
            kotlin.srcDir(File(checkout, "parikt/src/androidMain/kotlin"))
            dependencies {
                implementation(pariktLibs.androidx.startup.runtime)
                implementation(project(helpers.getValue(version)))
            }
        }
    }
}
