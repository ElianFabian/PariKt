#requires -Version 7.0
# Cold Linux JVM consumer: Windows uses WSL, Linux uses local Bash.
param([string]$Version = '2.19.0', [string]$GradleExecutable, [string]$ResumeLogDirectory)
$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Select an explicit PARI release version.' }
. (Join-Path $PSScriptRoot 'harness.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$logs = if ($ResumeLogDirectory) { [IO.Path]::GetFullPath($ResumeLogDirectory) } else { Join-Path $repository ('build/cold-jvm/' + [Guid]::NewGuid().ToString('N').Substring(0, 12)) }
$resumeRoot = $null
if ($ResumeLogDirectory) {
    $allowed = [IO.Path]::GetFullPath((Join-Path $repository 'build/cold-jvm')) + [IO.Path]::DirectorySeparatorChar
    if (-not $logs.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase)) { throw 'Resume logs must belong to this repository cold-jvm directory.' }
    if (-not $GradleExecutable) { throw 'Supply the same -GradleExecutable when resuming.' }
    $resumeRoot = (Get-Content -LiteralPath (Join-Path $logs 'linux-root.txt') -Raw).Trim()
    if ($resumeRoot -notmatch '^/[^\r\n]+/\.cache/parikt-verification/cold-jvm\.[A-Za-z0-9]+$') { throw 'Invalid isolated Linux root.' }
}
$null = New-Item -ItemType Directory -Path $logs -Force
$worker = Join-Path $PSScriptRoot 'verify_cold_jvm.sh'
$workerLog = Join-Path $logs $(if ($resumeRoot) { 'worker-resume-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) + '.log' } else { 'worker.log' })
if ($GradleExecutable -and -not (Test-Path -LiteralPath $GradleExecutable -PathType Leaf)) { throw "Missing Gradle executable: $GradleExecutable" }
if ($IsWindows) {
    $paths = @($worker, $repository, $logs)
    if ($GradleExecutable) { $paths += $GradleExecutable }
    $linuxPaths = foreach ($path in $paths) {
        $translated = & wsl --exec wslpath -u $path
        if ($LASTEXITCODE) { throw "Cannot translate $path for WSL" }
        $translated.Trim()
    }
    # WSL's Windows command-line parser needs its --exec switch unquoted. Keep
    # that fixed switch in a launcher while the harness quotes every path argument.
    $launcher = Join-Path $logs 'run-wsl.cmd'
    [IO.File]::WriteAllText($launcher, "@wsl.exe --exec bash %*`r`n", [Text.Encoding]::ASCII)
    $workerArguments = @($linuxPaths[0], $linuxPaths[1], $linuxPaths[2], $Version)
    if ($GradleExecutable) { $workerArguments += $linuxPaths[3] }
    if ($resumeRoot) { $workerArguments += $resumeRoot }
    $result = Invoke-PariKtGradle -Repository $repository -Wrapper $launcher -Log $workerLog -Arguments $workerArguments
} else {
    $workerArguments = @($repository, $logs, $Version)
    if ($GradleExecutable) { $workerArguments += $GradleExecutable }
    if ($resumeRoot) { $workerArguments += $resumeRoot }
    $result = Invoke-PariKtGradle -Repository $repository -Wrapper $worker -Log $workerLog -Arguments $workerArguments
}
Assert-PariKtOutput -Result $result -Log $workerLog -Expected @('PASS cold Linux JVM download/build/runtime', 'PASS warm Linux JVM cache reuse')
Write-Host $result.Output
Write-Host "Verification logs: $logs"
