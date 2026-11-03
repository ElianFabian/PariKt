@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class, org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

plugins { id("org.jetbrains.kotlin.multiplatform") }

@Suppress("UNCHECKED_CAST")
val selectedVersion = (extra["parikt.selection"] as Triple<String, String, Boolean>).first
val settingsSource = layout.buildDirectory.dir("generated/consumer-settings")
val generateConsumerSettings = tasks.register("generateConsumerSettings") {
	inputs.property("pariVersion", selectedVersion)
	outputs.dir(settingsSource)
	doLast {
		settingsSource.get().file("ConsumerSettings.kt").asFile.apply {
			parentFile.mkdirs()
			writeText("package sample\ninternal const val expectedVersion = \"$selectedVersion\"\n")
		}
	}
}

kotlin {
	jvm()
	linuxX64 { binaries.executable { entryPoint = "sample.main" } }
	mingwX64 { binaries.executable { entryPoint = "sample.main" } }
	js { useEsModules(); nodejs(); binaries.executable() }
	wasmJs { nodejs(); binaries.executable() }
	applyDefaultHierarchyTemplate {
		common { group("web") { withJs(); withWasmJs() } }
	}
	sourceSets {
		commonMain {
			kotlin.srcDir(files(settingsSource).builtBy(generateConsumerSettings))
			dependencies {
				implementation(project(":parikt"))
				implementation(pariktLibs.kotlinx.coroutines.core)
			}
		}
	}
}

tasks.register<JavaExec>("runJvm") {
	group = "application"
	dependsOn("jvmMainClasses")
	mainClass.set("sample.MainKt")
	val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
	classpath(compilation.output.allOutputs, configurations.getByName("jvmRuntimeClasspath"))
}
