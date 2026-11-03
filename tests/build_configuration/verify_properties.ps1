# Check consumer configuration diagnostics before any engine is downloaded or built.
# Compatible with Windows PowerShell 5.1 and PowerShell 7 (pwsh).
$ErrorActionPreference = 'Stop'

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
        if ($null -ne $case.Content) {
            [IO.File]::WriteAllText((Join-Path $projectDirectory 'parikt.properties'), $case.Content, $utf8)
        }

        $logPath = Join-Path $scratchDirectory ($case.Name + '.log')
        $gradleArguments = @('-p', $projectDirectory, 'help', '--console=plain') + $case.Options
        # Capture plain merged UTF-8 output, including under Windows PowerShell 5.1,
        # which otherwise formats native stderr as PowerShell error records.
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.WorkingDirectory = $repositoryRoot
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.StandardOutputEncoding = $utf8
        $startInfo.StandardErrorEncoding = $utf8
        if ($onWindows) {
            $startInfo.FileName = $env:ComSpec
            # Keep paths in quoted environment expansions rather than inserting them into
            # command text. Delayed expansion is disabled; options are fixed test literals.
            $startInfo.EnvironmentVariables['PARIKT_TEST_WRAPPER'] = $wrapper
            $startInfo.EnvironmentVariables['PARIKT_TEST_PROJECT'] = $projectDirectory
            $options = $case.Options -join ' '
            $startInfo.Arguments = '/d /v:off /s /c ""%PARIKT_TEST_WRAPPER%" -p "%PARIKT_TEST_PROJECT%" help --console=plain ' + $options + ' 2>&1"'
        } else {
            $startInfo.FileName = 'bash'
            foreach ($argument in (@('-c', 'exec bash "$@" 2>&1', '--', $wrapper) + $gradleArguments)) {
                $startInfo.ArgumentList.Add($argument)
            }
        }
        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        try {
            $null = $process.Start()
            $stdout = $process.StandardOutput.ReadToEndAsync()
            $stderr = $process.StandardError.ReadToEndAsync()
            $process.WaitForExit()
            $gradleExitCode = $process.ExitCode
            $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        } finally {
            $process.Dispose()
        }
        [IO.File]::WriteAllText($logPath, $output, $utf8)
        if ($gradleExitCode -eq 0 -or -not $output.Contains($case.Expected)) {
            throw "Unexpected result for $($case.Name). See $logPath`n$output"
        }
        if ($output.Contains('Configure project :pariktAndroidNative')) {
            throw "Case $($case.Name) reached native setup. See $logPath"
        }
        Write-Host "PASS: $($case.Name)"
    } finally {
        # Validate the absolute path before recursively removing this generated fixture.
        $fixturePath = [IO.Path]::GetFullPath($projectDirectory)
        $scratchPrefix = [IO.Path]::GetFullPath($scratchDirectory).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
        if (-not $fixturePath.StartsWith($scratchPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove fixture outside $scratchDirectory`: $fixturePath"
        }
        Remove-Item -LiteralPath $fixturePath -Recurse -Force
    }
}
