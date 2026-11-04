# Shared by verification scripts; dot-source without changing the caller's preferences/state.
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
function Invoke-PariKtGradle {
    param([string]$Repository, [string]$Wrapper, [string]$Log, [string[]]$Arguments)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.WorkingDirectory = $Repository
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    if ([Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT) {
        $start.FileName = $env:ComSpec
        # Pass each argument through a quoted environment expansion. Disabling delayed
        # expansion preserves ! in paths; no task/path value is inserted into shell code.
        $values = @($Wrapper) + $Arguments + @($Log)
        $tokens = for ($i = 0; $i -lt $values.Count; $i++) {
            if ($values[$i] -match '["\r\n]') { throw 'Windows verification arguments cannot contain quotes or newlines.' }
            $key = "PARIKT_VERIFY_$i"
            $start.EnvironmentVariables[$key] = $values[$i]
            '"%' + $key + '%"'
        }
        $start.Arguments = '/d /v:off /s /c "' + ($tokens[0..($tokens.Count - 2)] -join ' ') + ' > ' + $tokens[-1] + ' 2>&1"'
    } else {
        $start.FileName = 'bash'
        foreach ($argument in (@('-c', 'log=$1; shift; exec bash "$@" >"$log" 2>&1', '--', $Log, $Wrapper) + $Arguments)) {
            $start.ArgumentList.Add($argument)
        }
    }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try { $null = $process.Start(); $process.WaitForExit(); $code = $process.ExitCode }
    finally { $process.Dispose() }
    # Compiler daemons may retain inherited pipe handles. Read the merged disk log
    # after the client exits, rather than waiting for EOF on redirected process pipes.
    $stream = [IO.File]::Open($Log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = [IO.StreamReader]::new($stream, [Text.UTF8Encoding]::new($false), $true)
    try { $output = $reader.ReadToEnd() } finally { $reader.Dispose() }
    [PSCustomObject]@{ ExitCode = $code; Output = $output }
}

function Assert-PariKtOutput {
    param($Result, [string]$Log, [bool]$Failure = $false, [string[]]$Expected = @(),
        [string[]]$Absent = @(), [string[]]$Any = @(), [switch]$IgnoreCaseAbsent)
    $output = $Result.Output
    if (($Result.ExitCode -ne 0) -ne $Failure) { throw "Unexpected exit $($Result.ExitCode). See $Log`n$output" }
    foreach ($fragment in $Expected) {
        if (-not $output.Contains($fragment)) { throw "Missing '$fragment'. See $Log`n$output" }
    }
    foreach ($fragment in $Absent) {
        $present = if ($IgnoreCaseAbsent) { $output.IndexOf($fragment, [StringComparison]::OrdinalIgnoreCase) -ge 0 } else { $output.Contains($fragment) }
        if ($present) { throw "Unexpected '$fragment'. See $Log`n$output" }
    }
    if ($Any.Count -gt 0 -and -not @($Any | Where-Object { $output.Contains($_) }).Count) {
        throw "Missing an expected diagnostic. See $Log`n$output"
    }
}

function Copy-PariKtFixture {
    param([string]$Source, [string]$Destination)
    foreach ($file in (Get-ChildItem -LiteralPath $Source -Recurse -File)) {
        $relative = $file.FullName.Substring($Source.Length).TrimStart('\', '/')
        if ($relative -match '(^|[\\/])(build|\.gradle|\.kotlin)([\\/]|$)') { continue }
        $target = Join-Path $Destination $relative
        $null = New-Item -ItemType Directory -Path (Split-Path $target) -Force
        Copy-Item -LiteralPath $file.FullName -Destination $target
    }
}

function Remove-PariKtFixture {
    param([string]$Path, [string]$Parent)
    $resolved = [IO.Path]::GetFullPath($Path)
    $prefix = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove fixture outside $Parent`: $resolved"
    }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
