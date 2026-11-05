[CmdletBinding()]
param([string]$JdkHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Build this package on Windows x64.' }
if (-not $JdkHome) { throw 'Set JAVA_HOME or pass -JdkHome pointing to a JDK with jpackage.' }
$jpackage = Join-Path $JdkHome 'bin/jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) { throw "jpackage is missing: $jpackage" }
$repository = Split-Path $PSScriptRoot -Parent
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JdkHome
    Push-Location $repository
    try {
        & (Join-Path $repository 'gradlew.bat') ':desktopApp:stageWindowsApp' '--no-parallel' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Gradle staging failed ($LASTEXITCODE)." }
    } finally { Pop-Location }

    $packageRoot = Join-Path $PSScriptRoot 'build/windows-package'
    $metadata = @{}
    foreach ($line in [IO.File]::ReadAllLines((Join-Path $packageRoot 'metadata.properties'))) {
        if ($line -match '^([^#!][^=]*)=(.*)$') { $metadata[$Matches[1]] = $Matches[2] }
    }
    foreach ($key in @('pari.version', 'main.jar', 'main.class')) {
        if (-not $metadata[$key]) { throw "Missing package metadata: $key" }
    }
    $inputDirectory = Join-Path $packageRoot 'input'
    $nativeFiles = @(Get-ChildItem -LiteralPath (Join-Path $inputDirectory 'native') -Filter '*.dll')
    if (-not ($nativeFiles | Where-Object Name -eq 'paridroid.dll') -or
        @($nativeFiles | Where-Object { $_.Name -match '^(libpari.*|pari)\.dll$' }).Count -ne 1) {
        throw 'Staging must contain one PARI DLL and the JNI bridge.'
    }
    # Each invocation owns a fresh destination; keep earlier packages intact.
    $destination = Join-Path $packageRoot ('output/' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $destination | Out-Null
    $checkLauncherProperties = Join-Path $packageRoot 'check-launcher.properties'
    [IO.File]::WriteAllText($checkLauncherProperties, "arguments=--check-engine`nwin-console=true`n")
    & $jpackage '--type' 'app-image' '--name' 'PariKt' '--app-version' '1.0.0' `
        '--description' 'PariKt GP Console' '--vendor' 'PariKt' '--input' $inputDirectory `
        '--main-jar' $metadata['main.jar'] '--main-class' $metadata['main.class'] `
        '--dest' $destination '--java-options' '-Djava.library.path=$APPDIR/native' `
        '--add-launcher' ("PariKt-check=" + $checkLauncherProperties) `
        '--java-options' ("-Dparikt.expected.version=" + $metadata['pari.version'])
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)." }
    $image = Join-Path $destination 'PariKt'
    $executable = Join-Path $image 'PariKt.exe'

    # The jpackage runtime omits java.exe; verify through its actual diagnostic launcher.
    # Use only copied JARs/runtime/DLLs from outside the checkout's working directory.
    Push-Location $destination
    try {
        $checkLog = Join-Path $destination 'engine-check.log'
        $check = Start-Process -FilePath (Join-Path $image 'PariKt-check.exe') -WorkingDirectory $destination `
            -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $checkLog `
            -RedirectStandardError (Join-Path $destination 'engine-check-error.log')
        Get-Content -LiteralPath $checkLog
        if ($check.ExitCode -ne 0 -or -not (Select-String -LiteralPath $checkLog -SimpleMatch 'PASS shared console engine')) {
            throw "Bundled runtime engine check failed ($($check.ExitCode)); see $destination."
        }
        $launcher = Start-Process -FilePath $executable -ArgumentList '--check-engine' -WorkingDirectory $destination `
            -WindowStyle Hidden -Wait -PassThru
        if ($launcher.ExitCode -ne 0) { throw "Packaged executable check failed ($($launcher.ExitCode))." }
    } finally { Pop-Location }

    $archive = Join-Path $destination 'PariKt-windows-x64.zip'
    Compress-Archive -LiteralPath $image -DestinationPath $archive
    Write-Host "PARI: $($metadata['pari.version'])"
    Write-Host "Executable: $executable"
    Write-Host "Portable ZIP: $archive"
    Write-Host 'Keep the complete PariKt folder together; the executable requires its app and runtime folders.'
} finally { $env:JAVA_HOME = $previousJavaHome }
