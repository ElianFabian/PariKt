plugins {
	id("org.jetbrains.kotlin.jvm")
	application
}

dependencies {
	implementation(project(":parikt"))
}

application {
	mainClass.set("com.elianfabian.parikt.terminal.MainKt")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaExec>().configureEach {
	dependsOn(":parikt:buildJvmNativeDependencies")
	doFirst {
		val directory = providers.systemProperty("parikt.native.dir").orNull?.let(rootProject::file)
			?: project(":parikt").tasks.named("buildJvmNativeDependencies").get().outputs.files.singleFile
		systemProperty("java.library.path", directory.absolutePath)
	}
}

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}
