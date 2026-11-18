#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$HostOnly,
    [switch]$AllAppleTargets,
    [switch]$ConfigureOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
if (-not $IsMacOS -and -not $ConfigureOnly) { throw 'Apple compilation/runtime verification requires macOS and Xcode. Use -ConfigureOnly for model checks elsewhere.' }
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$run = Join-Path $repository ('build/apple-tests/' + [guid]::NewGuid().ToString('N').Substring(0,12))
$fixture = Join-Path $run 'consumer'
$null = New-Item -ItemType Directory -Path $fixture -Force
$wrapper = Join-Path $repository $(if ($IsWindows) { 'gradlew.bat' } else { 'gradlew' })
$utf8 = [Text.UTF8Encoding]::new($false)
$armHost = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq 'Arm64'
$hostTarget = if ($armHost) { 'macosArm64' } else { 'macosX64' }
$targets = @('macosX64','macosArm64','iosArm64','iosX64','iosSimulatorArm64',
    'tvosArm64','tvosX64','tvosSimulatorArm64','watchosArm64','watchosDeviceArm64','watchosX64','watchosSimulatorArm64')
$families = if ($HostOnly -and -not $AllAppleTargets -and -not $ConfigureOnly) { 'macos' } else { 'macos,ios,tvos,watchos' }
$results = @()
function Write-Fixture([string]$Path,[string]$Text) {
    $file = Join-Path $fixture $Path
    $null = New-Item -ItemType Directory -Path (Split-Path $file) -Force
    [IO.File]::WriteAllText($file,$Text,$utf8)
}
function Check-Build([string]$Name,[string[]]$Arguments,[string[]]$Expected=@(),[switch]$Failure) {
    $log = Join-Path $run "$Name.log"
    $r = Invoke-PariKtGradle -Repository $fixture -Wrapper $wrapper -Log $log -Arguments (@("-PpariktSourceDir=$repository","-Pparikt.apple.families=$families",'--no-parallel','--console=plain') + $Arguments)
    Assert-PariKtOutput -Result $r -Log $log -Failure $Failure -Expected $Expected
    if ($Failure -and $r.Output.Contains('[apple-native]')) { throw "Conflict reached engine preparation: $log" }
    $script:results += [PSCustomObject]@{Check=$Name;Passed=$true;Log=$log;ConfigureOnly=[bool]$ConfigureOnly}
    $script:results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $run 'results.json')
    Write-Host "PASS $Name ($log)"
}
$kotlinVersion = Get-PariKtToolVersion -Repository $repository -Name 'kotlin'
Write-Fixture 'settings.gradle.kts' @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS); repositories { google(); mavenCentral() } }
rootProject.name = "AppleConsumer"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":shared", ":appA", ":appB")
'@
Write-Fixture 'build.gradle.kts' "plugins { kotlin(`"multiplatform`") version `"$kotlinVersion`" apply false }`n"
Write-Fixture 'gradle.properties' "kotlin.mpp.enableCInteropCommonization=true`norg.gradle.jvmargs=-Xmx2g`n"
Write-Fixture 'parikt.properties' "pari.version=2.19.0`n"
Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
Write-Fixture 'shared/build.gradle.kts' @"
plugins { kotlin("multiplatform") }
kotlin {
    $hostTarget()
    sourceSets.commonMain.dependencies { api(project(":parikt")) }
}
"@
Write-Fixture 'shared/src/commonMain/kotlin/Shared.kt' @'
import com.elianfabian.parikt.*
suspend fun checkShared(expected: String) {
    PariKt.init()
    try {
        check(PariKt.getVersionName() == expected)
        check(PariKt.eval("6*7").trimEnd().endsWith("= 42"))
        check(PariKt.eval("factor(123456)==[2,6;3,1;643,1]").trimEnd().endsWith("= 1"))
    } finally { PariKt.close() }
}
'@
foreach ($app in @('appA','appB')) {
    $expected = if ($app -eq 'appA') { '2.19.0' } else { '2.15.0' }
    Write-Fixture "$app/build.gradle.kts" @"
plugins { kotlin("multiplatform") }
kotlin {
    $hostTarget { binaries.executable { entryPoint = "main" } }
    sourceSets.commonMain.dependencies {
        implementation(project(":shared"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    }
}
"@
    Write-Fixture "$app/src/commonMain/kotlin/Main.kt" @"
import kotlinx.coroutines.runBlocking
fun main() = runBlocking { checkShared("$expected"); println("PASS Apple $app PARI $expected") }
"@
}
if ($ConfigureOnly) {
    # Reproduce KGP's generated cross-project inventory even on Windows, where SwiftPM
    # does not create it. Actual consumer graphs still reject explicit foreign providers.
    [IO.File]::AppendAllText((Join-Path $fixture 'build.gradle.kts'), @'

gradle.projectsEvaluated {
    project(":appA") {
        val inventory = configurations.findByName("swiftPMDependenciesForLockFilesMetadataClasspath")
            ?: configurations.create("swiftPMDependenciesForLockFilesMetadataClasspath") {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes.attribute(org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                    org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.common)
                attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named("kotlin-metadata"))
            }
        dependencies.add(inventory.name, project(":parikt"))
        dependencies.add(inventory.name, project(":pariktEngine_2_15_0:parikt"))
        tasks.register("verifyGeneratedSwiftPMInventory") {
            doLast {
                val paths = inventory.incoming.resolutionResult.allComponents.mapNotNull {
                    (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
                }.filter { it in setOf(":parikt", ":pariktEngine_2_15_0:parikt") }
                check(paths == listOf(":parikt")) { "Wrong SwiftPM inventory engines: $paths" }
                println("PASS generated SwiftPM inventory uses only the consumer-selected provider")
            }
        }
    }
}
'@, $utf8)
}
Check-Build 'model' @('help',':appA:verifyPariNativeGraph',':appB:verifyPariNativeGraph') @(
    "PASS :appA:$hostTarget Native PARI=2.19.0 provider=:parikt",
    "PASS :appB:$hostTarget Native PARI=2.15.0 provider=:pariktEngine_2_15_0:parikt")
try {
    Write-Fixture 'shared/parikt.properties' "pari.version=2.19.0`n"
    Check-Build 'explicit-conflict' @(':appB:verifyPariNativeGraph') @('PARI requirement conflict','2.15.0','2.19.0') -Failure
} finally { Remove-Item -LiteralPath (Join-Path $fixture 'shared/parikt.properties') -ErrorAction SilentlyContinue }
if ($ConfigureOnly) {
    Check-Build 'swiftpm-inventory' @(':appA:verifyGeneratedSwiftPMInventory') @('PASS generated SwiftPM inventory uses only the consumer-selected provider')
    $expectedTasks = @($targets | ForEach-Object {
        $capitalized = $_.Substring(0,1).ToUpperInvariant() + $_.Substring(1)
        "cinteropPari$capitalized"
        "linkDebugFramework$capitalized"
        "linkDebugTest$capitalized"
    })
    Check-Build 'apple-task-model' @(':parikt:tasks','--all') $expectedTasks
    Write-Host "Configuration only: Apple binaries/tests were NOT compiled or run. Evidence: $run"
    return
}
$null = & xcodebuild -version
if ($LASTEXITCODE) { throw 'A selected full Xcode installation is required (xcode-select / DEVELOPER_DIR).' }
$testTargets = @($hostTarget)
$simulatorArguments = @()
if (-not $HostOnly) {
    $devices = (& xcrun simctl list devices available -j) | ConvertFrom-Json
    if ($LASTEXITCODE) { throw 'Unable to list installed Xcode simulator runtimes.' }
    foreach ($family in @('ios','tvos','watchos')) {
        $runtimePattern = "*SimRuntime.$family-*"
        $available = @($devices.devices.PSObject.Properties | Where-Object Name -Like $runtimePattern | Sort-Object Name -Descending | ForEach-Object { $_.Value } | Where-Object isAvailable)
        if (-not $available.Count) { throw "No available $family simulator. Install that runtime in Xcode, or use -HostOnly for macOS-only coverage." }
        $testTargets += if ($armHost) { "${family}SimulatorArm64" } else { "${family}X64" }
        $simulatorArguments += "-Pparikt.apple.$family.simulatorId=$($available[0].udid)"
    }
}
$testTasks = @($testTargets | ForEach-Object { ":parikt:$($_)Test" })
Check-Build 'apple-tests' ($testTasks + $simulatorArguments)
foreach ($target in $testTargets) {
    $reports = Join-Path $fixture "build/parikt/modules/parikt/test-results/$($target)Test"
    $count=0
    foreach ($xml in Get-ChildItem -LiteralPath $reports -Filter '*.xml') {
        [xml]$report=Get-Content -LiteralPath $xml.FullName
        if ([int]$report.testsuite.failures -or [int]$report.testsuite.errors) { throw "Failed Apple report: $($xml.FullName)" }
        $count += [int]$report.testsuite.tests - [int]$report.testsuite.skipped
    }
    if ($count -lt 4) { throw "Expected shared Native and Apple tests on $target, found $count. Skipped/NO-SOURCE is not a pass." }
}
$linkTargets = if ($AllAppleTargets) { $targets } else { $testTargets }
$frameworkTasks = @($linkTargets | ForEach-Object { ':parikt:linkDebugFramework' + $_.Substring(0,1).ToUpperInvariant() + $_.Substring(1) })
Check-Build 'framework-links' $frameworkTasks
if ($AllAppleTargets) {
    $testLinks = @($targets | ForEach-Object { ':parikt:linkDebugTest' + $_.Substring(0,1).ToUpperInvariant() + $_.Substring(1) })
    Check-Build 'all-test-binary-links' $testLinks
}
foreach ($target in $linkTargets) {
    $binary = Join-Path $fixture "build/parikt/modules/parikt/bin/$target/debugFramework/PariKt.framework/PariKt"
    if (-not (Test-Path -LiteralPath $binary)) { throw "Missing linked framework: $binary" }
}
$suffix = $hostTarget.Substring(0,1).ToUpperInvariant() + $hostTarget.Substring(1)
Check-Build 'two-app-links' @((":appA:linkDebugExecutable"+$suffix),(":appB:linkDebugExecutable"+$suffix)) @('exactly one libparikt.a matching PARI 2.19.0','exactly one libparikt.a matching PARI 2.15.0')
foreach ($app in @('appA','appB')) {
    $expected = if ($app -eq 'appA') { '2.19.0' } else { '2.15.0' }
    $binary = Join-Path $fixture "$app/build/bin/$hostTarget/debugExecutable/$app.kexe"
    $output = & $binary 2>&1
    $output | Set-Content (Join-Path $run "$app-runtime.log")
    if ($LASTEXITCODE -or ($output -join "`n") -notlike "*PASS Apple $app PARI $expected*") { throw "Apple runtime proof failed: $app" }
}
Check-Build 'warm-reuse' @((":appA:linkDebugExecutable"+$suffix),(":appB:linkDebugExecutable"+$suffix)) @('artifacts are up to date')
Write-Host "Apple verification passed for $($testTargets -join ', '); framework links: $($linkTargets -join ', '). Evidence: $run"
