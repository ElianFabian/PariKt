# Check consumer configuration diagnostics before any engine is downloaded or built.
# Compatible with Windows PowerShell 5.1 and PowerShell 7 (pwsh).
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$scratchDirectory = Join-Path $repositoryRoot 'build/configuration-tests'
$null = New-Item -ItemType Directory -Path $scratchDirectory -Force
$utf8 = [Text.UTF8Encoding]::new($false)
$kotlinRoot = $repositoryRoot.Replace('\', '/').Replace('$', '\$').Replace('"', '\"')

$cases = @(
    @{ Name = 'missing'; Content = $null; Options = @(); Expected = 'Missing' }
    @{ Name = 'empty'; Content = ''; Options = @(); Expected = 'Invalid or missing pari.version' }
    @{ Name = 'invalid'; Content = "pari.version=../../wrong`n"; Options = @(); Expected = 'Invalid or missing pari.version' }
    @{ Name = 'unknown'; Content = "pari.version=2.19.0`npari.versoin=2.15.0`n"; Options = @(); Expected = 'Unknown PariKt settings' }
    @{ Name = 'legacy'; Content = "pari.version=2.19.0`n"; Options = @('-PpariVersion=2.15.0'); Expected = 'pariVersion is no longer supported' }
)

foreach ($case in $cases) {
    $projectDirectory = Join-Path $scratchDirectory ($case.Name + '-' + [Guid]::NewGuid().ToString('N'))
    $null = New-Item -ItemType Directory -Path $projectDirectory
    try {
        $settings = @"
extra["pariktSourceDir"] = file("$kotlinRoot")
apply(from = file("$kotlinRoot/gradle/parikt.settings.gradle.kts"))
"@
        [IO.File]::WriteAllText((Join-Path $projectDirectory 'settings.gradle.kts'), $settings, $utf8)
        if ($case.Name -eq 'missing') {
            # An unrelated empty root needs no selection. A participating module does.
            [IO.File]::WriteAllText((Join-Path $projectDirectory 'build.gradle.kts'),
                'configurations.create("engine"); dependencies.add("engine", project(":parikt"))', $utf8)
        }
        if ($null -ne $case.Content) {
            [IO.File]::WriteAllText((Join-Path $projectDirectory 'parikt.properties'), $case.Content, $utf8)
        }

        $logPath = Join-Path $scratchDirectory ($case.Name + '.log')
        $gradleArguments = @('-p', $projectDirectory, 'help', '--console=plain') + $case.Options
        $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $logPath -Arguments $gradleArguments
        $gradleExitCode = $result.ExitCode
        $output = $result.Output
        if ($gradleExitCode -eq 0 -or -not $output.Contains($case.Expected)) {
            throw "Unexpected result for $($case.Name). See $logPath`n$output"
        }
        if ($output.Contains('Configure project :pariktAndroidNative')) {
            throw "Case $($case.Name) reached native setup. See $logPath"
        }
        Write-Host "PASS: $($case.Name)"
    } finally {
        Remove-PariKtFixture -Path $projectDirectory -Parent $scratchDirectory
    }
}
