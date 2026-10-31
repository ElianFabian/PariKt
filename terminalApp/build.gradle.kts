plugins {
	alias(libs.plugins.kotlin.jvm)
	application
}

dependencies {
	implementation(project(":parikt"))
}

val pariVersion = providers.gradleProperty("pariVersion").getOrElse("2.17.1")
val jvmHost = when {
	System.getProperty("os.name").startsWith("Windows") -> "windows-x86_64"
	System.getProperty("os.name").startsWith("Linux") -> "linux-x86_64"
	else -> "unsupported"
}

application {
	mainClass.set("com.elianfabian.parikt.terminal.MainKt")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

val jvmNativeDirectory = providers.gradleProperty("jvmNativeDir")
	.orElse(providers.systemProperty("parikt.native.dir"))
	.orElse(rootProject.file("parikt/.native/jvm-$pariVersion/$jvmHost").absolutePath)

tasks.withType<JavaExec>().configureEach {
	jvmNativeDirectory.orNull?.let { systemProperty("java.library.path", it) }
}

tasks.named<JavaExec>("run") {
	dependsOn(":parikt:buildJvmNativeDependencies")
	standardInput = System.`in`
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}
