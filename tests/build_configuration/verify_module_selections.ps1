# Scoped property/discovery checks, without invoking native builders.
# PowerShell 7 and Windows PowerShell 5.1; use a disposable copied consumer build.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot 'build/module-selection-tests'
$fixture = Join-Path $scratch ('fixture-' + [Guid]::NewGuid().ToString('N'))
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
function Write-Fixture([string]$Relative, [string]$Content) {
    $path = Join-Path $fixture $Relative
    $null = New-Item -ItemType Directory -Path (Split-Path $path) -Force
    [IO.File]::WriteAllText($path, $Content, $utf8)
}
function Invoke-Selection([string]$Name, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Tasks = @('pariktSelections'), [string[]]$Absent = @()) {
    $log = Join-Path $scratch ($Name + '.log')
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.WorkingDirectory = $repositoryRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    if ($onWindows) {
        $startInfo.FileName = $env:ComSpec
        $startInfo.EnvironmentVariables['PARIKT_SELECTION_WRAPPER'] = $wrapper
        $startInfo.EnvironmentVariables['PARIKT_SELECTION_FIXTURE'] = $fixture
        $startInfo.EnvironmentVariables['PARIKT_SELECTION_SOURCE'] = $repositoryRoot
        $startInfo.EnvironmentVariables['PARIKT_SELECTION_LOG'] = $log
        # Task/option strings are fixed literals below. Quote path expansions.
        $startInfo.Arguments = '/d /v:off /s /c ""%PARIKT_SELECTION_WRAPPER%" -p "%PARIKT_SELECTION_FIXTURE%" "-PpariktSourceDir=%PARIKT_SELECTION_SOURCE%" --console=plain --warning-mode=all ' + ($Tasks -join ' ') + ' > "%PARIKT_SELECTION_LOG%" 2>&1"'
    } else {
        $startInfo.FileName = 'bash'
        foreach ($argument in (@('-c', 'log=$1; shift; exec bash "$@" >"$log" 2>&1', '--', $log, $wrapper, '-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '--console=plain', '--warning-mode=all') + $Tasks)) {
            $startInfo.ArgumentList.Add($argument)
        }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    try {
        $null = $process.Start()
        $process.WaitForExit()
        $code = $process.ExitCode
    } finally { $process.Dispose() }
    $stream = [IO.File]::Open($log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream, $utf8, $true)
    try { $output = $reader.ReadToEnd() } finally { $reader.Dispose() }
    if (($code -ne 0) -ne $Failure) { throw "Unexpected exit $code for $Name. See $log`n$output" }
    foreach ($fragment in $Expected) {
        if (-not $output.Contains($fragment)) { throw "Missing '$fragment' for $Name. See $log`n$output" }
    }
    foreach ($fragment in (@('[jvm-native]', '[android-native]', 'has been deprecated', 'compileKotlinJvm') + $Absent)) {
        if ($output.Contains($fragment)) { throw "Unexpected '$fragment' for $Name. See $log`n$output" }
    }
    if ($output -match '(?m)^:parikt[^ ]* = ') { throw "Internal project leaked into selections. See $log" }
    Write-Host "PASS: $Name ($log)"
}
try {
    $null = New-Item -ItemType Directory -Path $fixture -Force
    foreach ($file in (Get-ChildItem -LiteralPath $source -Recurse -File)) {
        $relative = $file.FullName.Substring($source.Length).TrimStart('\', '/')
        if ($relative -match '(^|[\\/])(build|\.gradle|\.kotlin)([\\/]|$)') { continue }
        Write-Fixture $relative ([IO.File]::ReadAllText($file.FullName))
    }
    $settings = [IO.File]::ReadAllText((Join-Path $fixture 'settings.gradle.kts'))
    Invoke-Selection 'defaults-and-override' @(':appA = 2.19.0; inherited;', ':appB = 2.15.0; explicit;', ':sharedLogic = 2.19.0; inherited;', 'sharedLogic', 'parikt.properties; participating')
    $applyLine = 'apply(from = File(checkout, "gradle/parikt.selections.settings.gradle.kts"))'
    $includeLine = 'include(":appA", ":appB", ":sharedLogic")'
    Write-Fixture 'settings.gradle.kts' ($settings.Replace($applyLine, '').Replace($includeLine, "$includeLine`n$applyLine"))
    Invoke-Selection 'placement-after-includes' @(':appA = 2.19.0; inherited;', ':appB = 2.15.0; explicit;')
    Write-Fixture 'internal-helper/parikt.properties' 'invalid=ignored'
    Write-Fixture 'settings.gradle.kts' ($settings + "`ninclude(`":pariktAndroidNative`")`nproject(`":pariktAndroidNative`").projectDir = file(`"internal-helper`")`n")
    Invoke-Selection 'helper-excluded' @(':appB = 2.15.0; explicit;')
    Write-Fixture 'settings.gradle.kts' $settings
    Invoke-Selection 'default-task' @(':pariktEngine_2_19_0:buildJvmNativeDependencies SKIPPED', ':parikt:buildJvmNativeDependencies SKIPPED') $false @(':parikt:buildJvmNativeDependencies', '--dry-run') @(':pariktEngine_2_15_0:buildJvmNativeDependencies')
    Remove-Item -LiteralPath (Join-Path $fixture 'appB/parikt.properties')
    Invoke-Selection 'override-removal' @(':appB = 2.19.0; inherited;')
    Invoke-Selection 'root-only-reuse' @("Project ':pariktEngine_2_19_0'") $false @('projects') @('pariktEngine_2_15_0')
    Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
    Invoke-Selection 'override-addition' @(':appB = 2.15.0; explicit;')
    Write-Fixture 'appB/parikt.properties' "pari.version=2.19.0`n"
    Invoke-Selection 'override-edit' @(':appB = 2.19.0; explicit;')
    Invoke-Selection 'matching-override-reuse' @("Project ':pariktEngine_2_19_0'") $false @('projects') @('pariktEngine_2_15_0')
    $invalids = @(
        @{ Name = 'empty-override'; Content = ''; Expected = 'Invalid or missing pari.version' }
        @{ Name = 'invalid-override'; Content = 'pari.version=../../bad'; Expected = 'Invalid or missing pari.version' }
        @{ Name = 'unknown-override'; Content = "pari.version=2.15.0`npari.versoin=2.19.0"; Expected = 'Unknown PariKt settings' }
        @{ Name = 'malformed-override'; Content = 'pari.version=\uQQQQ'; Expected = 'Invalid PariKt properties' }
    )
    foreach ($case in $invalids) {
        Write-Fixture 'appB/parikt.properties' $case.Content
        Invoke-Selection $case.Name @($case.Expected, ':appB', (Join-Path $fixture 'appB/parikt.properties')) $true
    }
    Write-Fixture 'appB/parikt.properties' "pari.version=2.15.0`n"
    Write-Fixture 'parikt.properties' 'pari.version=bad'
    Invoke-Selection 'invalid-root' @('Invalid or missing pari.version for : in', (Join-Path $fixture 'parikt.properties')) $true
    Write-Fixture 'parikt.properties' "pari.version=2.19.0`n"
    Invoke-Selection 'legacy' @('pariVersion is no longer supported', 'parikt.properties') $true @('pariktSelections', '-PpariVersion=2.15.0')
    Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
    Invoke-Selection 'missing-participant' @('Missing PariKt selection for :appA', 'appA', 'parikt.properties', "A sibling module's override is not a default") $true
    Write-Fixture 'appA/parikt.properties' "pari.version=2.15.0`n"
    Invoke-Selection 'missing-shared-compilation' @('Missing PariKt selection for :sharedLogic', 'sharedLogic', 'parikt.properties') $true
    foreach ($module in @('appA', 'appB', 'sharedLogic')) { Write-Fixture "$module/parikt.properties" "pari.version=2.15.0`n" }
    Write-Fixture 'unrelated/.keep' ''
    Write-Fixture 'settings.gradle.kts' ($settings + "`ninclude(`":unrelated`")`n")
    Invoke-Selection 'module-only' @(': = unselected; no PariKt dependency', ':appA = 2.15.0; explicit;', ':sharedLogic = 2.15.0; explicit;', ':unrelated = unselected; no PariKt dependency')
    Invoke-Selection 'no-default-task' @('No root PARI default for :parikt:buildJvmNativeDependencies', 'Module overrides are not a default') $true @(':parikt:buildJvmNativeDependencies')
    Invoke-Selection 'no-default-jar' @('No root PARI default for :parikt:jvmJar') $true @(':parikt:jvmJar')

    # Late includes, a nested path, custom projectDir, and a parent override that
    # must not be inherited by either child. Keep the old directories unregistered.
    Write-Fixture 'parikt.properties' "pari.version=2.19.0`n"
    Remove-Item -LiteralPath (Join-Path $fixture 'appA/parikt.properties'), (Join-Path $fixture 'sharedLogic/parikt.properties')
    Write-Fixture 'apps/parikt.properties' "pari.version=2.15.0`n"
    Write-Fixture 'custom/older/parikt.properties' "pari.version=2.15.0`n"
    Write-Fixture 'custom/older/build.gradle.kts' ([IO.File]::ReadAllText((Join-Path $fixture 'appB/build.gradle.kts')))
    Write-Fixture 'custom/plain/build.gradle.kts' ([IO.File]::ReadAllText((Join-Path $fixture 'appB/build.gradle.kts')))
    $nested = $settings.Replace('include(":appA", ":appB", ":sharedLogic")', 'include(":appA", ":sharedLogic")') + @'

include(":apps:olderApp", ":apps:plainApp")
project(":apps:olderApp").projectDir = file("custom/older")
project(":apps:plainApp").projectDir = file("custom/plain")
'@
    Write-Fixture 'settings.gradle.kts' $nested
    Invoke-Selection 'nested-custom-directory' @(':apps = 2.15.0; explicit;', ':apps:olderApp = 2.15.0; explicit;', (Join-Path $fixture 'custom/older/parikt.properties'), ':apps:plainApp = 2.19.0; inherited;')
    Remove-Item -LiteralPath (Join-Path $fixture 'custom/older/parikt.properties')
    Invoke-Selection 'nested-override-removal' @(':apps:olderApp = 2.19.0; inherited;', ':apps:plainApp = 2.19.0; inherited;')
    Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
    Write-Fixture 'appA/parikt.properties' "pari.version=2.15.0`n"
    Write-Fixture 'sharedLogic/parikt.properties' "pari.version=2.15.0`n"
    Invoke-Selection 'parent-is-not-default' @('Missing PariKt selection for :apps:olderApp', (Join-Path $fixture 'custom/older/parikt.properties')) $true

    # No module needs PariKt: root configuration is optional, even with no engines.
    Write-Fixture 'settings.gradle.kts' ($settings.Replace('include(":appA", ":appB", ":sharedLogic")', 'include(":unrelated")'))
    Invoke-Selection 'unrelated-only' @(': = unselected; no PariKt dependency', ':unrelated = unselected; no PariKt dependency')
} finally {
    $resolved = [IO.Path]::GetFullPath($fixture)
    $prefix = [IO.Path]::GetFullPath($scratch).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to delete fixture outside $scratch`: $resolved" }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
