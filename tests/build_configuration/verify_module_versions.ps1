# JVM proof and module-only runtime check. Never edit checked-in fixture files.
# Compatible with PowerShell 7 and Windows PowerShell 5.1.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot 'build/module-version-tests'
$fixture = Join-Path $scratch ('fixture-' + [Guid]::NewGuid().ToString('N'))
$runLogs = Join-Path $scratch ('logs-' + (Split-Path $fixture -Leaf))
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture -Force
$null = New-Item -ItemType Directory -Path $runLogs -Force
[IO.File]::WriteAllText((Join-Path $scratch 'latest-run.txt'), $runLogs, $utf8)
Copy-PariKtFixture -Source $source -Destination $fixture
function Invoke-Proof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @()) {
    $log = Join-Path $runLogs ($Name + '.log')
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '--console=plain') + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure $Failure -Expected $Expected -Absent $Absent
    $script:lastProofOutput = $output
    if ($Failure -and $output.Contains('[jvm-native]')) { throw "Negative case $Name reached native setup. See $log" }
    Write-Host "PASS: $Name ($log)"
}
try {
    $graphChecks = @(
        'PASS :appA:compileClasspath engine=:pariktEngine_2_19_0',
        'PASS :appA:runtimeClasspath engine=:pariktEngine_2_19_0',
        'PASS :appB:compileClasspath engine=:pariktEngine_2_15_0',
        'PASS :appB:runtimeClasspath engine=:pariktEngine_2_15_0'
    )
    $runChecks = @('PASS sharedLogic PARI 2.19.0 arithmetic/factorization', 'PASS sharedLogic PARI 2.15.0 arithmetic/factorization')
    Invoke-Proof 'forward' @('verifyEngineIdentity', ':sharedLogic:verifyEngineGraph', ':appA:verifyEngineGraph', ':appB:verifyEngineGraph', ':appA:run', ':appB:run') ($graphChecks + $runChecks + @('PASS engine identity: identical JVM classes and Kotlin module metadata across 2 components', 'PASS :sharedLogic:compileClasspath engine=:pariktEngine_2_19_0'))
    Invoke-Proof 'reverse' @(':appB:run', ':appA:run', ':appB:verifyEngineGraph', ':appA:verifyEngineGraph') ($graphChecks + $runChecks + @('artifacts are up to date'))

    # Add a consumer after the engine already exists. Its own Kotlin compilation
    # is necessary; the selected source component and native engine must be reused.
    $appCSource = Join-Path $fixture 'appC/src/main/kotlin/proof'
    $null = New-Item -ItemType Directory -Path $appCSource -Force
    Copy-Item -LiteralPath (Join-Path $fixture 'appA/build.gradle.kts') -Destination (Join-Path $fixture 'appC/build.gradle.kts')
    Copy-Item -LiteralPath (Join-Path $fixture 'appA/src/main/kotlin/proof/Main.kt') -Destination (Join-Path $appCSource 'Main.kt')
    [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":appC`")`n", $utf8)
    Invoke-Proof 'later-module-reuse' @('verifyEngineIdentity', ':appC:verifyEngineGraph', ':appC:run') @(
        'PASS engine identity: identical JVM classes and Kotlin module metadata across 2 components',
        ':pariktEngine_2_19_0:compileKotlinJvm UP-TO-DATE', 'reusing compiled engine',
        'PASS :appC:runtimeClasspath engine=:pariktEngine_2_19_0', 'PASS sharedLogic PARI 2.19.0'
    ) $false @('[pari-source] fetching', 'configuring PARI', 'building static GMP')
    $cacheKey = [regex]::Match($script:lastProofOutput, 'reusing compiled engine ([0-9a-f]+)').Groups[1].Value
    $generationFile = Join-Path $fixture 'build/parikt/native/jvm-2.19.0/windows-x86_64/.engine-generation'
    if (-not $onWindows) { $generationFile = Join-Path $fixture 'build/parikt/native/jvm-2.19.0/linux-x86_64/.engine-generation' }
    $previousGeneration = [IO.File]::ReadAllText($generationFile)
    Invoke-Proof 'force-shared-engine' @(':appA:run', ':appC:run', '-PjvmNativeForce') @(
        '[pari-source] reusing cached pari-2.19.0', 'built ', 'PARI 2.19.0 artifacts', '> Task :appA:run', '> Task :appC:run'
    ) $false @('[pari-source] fetching')
    if ([regex]::Matches($script:lastProofOutput, '> Task :pariktEngine_2_19_0:buildJvmNativeDependencies').Count -ne 1) {
        throw 'Force rebuild did not use exactly one shared native producer'
    }
    $updatedGeneration = [IO.File]::ReadAllText($generationFile)
    if ($previousGeneration -eq $updatedGeneration) { throw 'Force rebuild did not replace the shared engine generation' }
    Invoke-Proof 'after-force-reuse' @(':appC:run', ':appA:run') @("reusing compiled engine $cacheKey", 'PASS sharedLogic PARI 2.19.0') $false @('[pari-source] fetching', 'configuring PARI', 'building static GMP')
    if ([IO.File]::ReadAllText($generationFile) -ne $updatedGeneration) { throw 'Unchanged consumers rebuilt the forced engine again' }

    $requirement = Join-Path $fixture 'sharedLogic/parikt.properties'
    [IO.File]::WriteAllText($requirement, "pari.version=2.19.0`n", $utf8)
    Invoke-Proof 'explicit-compatible' @(':appA:verifyEngineGraph') @('PASS :appA:runtimeClasspath engine=:pariktEngine_2_19_0')
    Invoke-Proof 'explicit-conflict' @(':appB:verifyEngineGraph') @('PARI requirement conflict', '2.15.0', '2.19.0', 'appB', 'sharedLogic', 'dependency path: :appB -> :sharedLogic', 'parikt.properties') $true
    Invoke-Proof 'explicit-runtime-conflict' @(':appB:dependencies', '--configuration', 'runtimeClasspath') @('PARI requirement conflict', 'runtimeClasspath', 'dependency path: :appB -> :sharedLogic') $true
    Remove-Item -LiteralPath $requirement

    # Force an extra internal provider to prove the common capability rejects it,
    # rather than selecting the highest version or packaging duplicate classes.
    $appBuild = Join-Path $fixture 'appB/build.gradle.kts'
    $originalBuild = [IO.File]::ReadAllText($appBuild)
    [IO.File]::AppendAllText($appBuild, "`ndependencies { implementation(project(`":pariktEngine_2_19_0`")) }`n", $utf8)
    Invoke-Proof 'duplicate-provider' @(':appB:verifyEngineGraph') @('capability', 'com.elianfabian.parikt:engine', 'pariktEngine_2_15_0', 'pariktEngine_2_19_0') $true
    [IO.File]::WriteAllText($appBuild, $originalBuild, $utf8)
    Invoke-Proof 'restored' @(':appB:verifyEngineGraph') @('PASS :appB:runtimeClasspath engine=:pariktEngine_2_15_0')

    # With no root default, all participating compilations select explicitly.
    # A shared library's explicit requirement must agree with both applications.
    Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
    foreach ($module in @('appA', 'appB', 'appC', 'sharedLogic')) {
        [IO.File]::WriteAllText((Join-Path $fixture "$module/parikt.properties"), "pari.version=2.15.0`n", $utf8)
    }
    Invoke-Proof 'module-only-runtime' @('verifyEngineIdentity', ':appA:verifyEngineGraph', ':appB:verifyEngineGraph', ':appA:run', ':appB:run') @(
        'PASS engine identity: identical JVM classes and Kotlin module metadata across 1 components',
        'PASS :appA:runtimeClasspath engine=:pariktEngine_2_15_0',
        'PASS :appB:runtimeClasspath engine=:pariktEngine_2_15_0',
        '> Task :appA:run', '> Task :appB:run', 'PASS sharedLogic PARI 2.15.0 arithmetic/factorization'
    )
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
