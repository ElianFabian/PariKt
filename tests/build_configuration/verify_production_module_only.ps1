#requires -Version 7.0
# Test the production settings script rather than the experimental provider scaffold.
# Requires prepared 2.15.0/2.19.0 JVM engines: every case also checks compatible cache reuse.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot 'build/production-module-only'
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
function Write-Fixture([string]$Path, [string]$Content) {
    $file = Join-Path $fixture $Path
    $null = New-Item -ItemType Directory -Path (Split-Path $file) -Force
    [IO.File]::WriteAllText($file, $Content, $utf8)
}
function Copy-Module([string]$From, [string]$To) {
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $source $From) -Recurse -File)) {
        $relative = $file.FullName.Substring((Join-Path $source $From).Length).TrimStart('\', '/')
        if ($relative -match '(^|[\\/])(build|\.gradle|\.kotlin)([\\/]|$)') { continue }
        $destination = Join-Path $fixture "$To/$relative"
        $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
        Copy-Item -LiteralPath $file.FullName -Destination $destination
    }
}
Write-Fixture 'settings.gradle.kts' @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ProductionModuleOnly"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":appA", ":appB", ":sharedLogic", ":kmpLibrary", ":unrelated")
'@
Write-Fixture 'build.gradle.kts' @'
plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false
}
'@
foreach ($module in @('appA', 'appB', 'sharedLogic')) {
    Copy-Module $module $module
    Write-Fixture "$module/parikt.properties" "pari.version=2.19.0`n"
}
foreach ($app in @('appA', 'appB')) {
    [IO.File]::AppendAllText((Join-Path $fixture "$app/build.gradle.kts"), @'

@Suppress("UNCHECKED_CAST")
val selected = extra["parikt.jvm.selection"] as Triple<String, String, Boolean>
tasks.named<JavaExec>("run") { args(selected.first) }
'@, $utf8)
}
Write-Fixture 'unrelated/build.gradle.kts' 'plugins { base }'
$kmpBuild = @'
plugins { id("org.jetbrains.kotlin.multiplatform") }
kotlin {
    jvm { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    sourceSets.commonMain.dependencies { api(project(":parikt")) }
}
tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
tasks.register("verifyCommonSelection") {
    doLast {
        val common = configurations.filter { it.isCanBeResolved &&
            it.allDependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java).any { dependency -> dependency.path == ":parikt" } &&
            it.attributes.keySet().any { key ->
            key.name == "org.jetbrains.kotlin.platform.type" && it.attributes.getAttribute(key).toString() == "common"
        } }
        check(common.isNotEmpty())
        common.forEach { configuration ->
            val paths = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
            }.filter { it == ":parikt" || it.startsWith(":pariktEngine_") }
            check(paths == listOf(":pariktEngine_2_19_0:parikt")) { "Wrong common metadata providers: $paths" }
        }
        logger.lifecycle("PASS rootless KMP common metadata selection")
    }
}
'@
Write-Fixture 'kmpLibrary/build.gradle.kts' $kmpBuild
Write-Fixture 'kmpLibrary/parikt.properties' "pari.version=2.19.0`n"
Write-Fixture 'kmpLibrary/src/commonMain/kotlin/probe/Probe.kt' 'package probe; suspend fun evaluate() = com.elianfabian.parikt.PariKt.eval("2+2")'
Write-Fixture 'kmpLibrary/src/jvmMain/java/probe/JavaProbe.java' 'package probe; public final class JavaProbe { public static Object engine() { return com.elianfabian.parikt.PariKt.INSTANCE; } }'
function Invoke-ProductionCheck([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @()) {
    $log = Join-Path $logs "$Name.log"
    if ($onWindows) {
        $env:PARIKT_ONLY_WRAPPER = $wrapper
        $env:PARIKT_ONLY_FIXTURE = $fixture
        $env:PARIKT_ONLY_SOURCE = $repositoryRoot
        $env:PARIKT_ONLY_LOG = $log
        & $env:ComSpec /d /v:off /s /c ('""%PARIKT_ONLY_WRAPPER%" -p "%PARIKT_ONLY_FIXTURE%" "-PpariktSourceDir=%PARIKT_ONLY_SOURCE%" --no-parallel --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_ONLY_LOG%" 2>&1"')
    } else {
        & bash -c 'log=$1; shift; exec bash "$@" >"$log" 2>&1' -- $log $wrapper -p $fixture "-PpariktSourceDir=$repositoryRoot" --no-parallel --console=plain --warning-mode=all @Tasks
    }
    $code = $LASTEXITCODE
    $output = [IO.File]::ReadAllText($log)
    if (($code -ne 0) -ne $Failure) { throw "Unexpected exit $code for $Name. See $log`n$output" }
    foreach ($fragment in $Expected) {
        if (-not $output.Contains($fragment)) { throw "Missing '$fragment' for $Name. See $log`n$output" }
    }
    foreach ($fragment in ($Absent + @('has been deprecated', '[pari-source] fetching', 'configuring PARI', 'building static GMP'))) {
        if ($output.Contains($fragment)) { throw "Unexpected '$fragment' for $Name. See $log" }
    }
    if ($Failure -and ($output.Contains('[jvm-native]') -or $output.Contains('[pari-source]'))) { throw "Negative case reached native preparation: $log" }
    Write-Host "PASS: $Name ($log)"
}
try {
    $both = @(':appA:run', ':appB:run', ':appA:verifyPariJvmGraph', ':appB:verifyPariJvmGraph')
    $pass19 = 'PASS sharedLogic PARI 2.19.0 arithmetic/factorization; one PariKt class resource'
    $pass15 = 'PASS sharedLogic PARI 2.15.0 arithmetic/factorization; one PariKt class resource'
    Invoke-ProductionCheck 'same-version-rootless' ($both + @('verifyPariJvmIdentity', 'pariktSelections', ':kmpLibrary:jvmJar', ':kmpLibrary:verifyCommonSelection')) @($pass19, 'PASS production JVM identity: 1 providers', 'provider=:pariktEngine_2_19_0', ':unrelated = unselected; no PariKt dependency', ':kmpLibrary:compileJvmMainJava', 'PASS rootless KMP common metadata selection') $false @('> Configure project :pariktAndroidNative', '> Task :parikt:compileKotlinJvm')
    Invoke-ProductionCheck 'reverse-and-cache' @(':appB:run', ':appA:run') @($pass19, 'reusing compiled engine', ':sharedLogic:compileKotlin UP-TO-DATE')
    Invoke-ProductionCheck 'no-default-engine-task' @(':parikt:jvmJar') @('No root PARI default', ':parikt:jvmJar', 'Module overrides are not a default') $true
    Invoke-ProductionCheck 'no-default-helper-task' @(':pariktAndroidNative:buildNativeDependencies') @('No root PARI default', ':pariktAndroidNative:buildNativeDependencies') $true
    Remove-Item -LiteralPath (Join-Path $fixture 'sharedLogic/parikt.properties')
    Invoke-ProductionCheck 'missing-shared-selection' @(':appA:run') @('Missing PariKt selection for :sharedLogic', 'A sibling module') $true
    Write-Fixture 'sharedLogic/parikt.properties' "pari.version=2.19.0`n"
    Write-Fixture 'appB/parikt.properties' ''
    Invoke-ProductionCheck 'invalid-local-selection' @('help') @('Invalid or missing pari.version', ':appB') $true
    Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
    Invoke-ProductionCheck 'explicit-shared-conflict' @(':appB:run') @('PARI requirement conflict', '2.15.0', '2.19.0', 'dependency path: :appB -> :sharedLogic') $true
    Write-Fixture 'appB/parikt.properties' "pari.version=2.19.0`n"
    Write-Fixture 'kmpLibrary/build.gradle.kts' ($kmpBuild + "`nkotlin { js { nodejs() } }`n")
    Invoke-ProductionCheck 'web-target-enabled' @(':kmpLibrary:verifyPariWebGraph') @('web PARI=2.19.0', 'provider=:pariktEngine_2_19_0:parikt')
    Write-Fixture 'kmpLibrary/build.gradle.kts' $kmpBuild
    Copy-Module 'sharedLogic' 'alternateLogic'
    Copy-Module 'appB' 'appC'
    Write-Fixture 'alternateLogic/parikt.properties' "pari.version=2.15.0`n"
    Write-Fixture 'appC/parikt.properties' "pari.version=2.15.0`n"
    $appC = [IO.File]::ReadAllText((Join-Path $fixture 'appB/build.gradle.kts')).Replace(':sharedLogic', ':alternateLogic')
    Write-Fixture 'appC/build.gradle.kts' $appC
    [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":appC`", `":alternateLogic`")`n", $utf8)
    $independent = @(':appA:run', ':appC:run', ':appA:verifyPariJvmGraph', ':appC:verifyPariJvmGraph', 'verifyPariJvmIdentity')
    Invoke-ProductionCheck 'independent-versions-rootless' $independent @($pass19, $pass15, 'PASS production JVM identity: 2 providers', 'provider=:pariktEngine_2_19_0', 'provider=:pariktEngine_2_15_0', 'reusing compiled engine')
    Invoke-ProductionCheck 'ambiguous-output' @('help', '-PjvmNativeDir=build/ambiguous') @('ambiguous with multiple PARI versions') $true
    Write-Fixture 'appC/build.gradle.kts' ($appC + "`ndependencies { implementation(project(`":pariktEngine_2_19_0:parikt`")) }`n")
    Invoke-ProductionCheck 'duplicate-provider' @(':appC:run') @('PARI provider conflict', 'explicit provider') $true
    Write-Fixture 'appC/build.gradle.kts' $appC
    Write-Fixture 'parikt.properties' ''
    Invoke-ProductionCheck 'present-empty-root' @('help') @('Invalid or missing pari.version', 'parikt.properties') $true
    Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
    Invoke-ProductionCheck 'restored-rootless-build' $independent @($pass19, $pass15, 'PASS production JVM identity: 2 providers', 'reusing compiled engine')
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($scratch).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to delete outside $scratch`: $resolved" }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
