# Opt-in Native proof in a disposable fixture; checked-in selections are never edited.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
# Keep extracted interop archive paths below the Windows GNU linker's path limit.
$scratch = Join-Path $repositoryRoot 'build/native-tests'
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
foreach ($file in (Get-ChildItem -LiteralPath $source -Recurse -File)) {
    $relative = $file.FullName.Substring($source.Length).TrimStart('\', '/')
    if ($relative -match '(^|[\\/])(build|\.gradle|\.kotlin)([\\/]|$)') { continue }
    $destination = Join-Path $fixture $relative
    $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
    Copy-Item -LiteralPath $file.FullName -Destination $destination
}
function Invoke-NativeProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @()) {
    $log = Join-Path $logs ($Name + '.log')
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.WorkingDirectory = $repositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    if ($onWindows) {
        $startInfo.FileName = $env:ComSpec
        $startInfo.EnvironmentVariables['PARIKT_NATIVE_WRAPPER'] = $wrapper
        $startInfo.EnvironmentVariables['PARIKT_NATIVE_FIXTURE'] = $fixture
        $startInfo.EnvironmentVariables['PARIKT_NATIVE_SOURCE'] = $repositoryRoot
        $startInfo.EnvironmentVariables['PARIKT_NATIVE_LOG'] = $log
        $startInfo.Arguments = '/d /v:off /s /c ""%PARIKT_NATIVE_WRAPPER%" -p "%PARIKT_NATIVE_FIXTURE%" "-PpariktSourceDir=%PARIKT_NATIVE_SOURCE%" -PnativeProof --no-parallel --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_NATIVE_LOG%" 2>&1"'
    } else {
        $startInfo.FileName = 'bash'
        foreach ($argument in (@('-c', 'log=$1; shift; exec bash "$@" >"$log" 2>&1', '--', $log, $wrapper, '-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '-PnativeProof', '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)) {
            $startInfo.ArgumentList.Add($argument)
        }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try { $null = $process.Start(); $process.WaitForExit(); $code = $process.ExitCode }
    finally { $process.Dispose() }
    $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream, $utf8, $true)
    try { $output = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if (($code -ne 0) -ne $Failure) { throw "Unexpected exit $code for $Name. See $log`n$output" }
    foreach ($fragment in $Expected) {
        if (-not $output.Contains($fragment)) { throw "Missing '$fragment' for $Name. See $log`n$output" }
    }
    foreach ($fragment in ($Absent + @('has been deprecated'))) {
        if ($output.Contains($fragment)) { throw "Unexpected '$fragment' for $Name. See $log`n$output" }
    }
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
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($scratch).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to delete outside $scratch`: $resolved" }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
