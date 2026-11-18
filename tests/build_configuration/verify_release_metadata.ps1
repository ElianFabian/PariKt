#requires -Version 7.0
[CmdletBinding()]
param([switch]$Release, [string]$Tag)
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$metadata=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $repository 'gradle.metadata.properties'))) {
    if($line -match '^\s*([^#!\s][^=]*)=(.*)$') { $metadata[$Matches[1].Trim()]=$Matches[2].Trim() }
}
$required=@('group','artifactId','version','POM_NAME','POM_DESCRIPTION','POM_URL','POM_INCEPTION_YEAR',
    'POM_LICENSE_NAME','POM_LICENSE_URL','POM_LICENSE_DIST','POM_SCM_URL','POM_SCM_CONNECTION',
    'POM_SCM_DEV_CONNECTION','POM_DEVELOPER_ID','POM_DEVELOPER_NAME','POM_DEVELOPER_URL')
foreach($key in $required) { if([string]::IsNullOrWhiteSpace($metadata[$key])) { throw "Missing publication metadata: $key" } }
if($metadata.group -notmatch '^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$') { throw 'Invalid Maven group' }
if($metadata.artifactId -notmatch '^[a-z][a-z0-9-]*$') { throw 'Invalid artifactId' }
if($metadata.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid library version' }
foreach($key in @('POM_URL','POM_LICENSE_URL','POM_SCM_URL','POM_DEVELOPER_URL')) {
    if($metadata[$key] -notmatch '^https://\S+$') { throw "Expected HTTPS metadata: $key" }
}
if(-not (Test-Path -LiteralPath (Join-Path $repository 'LICENSE'))) { throw 'Missing root LICENSE' }
$tracked=& git -C $repository ls-files
if($LASTEXITCODE) { throw 'Cannot inspect tracked release files' }
$binaries=@($tracked | Where-Object { $_ -match '(?i)\.(dll|dylib|a|lib|klib|wasm|exe)$|\.so(?:\.\d+)*$' -or
    ($_ -match '(?i)\.jar$' -and $_ -ne 'gradle/wrapper/gradle-wrapper.jar') })
if($binaries.Count) { throw "Generated binaries must not be tracked by Git: $($binaries -join ', ')" }
if($Release) {
    if($metadata.version.EndsWith('-SNAPSHOT')) { throw 'A release must not use a SNAPSHOT version' }
    if(!$Tag -or $Tag -cne "v$($metadata.version)") { throw 'Release tag must exactly match v<metadata version>' }
}
Write-Host "PASS publication metadata $($metadata.group):$($metadata.artifactId):$($metadata.version)"
Write-Host 'PASS no engine/build binaries tracked by Git (public Gradle wrapper JAR excepted)'
Write-Host 'Metadata validation does not prove binary publication readiness; see RELEASE_PLAN.md.'
