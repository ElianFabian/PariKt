# Scoped property/discovery checks, without invoking native builders.
# PowerShell 7 and Windows PowerShell 5.1; use a disposable copied consumer build.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
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
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '--console=plain', '--warning-mode=all') + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure $Failure -Expected $Expected -Absent (@('[jvm-native]', '[android-native]', 'has been deprecated', 'compileKotlinJvm') + $Absent)
    if ($output -match '(?m)^:parikt[^ ]* = ') { throw "Internal project leaked into selections. See $log" }
    Write-Host "PASS: $Name ($log)"
}
try {
    $null = New-Item -ItemType Directory -Path $fixture -Force
    Copy-PariKtFixture -Source $source -Destination $fixture
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
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
