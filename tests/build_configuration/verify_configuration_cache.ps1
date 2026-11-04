#requires -Version 7.0
# Real installer/cache acceptance. Uses prepared compatible JVM engines; never edits demo files.
param([switch]$Platforms)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$scratch = Join-Path $repositoryRoot 'build/configuration-cache-tests'
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$wrapper = Join-Path $repositoryRoot $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
function Write-Fixture([string]$Path, [string]$Content) {
    $file = Join-Path $fixture $Path
    $null = New-Item -ItemType Directory -Path (Split-Path $file) -Force
    [IO.File]::WriteAllText($file, $Content, $utf8)
}
foreach ($module in @('appA', 'appB', 'sharedLogic')) {
    $source = Join-Path $repositoryRoot "examples/module-versions/$module"
    Copy-PariKtFixture -Source $source -Destination (Join-Path $fixture $module)
    Write-Fixture "$module/parikt.properties" "pari.version=2.19.0`n"
}
Write-Fixture 'settings.gradle.kts' @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ConfigurationCacheConsumer"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":appA", ":appB", ":sharedLogic", ":unrelated")
'@
$kotlinRoot = @'
plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false
}
'@
Write-Fixture 'build.gradle.kts' $kotlinRoot
Write-Fixture 'unrelated/build.gradle.kts' @'
plugins { java }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { systemProperty("java.library.path", "unrelated-sentinel") }
tasks.register<JavaExec>("probe") {
    mainClass.set("probe.Unrelated")
    classpath(sourceSets.main.get().runtimeClasspath)
    systemProperty("java.library.path", "unrelated-sentinel")
}
'@
Write-Fixture 'unrelated/src/main/java/probe/Unrelated.java' @'
package probe;
public final class Unrelated {
    public static void main(String[] args) {
        if (!"unrelated-sentinel".equals(System.getProperty("java.library.path"))) throw new AssertionError("Native path replaced");
        if (System.getProperty("parikt.expected.version") != null) throw new AssertionError("Unrelated module received an engine");
        System.out.println("PASS unrelated JVM path and properties preserved");
    }
}
'@
Write-Fixture 'unrelated/src/test/java/probe/UnrelatedTest.java' @'
package probe;
public final class UnrelatedTest {
    @org.junit.Test public void preservesTestProperties() { Unrelated.main(new String[0]); }
}
'@
foreach ($app in @('appA', 'appB')) {
    [IO.File]::AppendAllText((Join-Path $fixture "$app/build.gradle.kts"), @'

@Suppress("UNCHECKED_CAST")
val selected = extra.properties["parikt.selection"] as? Triple<String, String, Boolean>
tasks.named<JavaExec>("run") { selected?.first?.let { args(it) } }
'@, $utf8)
}
$defaultTasks = @(':appA:run', ':appB:run', ':appA:verifyPariJvmGraph', ':appB:verifyPariJvmGraph', 'verifyPariJvmIdentity', 'pariktSelections')
function Invoke-CacheBuild([string]$Name, [string[]]$Expected, [switch]$Reuse, [switch]$Failure, [string[]]$Tasks = $defaultTasks, [string]$Directory = $fixture) {
    $log = Join-Path $logs "$Name.log"
    $flags = @('--configuration-cache', '--configuration-cache-problems=fail', '--no-parallel', '--console=plain', '--warning-mode=all')
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $Directory, "-PpariktSourceDir=$repositoryRoot") + $flags + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure ([bool]$Failure) -Expected $Expected
    if ($Reuse -and -not $output.Contains('Reusing configuration cache.')) { throw "No actual configuration-cache hit in $log`n$output" }
    foreach ($fragment in @('cannot serialize', 'configuration cache entry discarded', '[pari-source] fetching', 'configuring PARI', 'building static GMP', 'has been deprecated')) {
        if ($output.Contains($fragment, [StringComparison]::OrdinalIgnoreCase)) { throw "Unexpected '$fragment' in $log" }
    }
    if ($Failure -and ($output.Contains('[jvm-native]') -or $output.Contains('> Task :appA:run'))) { throw "Invalid graph reached engine preparation/runtime: $log" }
    if ($Name.StartsWith('unrelated-') -and $output.Contains(':buildJvmNativeDependencies')) { throw "Unrelated tasks scheduled native preparation: $log" }
    Write-Host "PASS: $Name ($log)"
}
$pass19 = 'PASS sharedLogic PARI 2.19.0 arithmetic/factorization; one PariKt class resource'
$pass15 = 'PASS sharedLogic PARI 2.15.0 arithmetic/factorization; one PariKt class resource'
try {
    Invoke-CacheBuild 'rootless-store' @($pass19, 'PASS production JVM identity: 1 providers')
    Invoke-CacheBuild 'rootless-reuse' @($pass19) -Reuse
    Invoke-CacheBuild 'rootless-default-task' @('No root PARI default', 'Module overrides are not a default') -Failure -Tasks @(':parikt:jvmJar')
    Invoke-CacheBuild 'rootless-default-task-reuse' @('No root PARI default') -Failure -Reuse -Tasks @(':parikt:jvmJar')
    Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
    Invoke-CacheBuild 'rootless-local-edit-conflict' @('PARI requirement conflict', '2.15.0', '2.19.0') -Failure
    Remove-Item -LiteralPath (Join-Path $fixture 'sharedLogic/parikt.properties')
    Invoke-CacheBuild 'rootless-local-removal-missing' @('Missing PariKt selection for :sharedLogic') -Failure
    foreach ($module in @('appB', 'sharedLogic')) { Write-Fixture "$module/parikt.properties" "pari.version=2.19.0`n" }
    Invoke-CacheBuild 'rootless-restored' @($pass19)
    Invoke-CacheBuild 'rootless-restored-reuse' @($pass19) -Reuse
    # Add a root default, keeping the full default provider's existing AGP/KGP setup.
    Write-Fixture 'build.gradle.kts' ($kotlinRoot.Replace('    alias(pariktLibs.plugins.kotlin.multiplatform)', "    alias(pariktLibs.plugins.android.library) apply false`n    alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false`n    alias(pariktLibs.plugins.kotlin.multiplatform)"))
    if (Test-Path (Join-Path $repositoryRoot 'local.properties')) { Copy-Item -LiteralPath (Join-Path $repositoryRoot 'local.properties') -Destination (Join-Path $fixture 'local.properties') }
    Write-Fixture 'parikt.properties' "pari.version=2.19.0`n"
    foreach ($module in @('appA', 'sharedLogic')) { Remove-Item -LiteralPath (Join-Path $fixture "$module/parikt.properties") }
    Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
    Invoke-CacheBuild 'root-added-two-versions' @($pass19, $pass15)
    Invoke-CacheBuild 'root-default-reuse' @($pass19, $pass15) -Reuse
    Invoke-CacheBuild 'unrelated-root-default' @('PASS unrelated JVM path and properties preserved') -Tasks @(':unrelated:probe', ':unrelated:test')
    Invoke-CacheBuild 'unrelated-root-default-reuse' @('PASS unrelated JVM path and properties preserved') -Tasks @(':unrelated:probe', ':unrelated:test') -Reuse
    Write-Fixture 'appA/parikt.properties' "pari.version=2.15.0`n"
    Invoke-CacheBuild 'local-added' @($pass15, ':appA = 2.15.0; explicit;')
    Write-Fixture 'appA/parikt.properties' "pari.version=2.19.0`n"
    Invoke-CacheBuild 'local-edited' @($pass19, $pass15, ':appA = 2.19.0; explicit;')
    Remove-Item -LiteralPath (Join-Path $fixture 'appA/parikt.properties')
    Invoke-CacheBuild 'local-removed' @($pass19, $pass15, ':appA = 2.19.0; inherited;')
    Write-Fixture 'parikt.properties' "pari.version=2.15.0`n"
    Invoke-CacheBuild 'root-edited' @($pass15, ':appA = 2.15.0; inherited;')
    Write-Fixture 'appB/parikt.properties' "pari.version=2.19.0`n"
    Invoke-CacheBuild 'override-new-version' @($pass15, $pass19)
    Remove-Item -LiteralPath (Join-Path $fixture 'appB/parikt.properties')
    Invoke-CacheBuild 'override-removed' @($pass15, ':appB = 2.15.0; inherited;')
    Write-Fixture 'sharedLogic/parikt.properties' "pari.version=2.19.0`n"
    Invoke-CacheBuild 'explicit-shared-conflict' @('PARI requirement conflict', 'dependency path:', '2.15.0', '2.19.0') -Failure
    Write-Fixture 'appB/parikt.properties' ''
    Invoke-CacheBuild 'present-invalid-local' @('Invalid or missing pari.version', ':appB') -Failure
    Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
    Remove-Item -LiteralPath (Join-Path $fixture 'appB/parikt.properties')
    Invoke-CacheBuild 'root-removed-missing' @('Missing PariKt selection for :appA') -Failure
    foreach ($module in @('appA', 'appB', 'sharedLogic')) { Write-Fixture "$module/parikt.properties" "pari.version=2.19.0`n" }
    Invoke-CacheBuild 'rootless-final' @($pass19)
    Invoke-CacheBuild 'rootless-final-reuse' @($pass19) -Reuse
    if ($Platforms) {
        $tasks = @(':terminalApp:verifyPariEngine', ':desktopApp:verifyPariEngine', ':terminalApp:verifyPariJvmGraph', ':desktopApp:verifyPariJvmGraph', 'verifyPariJvmIdentity', 'verifyPariNativeIdentity', 'pariktSelections', ':nativeTerminalApp:linkDebugExecutableLinuxX64', ':nativeTerminalApp:linkDebugExecutableMingwX64', ':nativeTerminalApp:verifyPariNativeGraph', ':androidApp:assembleDebug', ':androidApp:verifyPariAndroidGraph', ':parikt:jsNodeTest', ':parikt:wasmJsNodeTest', ':webApp:verifyPariWebGraph')
        Invoke-CacheBuild 'platform-store' @('BUILD SUCCESSFUL') -Tasks $tasks -Directory $repositoryRoot
        Invoke-CacheBuild 'platform-reuse' @('BUILD SUCCESSFUL') -Tasks $tasks -Directory $repositoryRoot -Reuse
    }
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
