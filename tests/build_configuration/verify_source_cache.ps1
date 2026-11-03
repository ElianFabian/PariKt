# Run the actual Bash source-cache workers, using WSL on Windows.
$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
Push-Location $repositoryRoot
try {
    if ($onWindows) { & wsl bash tests/build_configuration/verify_source_cache.sh }
    else { & bash tests/build_configuration/verify_source_cache.sh }
    if ($LASTEXITCODE -ne 0) { throw "PARI source-cache verification failed with exit $LASTEXITCODE" }
} finally { Pop-Location }
