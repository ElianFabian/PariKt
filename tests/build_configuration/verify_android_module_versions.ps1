#requires -Version 7.0
param([string]$AndroidSdk, [switch]$Production)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot 'build/android-tests'
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_HOME }
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk) {
    $sdkLine = Get-Content (Join-Path $repositoryRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
    $AndroidSdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$hostDirectory = if ($onWindows) { 'windows-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'linux-x86_64' }
$ndkVersion = Get-PariKtToolVersion -Repository $repositoryRoot -Name 'ndk'
$strip = Join-Path $AndroidSdk "ndk/$ndkVersion/toolchains/llvm/prebuilt/$hostDirectory/bin/llvm-strip$(if ($onWindows) { '.exe' })"
if (-not (Test-Path -LiteralPath $strip)) { throw "Missing pinned NDK strip tool: $strip" }
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
Copy-PariKtFixture -Source $source -Destination $fixture
[IO.File]::WriteAllText((Join-Path $fixture 'local.properties'), "sdk.dir=$($AndroidSdk.Replace('\', '\\').Replace(':', '\:'))`n", $utf8)
if ($Production) {
    foreach ($version in @('2.15.0', '2.19.0')) {
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            $prepared = Join-Path $repositoryRoot "parikt/.native/android-$version/$abi"
            $destination = Join-Path $fixture "build/parikt/native/android-$version/$abi"
            $null = New-Item -ItemType Directory -Path $destination -Force
            Copy-Item -LiteralPath (Join-Path $prepared '.stamp') -Destination $destination
            foreach ($library in @('gmp', 'pari')) {
                $null = New-Item -ItemType Directory -Path (Join-Path $destination "$library/lib") -Force
                Copy-Item -LiteralPath (Join-Path $prepared "$library/lib/lib$library.so") -Destination (Join-Path $destination "$library/lib")
                Copy-Item -LiteralPath (Join-Path $prepared "$library/include") -Destination (Join-Path $destination $library) -Recurse
            }
        }
    }
    [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle.kts'), @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ProductionAndroidVersions"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":androidAppA", ":androidAppB", ":androidSharedLogic")
listOf(":androidAppA", ":androidAppB").forEach { project(it).buildFileName = "../android-app.gradle.kts" }
gradle.projectsEvaluated { rootProject.apply(from = File(settingsDir, "android-verification.gradle.kts")) }
'@, $utf8)
    [IO.File]::WriteAllText((Join-Path $fixture 'build.gradle.kts'), @'
plugins {
    alias(pariktLibs.plugins.android.application) apply false
    alias(pariktLibs.plugins.android.library) apply false
    alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
}
'@, $utf8)
    $app = [IO.File]::ReadAllText((Join-Path $fixture 'android-app.gradle.kts'))
    $app = $app.Replace('extra["parikt.selections"]', 'gradle.extensions.extraProperties["parikt.selections"]')
    $app = $app.Replace('extra["parikt.engineProjects"]', 'gradle.extensions.extraProperties["parikt.engines"]')
    $app = $app.Replace('parikt.proof.androidHelpers', 'parikt.android.helpers')
    $app = $app.Substring(0, $app.IndexOf('tasks.register("verifyAndroidGraph")'))
    $app = $app.Replace('    implementation(project(":parikt"))', '')
    [IO.File]::WriteAllText((Join-Path $fixture 'android-app.gradle.kts'), $app, $utf8)
    $shared = Join-Path $fixture 'androidSharedLogic/build.gradle.kts'
    [IO.File]::WriteAllText($shared, [IO.File]::ReadAllText($shared).Replace('api(project(":parikt"))', 'implementation(project(":parikt"))'), $utf8)
    $verification = Join-Path $fixture 'android-verification.gradle.kts'
    $content = [IO.File]::ReadAllText($verification).Replace('parikt.proof.androidHelpers', 'parikt.android.helpers').Replace('extra["parikt.engineProjects"]', 'gradle.extensions.extraProperties["parikt.engines"]')
    [IO.File]::WriteAllText($verification, $content, $utf8)
}
function Invoke-AndroidProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @(), [string[]]$Any = @()) {
    if ($Production) {
        $Tasks = @($Tasks | ForEach-Object { $_.Replace(':verifyAndroidGraph', ':verifyPariAndroidGraph') })
        if ($Name -in @('duplicate-provider', 'duplicate-helper')) { $Expected = @('2_19_0', '2.15.0') }
    }
    $log = Join-Path $logs ($Name + '.log')
    # Direct file redirection avoids waiting for EOF on compiler-daemon inherited pipes.
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '-PandroidProof', '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure $Failure -Expected $Expected -Absent ($Absent + @('has been deprecated')) -Any $Any
    Write-Host "PASS: $Name ($log)"
}
function Assert-SplitApks([string]$App, [string]$Version) {
    $directory = Join-Path $fixture "$App/build/outputs/apk/debug"
    $apks = @(Get-ChildItem -LiteralPath $directory -Filter '*.apk')
    if ($apks.Count -ne 2) { throw "Expected two split APKs for $App" }
    foreach ($abi in @('arm64-v8a', 'x86_64')) {
        $apk = $apks | Where-Object Name -EQ "$App-$abi-debug.apk"
        if (-not $apk) { throw "Missing $App $abi APK" }
        $zip = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $entries = @($zip.Entries | Where-Object FullName -Like 'lib/*/*.so')
            $expected = @('libgmp.so', 'libpari.so', 'libparidroid.so', 'libc++_shared.so') | ForEach-Object { "lib/$abi/$_" }
            if (@(Compare-Object ($entries.FullName | Sort-Object) ($expected | Sort-Object)).Count) { throw "Wrong or duplicate native library set in $($apk.Name)" }
            if ($entries.FullName.Count -ne 4) { throw "Duplicate native entries in $($apk.Name)" }
            foreach ($library in @('gmp', 'pari')) {
                $raw = Join-Path $repositoryRoot "parikt/.native/android-$Version/$abi/$library/lib/lib$library.so"
                $stripped = Join-Path $runRoot "$Version-$abi-$library.so"
                & $strip --strip-unneeded -o $stripped $raw
                if ($LASTEXITCODE) { throw "Cannot strip $raw" }
                $stream = $zip.GetEntry("lib/$abi/lib$library.so").Open()
                try { $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream)) } finally { $stream.Dispose() }
                if ($hash -ne (Get-FileHash -LiteralPath $stripped).Hash) { throw "Wrong $library engine binary in $($apk.Name)" }
            }
        } finally { $zip.Dispose() }
        Write-Host "PASS: $App $abi exactly one selected PARI $Version/GMP/JNI/runtime set"
    }
}
try {
    $forward = @(':androidAppA:assembleDebug', ':androidAppB:assembleDebug', ':androidAppA:verifyAndroidGraph', ':androidAppB:verifyAndroidGraph')
    $versions = @('Android PARI=2.19.0', 'Android PARI=2.15.0')
    $noBuild = @('[pari-source] fetching', '[native] building')
    Invoke-AndroidProof 'forward-identity-graphs' ($forward + @('verifyAndroidIdentity')) ($versions + @('PASS Android identity: identical classes')) $false $noBuild
    Assert-SplitApks 'androidAppA' '2.19.0'
    Assert-SplitApks 'androidAppB' '2.15.0'
    Invoke-AndroidProof 'reverse-and-cache' @(':androidAppB:assembleDebug', ':androidAppA:assembleDebug', ':androidAppB:verifyAndroidGraph', ':androidAppA:verifyAndroidGraph') ($versions + @(':androidSharedLogic:compileAndroidMain UP-TO-DATE')) $false $noBuild
    $override = Join-Path $fixture 'androidAppB/parikt.properties'
    [IO.File]::WriteAllText($override, "pari.version=2.19.0`n", $utf8)
    $unused = @(':pariktEngine_2_15_0:androidNative:', ':pariktEngine_2_15_0:parikt:compileAndroidMain')
    Invoke-AndroidProof 'same-version-reuse' $forward @($versions[0], ':androidSharedLogic:compileAndroidMain UP-TO-DATE') $false ($noBuild + $unused + @($versions[1]))
    Assert-SplitApks 'androidAppB' '2.19.0'
    Remove-Item -LiteralPath $override
    Invoke-AndroidProof 'override-removal' @(':androidAppB:assembleDebug', ':androidAppB:verifyAndroidGraph') @($versions[0]) $false ($noBuild + $unused)
    [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
    $requirement = Join-Path $fixture 'androidSharedLogic/parikt.properties'
    [IO.File]::WriteAllText($requirement, "pari.version=2.15.0`n", $utf8)
    Invoke-AndroidProof 'explicit-shared-conflict' @(':androidAppA:assembleDebug') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :androidAppA -> :androidSharedLogic', $requirement) $true @('> Task :androidAppA:packageDebug')
    Remove-Item -LiteralPath $requirement
    $appScript = Join-Path $fixture 'android-app.gradle.kts'
    $original = [IO.File]::ReadAllBytes($appScript)
    if ($Production) {
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.15.0`n", $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'androidAppA/parikt.properties'), "pari.version=2.19.0`n", $utf8)
    }
    [IO.File]::AppendAllText($appScript, "`ndependencies { implementation(project(`":pariktEngine_2_19_0:parikt`")) }`n", $utf8)
    Invoke-AndroidProof 'duplicate-provider' @(':androidAppB:assembleDebug') @('2_19_0', '2_15_0') $true @('> Task :androidAppB:packageDebug') @('PARI provider conflict', 'com.elianfabian.parikt:engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    [IO.File]::AppendAllText($appScript, "`ndependencies { implementation(project(`":pariktEngine_2_19_0:androidNative`")) }`n", $utf8)
    Invoke-AndroidProof 'duplicate-helper' @(':androidAppB:assembleDebug') @('2_19_0', '2_15_0') $true @('> Task :androidAppB:packageDebug') @('PARI Android helper conflict', 'com.elianfabian.parikt:android-native-engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    if ($Production) {
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.19.0`n", $utf8)
        Remove-Item -LiteralPath (Join-Path $fixture 'androidAppA/parikt.properties')
    }
    Invoke-AndroidProof 'restored-valid-build' $forward $versions $false $noBuild
    Assert-SplitApks 'androidAppA' '2.19.0'
    Assert-SplitApks 'androidAppB' '2.15.0'
    if ($Production) {
        Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
        foreach ($module in @('androidAppA', 'androidAppB', 'androidSharedLogic')) {
            [IO.File]::WriteAllText((Join-Path $fixture "$module/parikt.properties"), "pari.version=2.19.0`n", $utf8)
        }
        Invoke-AndroidProof 'rootless-shared' $forward @($versions[0]) $false ($noBuild + @($versions[1]))
        Assert-SplitApks 'androidAppA' '2.19.0'
        Assert-SplitApks 'androidAppB' '2.19.0'
        Invoke-AndroidProof 'rootless-default-helper-error' @(':pariktAndroidNative:assembleDebug') @('No root PARI default') $true $noBuild
        $requirement = Join-Path $fixture 'androidSharedLogic/parikt.properties'
        Remove-Item -LiteralPath $requirement
        Invoke-AndroidProof 'rootless-missing-shared' @('help') @('Missing PariKt selection for :androidSharedLogic') $true $noBuild
        [IO.File]::WriteAllText($requirement, "pari.version=2.15.0`n", $utf8)
        Invoke-AndroidProof 'rootless-shared-conflict' @(':androidAppA:assembleDebug') @('PARI requirement conflict', '2.19.0', '2.15.0') $true @('> Task :androidAppA:packageDebug')
        $alternate = Join-Path $fixture 'androidAlternateLogic'
        $null = New-Item -ItemType Directory -Path $alternate -Force
        Copy-Item -LiteralPath (Join-Path $fixture 'androidSharedLogic/src') -Destination $alternate -Recurse
        $sharedScript = [IO.File]::ReadAllText((Join-Path $fixture 'androidSharedLogic/build.gradle.kts')).Replace('proof.shared', 'proof.alternate')
        [IO.File]::WriteAllText((Join-Path $alternate 'build.gradle.kts'), $sharedScript, $utf8)
        [IO.File]::WriteAllText((Join-Path $alternate 'parikt.properties'), "pari.version=2.15.0`n", $utf8)
        [IO.File]::WriteAllText($requirement, "pari.version=2.19.0`n", $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'androidAppB/parikt.properties'), "pari.version=2.15.0`n", $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'android-b.gradle.kts'), [IO.File]::ReadAllText($appScript).Replace(':androidSharedLogic', ':androidAlternateLogic'), $utf8)
        [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":androidAlternateLogic`")`nproject(`":androidAppB`").buildFileName = `"../android-b.gradle.kts`"`n", $utf8)
        Invoke-AndroidProof 'rootless-independent' ($forward + @('verifyAndroidIdentity')) $versions $false $noBuild
        Assert-SplitApks 'androidAppA' '2.19.0'
        Assert-SplitApks 'androidAppB' '2.15.0'
        # Retain final split APKs for emulator verification outside disposable project outputs.
        foreach ($appName in @('androidAppA', 'androidAppB')) {
            $destination = Join-Path $runRoot "apks/$appName"
            $null = New-Item -ItemType Directory -Path $destination -Force
            Copy-Item -Path (Join-Path $fixture "$appName/build/outputs/apk/debug/*.apk") -Destination $destination
        }
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.19.0`n", $utf8)
        foreach ($module in @('androidAppA', 'androidSharedLogic')) { Remove-Item -LiteralPath (Join-Path $fixture "$module/parikt.properties") }
        Invoke-AndroidProof 'root-default-restored' $forward $versions $false $noBuild
    }
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
