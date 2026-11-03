plugins { id("org.jetbrains.kotlin.jvm"); application }
dependencies {
    implementation(project(":sharedLogic"))
    implementation(project(":parikt")) // Direct and transitive references must collapse to one provider.
}
application { mainClass.set("proof.MainKt") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
