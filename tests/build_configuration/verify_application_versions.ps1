# Acceptance checks using the real terminalApp/desktopApp/consoleUi modules.
# Temporary property/build edits are restored byte-for-byte, including on failure.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$logs = Join-Path $repositoryRoot ('build/application-version-tests/' + [Guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
$null = New-Item -ItemType Directory -Path $logs -Force
$desktopFile = Join-Path $repositoryRoot 'desktopApp/parikt.properties'
$sharedFile = Join-Path $repositoryRoot 'consoleUi/parikt.properties'
$terminalBuild = Join-Path $repositoryRoot 'terminalApp/build.gradle.kts'
if (Test-Path -LiteralPath $sharedFile) { throw 'This acceptance suite expects consoleUi to inherit the root default. Preserve your local requirement and run from the documented demo configuration.' }
if (-not ([IO.File]::ReadAllText((Join-Path $repositoryRoot 'parikt.properties')) -match '(?m)^pari\.version=2\.15\.0\s*$')) {
    throw 'This acceptance suite expects the repository root default 2.15.0.'
}
$desktopBytes = [IO.File]::ReadAllBytes($desktopFile)
$terminalBytes = [IO.File]::ReadAllBytes($terminalBuild)
function Invoke-ApplicationCheck([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @()) {
    $log = Join-Path $logs ($Name + '.log')
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.WorkingDirectory = $repositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    if ($onWindows) {
        $startInfo.FileName = $env:ComSpec
        $startInfo.EnvironmentVariables['PARIKT_APP_WRAPPER'] = $wrapper
        $startInfo.EnvironmentVariables['PARIKT_APP_LOG'] = $log
        $startInfo.Arguments = '/d /v:off /s /c ""%PARIKT_APP_WRAPPER%" --no-parallel --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_APP_LOG%" 2>&1"'
    } else {
        $startInfo.FileName = 'bash'
        foreach ($argument in (@('-c', 'log=$1; shift; exec bash "$@" >"$log" 2>&1', '--', $log, $wrapper, '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)) {
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
    if ($Failure -and ($output.Contains('[jvm-native]') -or $output.Contains('[pari-source]'))) {
        throw "Negative case $Name reached native engine preparation. See $log"
    }
    $script:lastOutput = $output
    Write-Host "PASS: $Name ($log)"
}
try {
    $bothVersions = @('PASS shared console engine PARI 2.15.0', 'PASS shared console engine PARI 2.19.0')
    $bothGraphs = @('PASS :terminalApp:runtimeClasspath PARI=2.15.0 provider=:parikt', 'PASS :desktopApp:runtimeClasspath PARI=2.19.0 provider=:pariktEngine_2_19_0')
    $noBuild = @('[pari-source] fetching', 'configuring PARI', 'building static GMP')
    Invoke-ApplicationCheck 'forward' @(':terminalApp:verifyPariEngine', ':desktopApp:verifyPariEngine', ':terminalApp:verifyPariJvmGraph', ':desktopApp:verifyPariJvmGraph', 'verifyPariJvmIdentity', ':desktopApp:test', ':consoleUi:jvmTest') ($bothVersions + $bothGraphs + @('PASS production JVM identity: 2 providers'))
    if ($script:lastOutput.IndexOf('> Task :terminalApp:verifyPariEngine') -gt $script:lastOutput.IndexOf('> Task :desktopApp:verifyPariEngine')) { throw 'Forward app order was not exercised' }
    Invoke-ApplicationCheck 'reverse-and-cache' @(':desktopApp:verifyPariEngine', ':terminalApp:verifyPariEngine') ($bothVersions + @('reusing compiled engine', ':consoleUi:compileKotlinJvm UP-TO-DATE')) $false $noBuild
    if ($script:lastOutput.IndexOf('> Task :desktopApp:verifyPariEngine') -gt $script:lastOutput.IndexOf('> Task :terminalApp:verifyPariEngine')) { throw 'Reverse app order was not exercised' }
    [IO.File]::WriteAllText($desktopFile, "pari.version=2.15.0`n", $utf8)
    Invoke-ApplicationCheck 'same-version-reuse' @('verifyPariJvmIdentity', ':terminalApp:verifyPariEngine', ':desktopApp:verifyPariEngine', ':desktopApp:verifyPariJvmGraph', ':desktopApp:test') @('PASS production JVM identity: 1 providers', 'PASS :desktopApp:runtimeClasspath PARI=2.15.0 provider=:parikt', 'PASS shared console engine PARI 2.15.0') $false ($noBuild + @('pariktEngine_2_19_0'))
    Remove-Item -LiteralPath $desktopFile
    Invoke-ApplicationCheck 'override-removal' @('pariktSelections', ':desktopApp:verifyPariJvmGraph', ':desktopApp:verifyPariEngine') @(':desktopApp = 2.15.0; inherited;', 'PASS :desktopApp:runtimeClasspath PARI=2.15.0 provider=:parikt', 'PASS shared console engine PARI 2.15.0') $false $noBuild
    [IO.File]::WriteAllBytes($desktopFile, $desktopBytes)
    Invoke-ApplicationCheck 'override-restored' @(':desktopApp:verifyPariEngine', ':desktopApp:verifyPariJvmGraph') @('PASS shared console engine PARI 2.19.0', 'PASS :desktopApp:runtimeClasspath PARI=2.19.0 provider=:pariktEngine_2_19_0', 'reusing compiled engine') $false $noBuild
    [IO.File]::WriteAllText($sharedFile, "pari.version=2.15.0`n", $utf8)
    Invoke-ApplicationCheck 'explicit-shared-conflict' @(':desktopApp:verifyPariJvmGraph') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :desktopApp -> :consoleUi', $desktopFile, $sharedFile) $true
    Remove-Item -LiteralPath $sharedFile
    Invoke-ApplicationCheck 'ambiguous-global-output' @('help', '-PjvmNativeDir=build/ambiguous-jvm-output') @('ambiguous with multiple PARI versions') $true
    [IO.File]::WriteAllText($sharedFile, "pari.version=2.19.0`n", $utf8)
    Invoke-ApplicationCheck 'non-jvm-override-gate' @('pariktSelections') @('Only JVM module overrides are enabled', ':consoleUi', $sharedFile) $true
    [IO.File]::WriteAllText($sharedFile, '', $utf8)
    Invoke-ApplicationCheck 'invalid-local-file' @('help') @('Invalid or missing pari.version', ':consoleUi', $sharedFile) $true
    Remove-Item -LiteralPath $sharedFile
    [IO.File]::AppendAllText($terminalBuild, "`ndependencies { implementation(project(`":pariktEngine_2_19_0`")) }`n", $utf8)
    Invoke-ApplicationCheck 'duplicate-provider' @(':terminalApp:verifyPariJvmGraph') @('capability', 'com.elianfabian.parikt:engine') $true
    [IO.File]::WriteAllBytes($terminalBuild, $terminalBytes)
    Invoke-ApplicationCheck 'restored-valid-build' @(':terminalApp:verifyPariEngine', ':desktopApp:verifyPariEngine', ':terminalApp:verifyPariJvmGraph', ':desktopApp:verifyPariJvmGraph') ($bothVersions + $bothGraphs) $false $noBuild
} finally {
    [IO.File]::WriteAllBytes($desktopFile, $desktopBytes)
    [IO.File]::WriteAllBytes($terminalBuild, $terminalBytes)
    if (Test-Path -LiteralPath $sharedFile) { Remove-Item -LiteralPath $sharedFile }
}
