plugins {
	id("org.jetbrains.kotlin.jvm")
	id("org.jetbrains.kotlin.plugin.compose")
	application
}

val hostOs = when {
	System.getProperty("os.name").startsWith("Windows") -> "windows"
	System.getProperty("os.name").startsWith("Linux") -> "linux"
	System.getProperty("os.name").startsWith("Mac") -> "macos"
	else -> throw GradleException("Unsupported desktop OS: ${System.getProperty("os.name")}")
}
val hostArch = when (System.getProperty("os.arch").lowercase()) {
	"amd64", "x86_64" -> "x64"
	"aarch64", "arm64" -> "arm64"
	else -> throw GradleException("Unsupported desktop architecture: ${System.getProperty("os.arch")}")
}

dependencies {
	implementation(project(":consoleUi"))
	implementation(project(":parikt"))
	implementation("org.jetbrains.compose.desktop:desktop-jvm-$hostOs-$hostArch:${libs.versions.composeMultiplatform.get()}")
	implementation(libs.kotlinx.coroutines.swing)
	testImplementation("org.jetbrains.compose.ui:ui-test-junit4-desktop:${libs.versions.composeMultiplatform.get()}")
}

application { mainClass.set("com.elianfabian.parikt.desktop.MainKt") }
java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

tasks.withType<JavaExec>().configureEach {
	dependsOn(":parikt:buildJvmNativeDependencies")
	doFirst {
		val libraryDirectory = providers.systemProperty("parikt.native.dir").orNull?.let(rootProject::file)
			?: project(":parikt").tasks.named("buildJvmNativeDependencies").get().outputs.files.singleFile
		systemProperty("java.library.path", libraryDirectory.absolutePath)
	}
}

tasks.test {
	dependsOn(":parikt:buildJvmNativeDependencies")
	inputs.files(project(":parikt").tasks.named("buildJvmNativeDependencies").map { it.outputs.files })
	systemProperty("java.awt.headless", "true")
	doFirst {
		val libraryDirectory = providers.systemProperty("parikt.native.dir").orNull?.let(rootProject::file)
			?: project(":parikt").tasks.named("buildJvmNativeDependencies").get().outputs.files.singleFile
		systemProperty("java.library.path", libraryDirectory.absolutePath)
	}
}
