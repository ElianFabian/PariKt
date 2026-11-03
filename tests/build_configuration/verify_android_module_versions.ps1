#requires -Version 7.0
param([string]$AndroidSdk)
$ErrorActionPreference = 'Stop'
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
$strip = Join-Path $AndroidSdk "ndk/28.2.13676358/toolchains/llvm/prebuilt/$hostDirectory/bin/llvm-strip$(if ($onWindows) { '.exe' })"
if (-not (Test-Path -LiteralPath $strip)) { throw "Missing pinned NDK strip tool: $strip" }
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
foreach ($file in (Get-ChildItem -LiteralPath $source -Recurse -File)) {
    $relative = $file.FullName.Substring($source.Length).TrimStart('\', '/')
    if ($relative -match '(^|[\\/])(build|\.gradle|\.kotlin)([\\/]|$)') { continue }
    $destination = Join-Path $fixture $relative
    $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}
[IO.File]::WriteAllText((Join-Path $fixture 'local.properties'), "sdk.dir=$($AndroidSdk.Replace('\', '\\').Replace(':', '\:'))`n", $utf8)
function Invoke-AndroidProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @(), [string[]]$Any = @()) {
    $log = Join-Path $logs ($Name + '.log')
    # Direct file redirection avoids waiting for EOF on compiler-daemon inherited pipes.
    if ($onWindows) {
        $env:PARIKT_ANDROID_WRAPPER = $wrapper
        $env:PARIKT_ANDROID_FIXTURE = $fixture
        $env:PARIKT_ANDROID_SOURCE = $repositoryRoot
        $env:PARIKT_ANDROID_LOG = $log
        & $env:ComSpec /d /v:off /s /c ('""%PARIKT_ANDROID_WRAPPER%" -p "%PARIKT_ANDROID_FIXTURE%" "-PpariktSourceDir=%PARIKT_ANDROID_SOURCE%" -PandroidProof --no-parallel --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_ANDROID_LOG%" 2>&1"')
    } else {
        & bash -c 'log=$1; shift; exec bash "$@" >"$log" 2>&1' -- $log $wrapper -p $fixture "-PpariktSourceDir=$repositoryRoot" -PandroidProof --no-parallel --console=plain --warning-mode=all @Tasks
    }
    $code = $LASTEXITCODE
    $output = [IO.File]::ReadAllText($log)
    if (($code -ne 0) -ne $Failure) { throw "Unexpected exit $code for $Name. See $log`n$output" }
    foreach ($fragment in $Expected) {
        if (-not $output.Contains($fragment)) { throw "Missing '$fragment' for $Name. See $log`n$output" }
    }
    foreach ($fragment in ($Absent + @('has been deprecated'))) {
        if ($output.Contains($fragment)) { throw "Unexpected '$fragment' for $Name. See $log" }
    }
    if ($Any.Count -gt 0 -and -not @($Any | Where-Object { $output.Contains($_) }).Count) { throw "Missing diagnostic for $Name. See $log" }
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
    [IO.File]::AppendAllText($appScript, "`ndependencies { implementation(project(`":pariktEngine_2_19_0:parikt`")) }`n", $utf8)
    Invoke-AndroidProof 'duplicate-provider' @(':androidAppB:assembleDebug') @('2_19_0', '2_15_0') $true @('> Task :androidAppB:packageDebug') @('PARI provider conflict', 'com.elianfabian.parikt:engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    [IO.File]::AppendAllText($appScript, "`ndependencies { implementation(project(`":pariktEngine_2_19_0:androidNative`")) }`n", $utf8)
    Invoke-AndroidProof 'duplicate-helper' @(':androidAppB:assembleDebug') @('2_19_0', '2_15_0') $true @('> Task :androidAppB:packageDebug') @('PARI Android helper conflict', 'com.elianfabian.parikt:android-native-engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    Invoke-AndroidProof 'restored-valid-build' $forward $versions $false $noBuild
    Assert-SplitApks 'androidAppA' '2.19.0'
    Assert-SplitApks 'androidAppB' '2.15.0'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($scratch).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to delete outside $scratch`: $resolved" }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
