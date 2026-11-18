#requires -Version 7.0
param([Parameter(Mandatory)][string]$ProofDirectory,
    [Parameter(Mandatory)][string]$DeviceSerial,[string]$AndroidSdk=$env:ANDROID_HOME)
$ErrorActionPreference='Stop'
$proof=[IO.Path]::GetFullPath($ProofDirectory)
$adb=Join-Path $AndroidSdk "platform-tools/adb$(if($IsWindows){'.exe'})"
if($DeviceSerial -match '^127\.0\.0\.1:[0-9]+$') {
    $null=& $adb connect $DeviceSerial 2>&1
    if($LASTEXITCODE){throw 'Unable to connect to the owned emulator ADB port.'}
}
function Invoke-ProofAdb([string[]]$Arguments) {
    $output=& $adb -s $DeviceSerial @Arguments 2>&1
    if($LASTEXITCODE){throw "adb failed: $output"}
    return ($output -join "`n")
}
if((Invoke-ProofAdb @('shell','getprop','ro.kernel.qemu')).Trim() -ne '1') { throw 'Use a disposable emulator; this script does not operate on physical devices.' }
if((Invoke-ProofAdb @('shell','getprop','sys.boot_completed')).Trim() -ne '1') { throw 'Boot the selected emulator first.' }
$abi=(Invoke-ProofAdb @('shell','getprop','ro.product.cpu.abi')).Trim()
if($abi -notin @('arm64-v8a','x86_64')){throw "Unsupported engine ABI: $abi"}
foreach($app in @('appA','appB')) {
    $package='proof.maven.'+$app.ToLowerInvariant()
    if((Invoke-ProofAdb @('shell','pm','list','packages',$package)).Contains("package:$package")){throw "Will not replace existing $package; use a fresh emulator."}
    foreach($variant in @('debug','release')) {
        $apk=Join-Path $proof "consumer/$app/build/outputs/apk/$variant/$app-$variant.apk"
        if(!(Test-Path $apk)){throw "Missing built candidate: $apk"}
        $installed=$false
        try {
            $null=Invoke-ProofAdb @('install','--no-streaming',$apk)
            $installed=$true
            $started=(Invoke-ProofAdb @('shell','date','+%s')).Trim()+'.000'
            $null=Invoke-ProofAdb @('shell','am','start','-W','-n',"$package/$package.MainActivity")
            $expected=if($app -eq 'appA'){'PASS Android Maven 135936'}else{'PASS Android Maven 134912'}
            $deadline=[DateTime]::UtcNow.AddSeconds(30)
            do {
                # Keep other apps' logs intact and inspect only this proof's explicit tag.
                $output=Invoke-ProofAdb @('logcat','-d','-T',$started,'-s','PariKtMavenProof:I','*:S')
                if($output.Contains($expected)){break}
                Start-Sleep -Milliseconds 500
            } while([DateTime]::UtcNow -lt $deadline)
            $output | Set-Content (Join-Path $proof "$app-$variant-runtime.log")
            if(!$output.Contains($expected)){throw "No runtime PASS for $app $variant; inspect proof runtime log."}
            Write-Host "PASS $app $variant Maven runtime ($abi)"
        } finally {if($installed){$null=Invoke-ProofAdb @('uninstall',$package)}}
    }
}
