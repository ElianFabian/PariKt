#requires -Version 7.0
[CmdletBinding()]
param([Parameter(ValueFromRemainingArguments)][string[]]$GradleArguments)
$ErrorActionPreference='Stop'
$repository=Split-Path $PSScriptRoot -Parent
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
$toolchain=@{}
foreach($line in Get-Content (Join-Path $PSScriptRoot 'toolchain.properties')) {
    if($line -match '^([^#=]+)=(.*)$') { $toolchain[$Matches[1].Trim()]=$Matches[2].Trim() }
}
$version=$toolchain['gradle']
$wrapperRoot=Join-Path $repository "build/publication-wrapper/$version"
$directory=Join-Path $wrapperRoot 'gradle/wrapper'
$null=New-Item -ItemType Directory -Path $directory -Force
$properties=Join-Path $directory 'gradle-wrapper.properties'
if(-not (Test-Path -LiteralPath $properties)) {
    $response=(Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$version-bin.zip.sha256").Content
    $checksum=$(if($response -is [byte[]]){[Text.Encoding]::UTF8.GetString($response)}else{[string]$response}).Trim()
    if($checksum -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid Gradle distribution checksum' }
    [IO.File]::WriteAllText($properties,"distributionUrl=https\://services.gradle.org/distributions/gradle-$version-bin.zip`ndistributionSha256Sum=$checksum`nnetworkTimeout=60000`n")
}
Copy-Item -LiteralPath (Join-Path $repository 'gradle/wrapper/gradle-wrapper.jar') -Destination $directory -Force
foreach($name in @('gradlew','gradlew.bat')) { Copy-Item -LiteralPath (Join-Path $repository $name) -Destination $wrapperRoot -Force }
$local=Join-Path $PSScriptRoot 'local.properties'
if(-not (Test-Path -LiteralPath $local) -and (Test-Path -LiteralPath (Join-Path $repository 'local.properties'))) {
    Copy-Item -LiteralPath (Join-Path $repository 'local.properties') -Destination $local
}
$logRoot=Join-Path $repository 'build/publication-logs'
$null=New-Item -ItemType Directory -Path $logRoot -Force
$log=Join-Path $logRoot ([guid]::NewGuid().ToString('N')+'.log')
$wrapper=Join-Path $wrapperRoot $(if($IsWindows){'gradlew.bat'}else{'gradlew'})
$r=Invoke-PariKtGradle -Repository $PSScriptRoot -Wrapper $wrapper -Log $log -Arguments ($GradleArguments+@('--no-parallel','--console=plain'))
Get-Content -LiteralPath $log -Tail 35
Write-Host "Publication build log: $log"
if($r.ExitCode) { throw "Publication build failed ($($r.ExitCode))" }
