# Exercise argument fidelity, disk logs, exit/assertion handling and fixture boundaries.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$scratch = Join-Path $repository 'build/harness-tests'
$run = Join-Path $scratch ([Guid]::NewGuid().ToString('N'))
$fixture = Join-Path $run 'fixture with spaces ! & %'
$wrapper = Join-Path $repository $(if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) { 'gradlew.bat' } else { 'gradlew' })
$utf8 = [Text.UTF8Encoding]::new($false)
$null = New-Item -ItemType Directory -Path $fixture -Force
try {
    [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle'), "rootProject.name = 'HarnessProbe'", $utf8)
    [IO.File]::WriteAllText((Join-Path $fixture 'build.gradle'), @'
tasks.register('probe') {
    doLast {
        assert providers.gradleProperty('message').get() == 'spaces ! & %'
        println('PASS argument fidelity')
    }
}
'@, $utf8)
    $message = 'spaces ! & %'
    $log = Join-Path $run 'success with spaces ! & %.log'
    $result = Invoke-PariKtGradle -Repository $repository -Wrapper $wrapper -Log $log -Arguments @('-p', $fixture, 'probe', "-Pmessage=$message", '--console=plain')
    Assert-PariKtOutput -Result $result -Log $log -Expected @('PASS argument fidelity') -Absent @('BUILD FAILED')
    Write-Host 'PASS: argument and log path fidelity'
    $log = Join-Path $run 'expected-failure.log'
    $result = Invoke-PariKtGradle -Repository $repository -Wrapper $wrapper -Log $log -Arguments @('-p', $fixture, 'missingTask', '--console=plain')
    Assert-PariKtOutput -Result $result -Log $log -Failure $true -Expected @('missingTask') -Any @('not found', 'does not exist')
    $rejected = $false
    try { Assert-PariKtOutput -Result $result -Log $log -Failure $true -Absent @('MISSINGTASK') -IgnoreCaseAbsent }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Absent-output assertion failed to reject a diagnostic' }
    Write-Host 'PASS: expected failure and diagnostic assertions'
    $copy = Join-Path $run 'copy'
    Copy-PariKtFixture -Source $fixture -Destination $copy
    if (-not (Test-Path (Join-Path $copy 'build.gradle')) -or (Test-Path (Join-Path $copy '.gradle'))) { throw 'Fixture filtering failed' }
    $rejected = $false
    try { Remove-PariKtFixture -Path $scratch -Parent $run }
    catch { $rejected = $true }
    if (-not $rejected) { throw 'Cleanup accepted a path outside its parent' }
    Remove-PariKtFixture -Path $copy -Parent $run
    if (Test-Path -LiteralPath $copy) { throw 'Fixture cleanup failed' }
    Write-Host 'PASS: fixture copy and cleanup boundaries'
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $run
}
