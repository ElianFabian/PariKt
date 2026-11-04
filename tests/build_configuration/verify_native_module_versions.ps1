# Native proof/production acceptance in a disposable fixture; checked-in files are never edited.
param([switch]$Production)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
# Keep extracted interop archive paths below the Windows GNU linker's path limit.
$scratch = Join-Path $repositoryRoot $(if ($Production) { 'build/np' } else { 'build/native-tests' })
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
Copy-PariKtFixture -Source $source -Destination $fixture
if ($Production) {
    # Exercise the actual installer and its consumer-owned cache/output paths. Seed only prepared
    # C artifacts/stamps, so the ordinary builders must validate compatibility without rebuilding.
    foreach ($version in @('2.19.0', '2.15.0')) {
        foreach ($hostTarget in @('linux-x86_64', 'windows-x86_64')) {
            $cache = "native-$version/$hostTarget"
            foreach ($relative in @('.stamp', 'lib/libparikt.a', 'include/bridge.h')) {
                $destination = Join-Path $fixture "build/parikt/native/$cache/$relative"
                $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
                Copy-Item -LiteralPath (Join-Path $repositoryRoot "parikt/.native/$cache/$relative") -Destination $destination
            }
        }
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            foreach ($library in @('gmp', 'pari')) {
                $relative = "android-$version/$abi/$library/lib/lib$library.so"
                $destination = Join-Path $fixture "build/parikt/native/$relative"
                $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
                Copy-Item -LiteralPath (Join-Path $repositoryRoot "parikt/.native/$relative") -Destination $destination
            }
        }
    }
    $sdk = Get-Content (Join-Path $repositoryRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
    [IO.File]::WriteAllLines((Join-Path $fixture 'local.properties'), [string[]]$sdk, $utf8)
    [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle.kts'), @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ProductionNativeVersions"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":nativeAppA", ":nativeAppB", ":nativeSharedLogic")
listOf(":nativeAppA", ":nativeAppB").forEach { project(it).buildFileName = "../native-app.gradle.kts" }
'@, $utf8)
    [IO.File]::WriteAllText((Join-Path $fixture 'build.gradle.kts'), @'
plugins {
    alias(pariktLibs.plugins.android.library) apply false
    alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
}
'@, $utf8)
    $app = [IO.File]::ReadAllText((Join-Path $fixture 'native-app.gradle.kts'))
    $app = $app.Substring(0, $app.IndexOf('tasks.withType<KotlinNativeLink>()'))
    $app = $app.Replace('extra["parikt.selections"]', 'gradle.extensions.extraProperties["parikt.selections"]')
    $app = $app.Replace('extra["parikt.engineProjects"]', 'gradle.extensions.extraProperties["parikt.engines"]')
    $app = $app.Replace('            implementation(project(":parikt"))', '')
    [IO.File]::WriteAllText((Join-Path $fixture 'native-app.gradle.kts'), $app, $utf8)
    $shared = Join-Path $fixture 'nativeSharedLogic/build.gradle.kts'
    [IO.File]::WriteAllText($shared, [IO.File]::ReadAllText($shared).Replace('api(project(":parikt"))', 'implementation(project(":parikt"))'), $utf8)
}
function Invoke-NativeProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @()) {
    $log = Join-Path $logs ($Name + '.log')
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '-PnativeProof', '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure $Failure -Expected $Expected -Absent ($Absent + @('has been deprecated'))
    if ($Failure -and ($output.Contains('[kotlin-native]') -or $output.Contains('[pari-source]'))) {
        throw "Negative case $Name reached native preparation. See $log"
    }
    $script:lastOutput = $output
    Write-Host "PASS: $Name ($log)"
}
try {
    $runtimes = @('PASS native sharedLogic PARI 2.19.0 arithmetic/factorization/error recovery', 'PASS native sharedLogic PARI 2.15.0 arithmetic/factorization/error recovery')
    $forward = @(':nativeAppA:verifyMingwX64', ':nativeAppB:verifyMingwX64', ':nativeAppA:verifyLinuxX64', ':nativeAppB:verifyLinuxX64')
    $reverse = @(':nativeAppB:verifyLinuxX64', ':nativeAppA:verifyLinuxX64', ':nativeAppB:verifyMingwX64', ':nativeAppA:verifyMingwX64')
    if ($Production) {
        $noBuild = @('[pari-source] fetching', 'building static PARI', 'building static GMP')
        $graphs = @(':nativeAppA:verifyPariNativeGraph', ':nativeAppB:verifyPariNativeGraph')
        Invoke-NativeProof 'production-forward-and-identity' ($forward + $graphs + @('verifyPariNativeIdentity')) ($runtimes + @('PASS production Native identity linuxX64', 'PASS production Native identity mingwX64', 'exactly one libparikt.a matching PARI 2.19.0', 'exactly one libparikt.a matching PARI 2.15.0')) $false $noBuild
        Invoke-NativeProof 'production-reverse-and-cache' $reverse ($runtimes + @(':nativeSharedLogic:compileKotlinMingwX64 UP-TO-DATE', ':nativeSharedLogic:compileKotlinLinuxX64 UP-TO-DATE')) $false $noBuild
        $override = Join-Path $fixture 'nativeAppB/parikt.properties'
        [IO.File]::WriteAllText($override, "pari.version=2.19.0`n", $utf8)
        Invoke-NativeProof 'production-same-version-reuse' ($forward + $graphs) @($runtimes[0], 'provider=:parikt') $false ($noBuild + @('pariktEngine_2_15_0'))
        Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
        foreach ($module in @('nativeAppA', 'nativeSharedLogic')) {
            [IO.File]::WriteAllText((Join-Path $fixture "$module/parikt.properties"), "pari.version=2.19.0`n", $utf8)
        }
        Invoke-NativeProof 'production-rootless-shared' ($forward + $graphs + @('verifyPariNativeIdentity')) @($runtimes[0], 'provider=:pariktEngine_2_19_0:parikt') $false $noBuild
        $requirement = Join-Path $fixture 'nativeSharedLogic/parikt.properties'
        Remove-Item -LiteralPath $requirement
        Invoke-NativeProof 'production-rootless-missing-library' @(':nativeAppA:linkDebugExecutableMingwX64') @('Missing PariKt selection for :nativeSharedLogic', $requirement) $true
        [IO.File]::WriteAllText($requirement, "pari.version=2.19.0`n", $utf8)
        [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
        Invoke-NativeProof 'production-shared-conflict' @(':nativeAppB:linkDebugExecutableMingwX64') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :nativeAppB -> :nativeSharedLogic') $true
        foreach ($relative in @('build.gradle.kts', 'src/commonMain/kotlin/proof/SharedNativeLogic.kt')) {
            $destination = Join-Path $fixture "nativeAlternateLogic/$relative"
            $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
            Copy-Item -LiteralPath (Join-Path $source "nativeSharedLogic/$relative") -Destination $destination
        }
        [IO.File]::WriteAllText((Join-Path $fixture 'nativeAlternateLogic/parikt.properties'), "pari.version=2.15.0`n", $utf8)
        $appScript = Join-Path $fixture 'native-app.gradle.kts'
        $original = [IO.File]::ReadAllBytes($appScript)
        $appBScript = Join-Path $fixture 'native-app-b.gradle.kts'
        $appB = [IO.File]::ReadAllText($appScript).Replace(':nativeSharedLogic', ':nativeAlternateLogic')
        [IO.File]::WriteAllText($appBScript, $appB, $utf8)
        [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":nativeAlternateLogic`")`nproject(`":nativeAppB`").buildFileName = `"../native-app-b.gradle.kts`"`n", $utf8)
        Invoke-NativeProof 'production-rootless-independent-versions' ($forward + $graphs + @('verifyPariNativeIdentity')) ($runtimes + @('provider=:pariktEngine_2_19_0:parikt', 'provider=:pariktEngine_2_15_0:parikt')) $false $noBuild
        [IO.File]::AppendAllText($appScript, "`nconfigure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { implementation(project(`":pariktEngine_2_15_0:parikt`")) } }`n", $utf8)
        Invoke-NativeProof 'production-hidden-lower-provider' @(':nativeAppA:linkDebugExecutableMingwX64') @('PARI provider conflict', 'explicit provider', '2_15_0:parikt', '2_19_0:parikt') $true
        [IO.File]::WriteAllBytes($appScript, $original)
        [IO.File]::AppendAllText($appBScript, "`nconfigure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { implementation(project(`":pariktEngine_2_19_0:parikt`")) } }`n", $utf8)
        Invoke-NativeProof 'production-higher-provider' @(':nativeAppB:linkDebugExecutableMingwX64') @('PARI provider conflict', 'explicit provider', '2_19_0:parikt', '2_15_0:parikt') $true
        [IO.File]::WriteAllText($appBScript, $appB, $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.19.0`n", $utf8)
        Invoke-NativeProof 'production-restored-root-default' ($forward + $graphs + @('verifyPariNativeIdentity')) ($runtimes + @('PASS production Native identity', 'provider=:parikt')) $false $noBuild
    } else {
    Invoke-NativeProof 'forward-and-identity' ($forward + @('verifyNativeIdentity', ':nativeAppA:verifyNativeGraph', ':nativeAppB:verifyNativeGraph')) ($runtimes + @('PASS Native identity linuxX64', 'PASS Native identity mingwX64', 'exactly one libparikt.a matching PARI 2.19.0', 'exactly one libparikt.a matching PARI 2.15.0'))
    foreach ($version in @('2.19.0', '2.15.0')) {
        if ([regex]::Matches($script:lastOutput, "PASS native sharedLogic PARI $([regex]::Escape($version)) ").Count -ne 2) { throw "Both hosts were not exercised for $version" }
    }
    $noBuild = @('[pari-source] fetching', 'building static PARI', 'building static GMP')
    Invoke-NativeProof 'reverse-and-cache' $reverse ($runtimes + @(':nativeSharedLogic:compileKotlinMingwX64 UP-TO-DATE', ':nativeSharedLogic:compileKotlinLinuxX64 UP-TO-DATE')) $false $noBuild
    $override = Join-Path $fixture 'nativeAppB/parikt.properties'
    [IO.File]::WriteAllText($override, "pari.version=2.19.0`n", $utf8)
    Invoke-NativeProof 'matching-provider-reuse' $forward @('PASS native sharedLogic PARI 2.19.0', ':nativeSharedLogic:compileKotlinMingwX64 UP-TO-DATE', ':nativeSharedLogic:compileKotlinLinuxX64 UP-TO-DATE') $false ($noBuild + @('PASS native sharedLogic PARI 2.15.0'))
    [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
    $requirement = Join-Path $fixture 'nativeSharedLogic/parikt.properties'
    [IO.File]::WriteAllText($requirement, "pari.version=2.15.0`n", $utf8)
    Invoke-NativeProof 'shared-requirement-conflict' @(':nativeAppA:verifyNativeGraph') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :nativeAppA -> :nativeSharedLogic', $requirement) $true
    Remove-Item -LiteralPath $requirement
    $appScript = Join-Path $fixture 'native-app.gradle.kts'
    $original = [IO.File]::ReadAllBytes($appScript)
    [IO.File]::AppendAllText($appScript, "`nconfigure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { implementation(project(`":pariktEngine_2_19_0:parikt`")) } }`n", $utf8)
    Invoke-NativeProof 'duplicate-provider' @(':nativeAppB:verifyNativeGraph') @('PARI provider conflict', 'explicit provider', ':pariktEngine_2_19_0:parikt', ':pariktEngine_2_15_0:parikt') $true
    [IO.File]::WriteAllBytes($appScript, $original)
    [IO.File]::AppendAllText($appScript, "`nconfigure<KotlinMultiplatformExtension> { sourceSets.commonMain.dependencies { implementation(project(`":pariktEngine_2_15_0:parikt`")) } }`n", $utf8)
    Invoke-NativeProof 'hidden-lower-provider' @(':nativeAppA:verifyNativeGraph') @('PARI provider conflict', 'explicit provider', ':pariktEngine_2_15_0:parikt', ':pariktEngine_2_19_0:parikt') $true
    [IO.File]::WriteAllBytes($appScript, $original)
    Invoke-NativeProof 'restored-valid-build' ($forward + @(':nativeAppA:verifyNativeGraph', ':nativeAppB:verifyNativeGraph')) ($runtimes + @('native graph PARI=2.19.0', 'native graph PARI=2.15.0')) $false $noBuild
    }
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
