#requires -Version 7.0
param([Parameter(Mandatory)][string]$ArtifactDirectory)
$ErrorActionPreference='Stop'
if(-not $IsWindows) { throw 'Run this verification on Windows x64.' }
$directory=[IO.Path]::GetFullPath($ArtifactDirectory)
$version=[IO.File]::ReadAllText((Join-Path $directory 'pari-version.txt')).Trim()
if($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid staged engine version' }
$native=Join-Path $directory 'native'
$classpath=(Join-Path $directory 'classes') + [IO.Path]::PathSeparator + (Join-Path $directory 'lib/*')
foreach($scenario in @('commands','quit','empty','lifecycle','initialization','native-validation')) {
    $result=& java "-Djava.library.path=$native" -cp $classpath com.elianfabian.parikt.JniBoundaryProbe $scenario $version 2>&1
    $result | Set-Content (Join-Path $directory "jni-$scenario.log")
    if($LASTEXITCODE -or ($result -join "`n") -notlike "*PASS JNI $version $scenario*") { throw "Windows JNI scenario failed: $scenario" }
}
$result=& (Join-Path $directory 'test.exe') 2>&1
$result | Set-Content (Join-Path $directory 'native-tests.log')
if($LASTEXITCODE -or ($result -join "`n") -notmatch '\[\s*PASSED\s*\] 2 tests') { throw 'Windows Native boundary/lifecycle tests failed or did not run.' }
Write-Host "PASS actual Windows JVM/JNI and Native tests for PARI $version"
