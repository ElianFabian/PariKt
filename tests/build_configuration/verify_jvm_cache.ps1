# Windows/WSL proof of one compiled producer across independent work directories.
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This cross-workspace proof currently targets Windows JVM engines built through WSL.'
}
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK with Windows JNI headers.' }
Push-Location $repositoryRoot
try {
    $jniPath = & wsl --exec wslpath -u $env:JAVA_HOME
    if ($LASTEXITCODE -ne 0) { throw 'Cannot translate JAVA_HOME for WSL.' }
    & wsl --exec bash tests/build_configuration/verify_jvm_cache.sh $jniPath
    if ($LASTEXITCODE -ne 0) { throw "JVM cache verification failed with exit $LASTEXITCODE" }
} finally { Pop-Location }
