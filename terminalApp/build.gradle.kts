plugins {
	id("org.jetbrains.kotlin.jvm")
	application
}

dependencies {
	implementation(project(":parikt"))
	implementation(project(":consoleUi"))
}

application {
	mainClass.set("com.elianfabian.parikt.terminal.MainKt")
}

java {
	sourceCompatibility = JavaVersion.VERSION_17
	targetCompatibility = JavaVersion.VERSION_17
}

tasks.register<JavaExec>("verifyPariEngine") {
	group = "verification"
	description = "Checks this console's selected engine through the shared console API."
	mainClass.set(application.mainClass)
	classpath(sourceSets.main.get().runtimeClasspath)
	args("--check-engine")
}

tasks.named<JavaExec>("run") {
	standardInput = System.`in`
}

kotlin {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
	}
}
