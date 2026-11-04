#requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$ApkDirectory,
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$AndroidSdk
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $AndroidSdk "platform-tools/adb$(if ($IsWindows) { '.exe' })"
function Invoke-Adb([string[]]$Arguments) {
    $output = & $adb -s $DeviceSerial @Arguments 2>&1
    if ($LASTEXITCODE) { throw "adb $Arguments failed: $output" }
    return ($output -join "`n")
}
if ((Invoke-Adb @('shell', 'getprop', 'sys.boot_completed')).Trim() -ne '1') { throw 'The selected emulator must be booted before running this check.' }
$abi = (Invoke-Adb @('shell', 'getprop', 'ro.product.cpu.abi')).Trim()
if ($abi -notin @('arm64-v8a', 'x86_64')) { throw "Unsupported runtime ABI: $abi" }
$cases = @(
    @{ App='androidAppA'; Id='com.elianfabian.parikt.proof.a'; Version='2.19.0' },
    @{ App='androidAppB'; Id='com.elianfabian.parikt.proof.b'; Version='2.15.0' }
)
foreach ($case in $cases) {
    $apk = Join-Path $ApkDirectory "$($case.App)/$($case.App)-$abi-debug.apk"
    if (-not (Test-Path -LiteralPath $apk)) { throw "Missing retained production APK: $apk" }
    # Do not replace an existing installation or its user data.
    if ((Invoke-Adb @('shell', 'pm', 'list', 'packages', $case.Id)).Contains("package:$($case.Id)")) {
        throw "Proof package $($case.Id) is already installed; use a disposable emulator without these proof apps."
    }
    $installed = $false
    try {
        $null = Invoke-Adb @('install', '--no-streaming', $apk)
        $installed = $true
        $null = Invoke-Adb @('logcat', '-c')
        $null = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$($case.Id)/proof.AndroidMainActivity")
        $appProcessId = (Invoke-Adb @('shell', 'pidof', $case.Id)).Trim()
        $expected = "PASS Android PARI $($case.Version) deferred startup/explicit init/reinit/arithmetic/factorization/error recovery/JNI boundaries"
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $output = Invoke-Adb @('logcat', '-d', "--pid=$appProcessId", '*:I')
            if ($output.Contains($expected)) { break }
            if ($output.Contains('FAIL:') -or $output.Contains('Engine verification failed')) { throw $output }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if (-not $output.Contains($expected)) { throw "No runtime success for $($case.App): $output" }
        $beforeInit = $output.IndexOf('Android activity before explicit init')
        $loading = $output.IndexOf('Loading libgmp.so')
        if ($beforeInit -lt 0 -or $loading -le $beforeInit) { throw "JNI loaded before the app requested initialization: $output" }
        Write-Host "$expected ($DeviceSerial, $abi)"
    } finally {
        if ($installed) { $null = Invoke-Adb @('uninstall', $case.Id) }
    }
}
