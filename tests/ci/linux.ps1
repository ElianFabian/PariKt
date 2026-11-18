#requires -Version 7.0
param([Parameter(Mandatory)][string]$WindowsJniHeaders)
$ErrorActionPreference='Stop'
if(-not $IsLinux) { throw 'Run this build stage on Linux x86_64.' }
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
$output=Join-Path $repository 'build/ci/windows-runtime'
$null=New-Item -ItemType Directory -Path $output -Force
$version=((Get-Content (Join-Path $repository 'parikt.properties') | Where-Object {$_ -match '^pari.version='}) -split '=',2)[1].Trim()
$sdk=$env:ANDROID_HOME
if(!$sdk) { throw 'ANDROID_HOME must name the configured Android SDK.' }
$local=Join-Path $repository 'local.properties'
if(-not (Test-Path $local)) { [IO.File]::WriteAllText($local,"sdk.dir=$sdk`n") }
$ndk=Get-PariKtToolVersion -Repository $repository -Name 'ndk'
& sdkmanager 'platforms;android-36' 'build-tools;36.0.0' "ndk;$ndk"
if($LASTEXITCODE) { throw 'Android SDK/NDK setup failed.' }
$tasks=@(':parikt:jvmTest',':parikt:verifyLinuxX64',':parikt:linkDebugTestMingwX64',
    ':parikt:jsNodeTest',':parikt:wasmJsNodeTest',':parikt:jsBrowserTest',':parikt:wasmJsBrowserTest',
    ':desktopApp:test',':consoleUi:jvmTest',':androidApp:assembleDebug',':terminalApp:installDist')
$log=Join-Path $repository 'build/ci/linux.log'
$result=Invoke-PariKtGradle -Repository $repository -Wrapper (Join-Path $repository 'gradlew') -Log $log -Arguments ($tasks+@('-PnativeRunner=local','--no-parallel','--console=plain'))
if($result.ExitCode) { throw "Linux verification failed: $log`n$($result.Output)" }
# The Windows job supplies its JDK's public JNI headers. No WSL installation is required there.
& bash (Join-Path $repository 'parikt/native/build-jvm.sh') --pari-version $version --target windows-x86_64 --out (Join-Path $output 'native') --jni-home $WindowsJniHeaders
if($LASTEXITCODE) { throw 'Windows JNI cross-compilation failed.' }
Copy-Item -LiteralPath (Join-Path $repository 'parikt/build/bin/mingwX64/debugTest/test.exe') -Destination (Join-Path $output 'test.exe')
Copy-Item -LiteralPath (Join-Path $repository 'terminalApp/build/install/terminalApp/lib') -Destination (Join-Path $output 'lib') -Recurse
Copy-Item -LiteralPath (Join-Path $repository 'parikt/build/classes/kotlin/jvm/test') -Destination (Join-Path $output 'classes') -Recurse
[IO.File]::WriteAllText((Join-Path $output 'pari-version.txt'),$version)
Write-Host "PASS Linux checks; staged Windows runtime verification for PARI $version"
