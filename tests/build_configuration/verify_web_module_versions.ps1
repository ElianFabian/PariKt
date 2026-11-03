#requires -Version 7.0
# Disposable JS/Wasm proof. The checked-in fixture and its lockfiles are never edited.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot 'build/web-tests'
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
function Invoke-WebProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @(), [string[]]$Any = @()) {
    $log = Join-Path $logs ($Name + '.log')
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.WorkingDirectory = $repositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    if ($onWindows) {
        $startInfo.FileName = $env:ComSpec
        $startInfo.EnvironmentVariables['PARIKT_WEB_WRAPPER'] = $wrapper
        $startInfo.EnvironmentVariables['PARIKT_WEB_FIXTURE'] = $fixture
        $startInfo.EnvironmentVariables['PARIKT_WEB_SOURCE'] = $repositoryRoot
        $startInfo.EnvironmentVariables['PARIKT_WEB_LOG'] = $log
        $startInfo.Arguments = '/d /v:off /s /c ""%PARIKT_WEB_WRAPPER%" -p "%PARIKT_WEB_FIXTURE%" "-PpariktSourceDir=%PARIKT_WEB_SOURCE%" -PwebProof --no-parallel --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_WEB_LOG%" 2>&1"'
    } else {
        $startInfo.FileName = 'bash'
        foreach ($argument in (@('-c', 'log=$1; shift; exec bash "$@" >"$log" 2>&1', '--', $log, $wrapper, '-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '-PwebProof', '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)) {
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
    if ($Any.Count -gt 0 -and -not @($Any | Where-Object { $output.Contains($_) }).Count) {
        throw "Missing an expected diagnostic for $Name. See $log`n$output"
    }
    if ($Failure -and ($output.Contains('[web-native]') -or $output.Contains('[pari-source]'))) {
        throw "Negative case $Name reached native preparation. See $log"
    }
    $script:lastOutput = $output
    Write-Host "PASS: $Name ($log)"
}
try {
    $upgrade = @('kotlinUpgradePackageLock', 'kotlinWasmUpgradePackageLock')
    $forward = @(':webAppA:jsNodeProductionRun', ':webAppB:jsNodeProductionRun', ':webAppA:wasmJsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun')
    $reverse = @(':webAppB:wasmJsNodeProductionRun', ':webAppA:wasmJsNodeProductionRun', ':webAppB:jsNodeProductionRun', ':webAppA:jsNodeProductionRun')
    $runtimes = @('PASS web sharedLogic PARI 2.19.0 arithmetic/factorization/error recovery', 'PASS web sharedLogic PARI 2.15.0 arithmetic/factorization/error recovery')
    $noBuild = @('[pari-source] fetching', '[web-native] building')
    $unused = @(':pariktEngine_2_15_0:buildWebNativeDependencies', ':pariktEngine_2_15_0:prepareWebEnginePackage')
    Invoke-WebProof 'single-selected-payload' ($upgrade + @(':webAppA:jsNodeProductionRun')) @($runtimes[0]) $false ($noBuild + $unused)
    if (Test-Path -LiteralPath (Join-Path $fixture 'build/parikt/web-engines/2.15.0/parikt-engine.wasm')) { throw 'Unused engine payload was staged' }
    Invoke-WebProof 'forward-identity-npm-bundles' ($forward + @('verifyWebIdentity', 'verifyWebNpm', 'verifyWebBundles', ':webAppA:verifyWebGraph', ':webAppB:verifyWebGraph')) ($runtimes + @('PASS web identity js', 'PASS web identity wasmJs', 'PASS webAppB wasm npm: one @parikt/engine-2-15-0', 'PASS :webAppB wasmJs bundle: exactly one PARI 2.15.0')) $false $noBuild
    foreach ($version in @('2.19.0', '2.15.0')) {
        if ([regex]::Matches($script:lastOutput, "PASS web sharedLogic PARI $([regex]::Escape($version)) ").Count -ne 2) { throw "Both Node targets were not exercised for $version" }
    }
    Invoke-WebProof 'reverse-and-cache' ($reverse + @('verifyWebNpm', 'verifyWebBundles')) ($runtimes + @(':webSharedLogic:compileKotlinJs UP-TO-DATE', ':webSharedLogic:compileKotlinWasmJs UP-TO-DATE')) $false $noBuild
    $override = Join-Path $fixture 'webAppB/parikt.properties'
    [IO.File]::WriteAllText($override, "pari.version=2.19.0`n", $utf8)
    Invoke-WebProof 'same-version-reuse' ($upgrade + $forward + @('verifyWebNpm', 'verifyWebBundles')) @($runtimes[0], ':webSharedLogic:compileKotlinJs UP-TO-DATE', ':webSharedLogic:compileKotlinWasmJs UP-TO-DATE', 'PASS webAppB js npm: one @parikt/engine-2-19-0') $false ($noBuild + $unused + @($runtimes[1]))
    Remove-Item -LiteralPath $override
    Invoke-WebProof 'override-removal' @(':webAppB:jsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun', ':webAppB:verifyWebGraph') @($runtimes[0], 'web graph PARI=2.19.0') $false ($noBuild + $unused)
    [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
    Invoke-WebProof 'override-restored' ($upgrade + @(':webAppB:jsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun', 'verifyWebNpm', 'verifyWebBundles')) @($runtimes[1], 'PASS webAppB js npm: one @parikt/engine-2-15-0') $false $noBuild
    $requirement = Join-Path $fixture 'webSharedLogic/parikt.properties'
    [IO.File]::WriteAllText($requirement, "pari.version=2.15.0`n", $utf8)
    Invoke-WebProof 'explicit-shared-conflict' @(':webAppA:verifyWebGraph') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :webAppA -> :webSharedLogic', $requirement) $true
    Remove-Item -LiteralPath $requirement
    $appScript = Join-Path $fixture 'web-app.gradle.kts'
    $original = [IO.File]::ReadAllBytes($appScript)
    [IO.File]::AppendAllText($appScript, "`nkotlin { sourceSets.commonMain.dependencies { implementation(project(`":pariktEngine_2_19_0`")) } }`n", $utf8)
    Invoke-WebProof 'duplicate-provider' @(':webAppB:verifyWebGraph') @('2_19_0', '2_15_0') $true @() @('PARI provider conflict', 'com.elianfabian.parikt:engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    Invoke-WebProof 'restored-valid-build' ($forward + @('verifyWebNpm', 'verifyWebBundles', ':webAppA:verifyWebGraph', ':webAppB:verifyWebGraph')) ($runtimes + @('web graph PARI=2.19.0', 'web graph PARI=2.15.0')) $false $noBuild
    foreach ($lock in (Get-ChildItem (Join-Path $fixture 'kotlin-js-store') -Filter 'package-lock.json' -Recurse -File)) {
        if ([IO.File]::ReadAllText($lock.FullName) -match '(?i)"resolved"\s*:\s*"(?:file:/|[A-Z]:[\\/]|/home/|/Users/)') { throw "Nonportable npm lock: $($lock.FullName)" }
    }
    Write-Host 'PASS: portable-lockfiles'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($scratch).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to delete outside $scratch`: $resolved" }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
