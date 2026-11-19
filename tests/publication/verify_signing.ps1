#requires -Version 7.0
param([string]$Gpg)
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if(!$Gpg) {
    $command=Get-Command gpg -ErrorAction SilentlyContinue
    $Gpg=if($command){$command.Source}elseif($IsWindows -and (Test-Path 'C:/Program Files/Git/usr/bin/gpg.exe')){'C:/Program Files/Git/usr/bin/gpg.exe'}else{throw 'Install GnuPG for the local signing proof.'}
}
$keyVariables=@('ORG_GRADLE_PROJECT_signingInMemoryKey','ORG_GRADLE_PROJECT_signingInMemoryKeyPassword','ORG_GRADLE_PROJECT_signingInMemoryKeyId')
foreach($name in $keyVariables){if(Test-Path "Env:$name"){throw 'Run the synthetic signing proof without production signing environment variables.'}}
$gradleHome=Join-Path $repository '.gradle-user-home'
if((Test-Path (Join-Path $gradleHome 'gradle.properties')) -or (Test-Path (Join-Path $gradleHome 'init.d'))){throw 'Synthetic signing requires a workspace Gradle home without user properties or init scripts.'}
$proof=Join-Path $repository ('build/signing-proof/'+[guid]::NewGuid().ToString('N').Substring(0,12))
$signingHome=(Join-Path $proof 'gpg').Replace('\','/')
$gpgSigningHome=$signingHome
$msysGpg=$IsWindows -and $Gpg -match '[\\/]Git[\\/]usr[\\/]bin[\\/]gpg\.exe$'
if($msysGpg) {
    # Git's MSYS GnuPG uses Unix sockets and rejects a drive colon in their names.
    $gpgSigningHome='/'+$signingHome.Substring(0,1).ToLowerInvariant()+$signingHome.Substring(2)
}
function Convert-TestGpgPath([string]$Path) {
    $pathValue=$Path.Replace('\','/')
    if($msysGpg){return '/'+$pathValue.Substring(0,1).ToLowerInvariant()+$pathValue.Substring(2)}
    return $pathValue
}
$null=New-Item -ItemType Directory -Path $signingHome -Force
if(!$IsWindows){& chmod 700 $signingHome}
$previousGradleHome=$env:GRADLE_USER_HOME
try {
    # A one-day synthetic key exercises signing without accessing the user's keyring.
    & $Gpg --homedir $gpgSigningHome --batch --pinentry-mode loopback --passphrase candidate-test-only --quick-generate-key 'PariKt local test <candidate@example.invalid>' ed25519 sign 1d *> (Join-Path $proof 'key-generation.log')
    if($LASTEXITCODE){throw 'Synthetic key generation failed; inspect key-generation.log.'}
    $listing=& $Gpg --homedir $gpgSigningHome --batch --with-colons --list-secret-keys 2>$null
    if($LASTEXITCODE){throw 'Cannot list the synthetic key.'}
    $fingerprint=(($listing | Where-Object {$_ -match '^fpr:'} | Select-Object -First 1) -split ':')[9]
    if($fingerprint -notmatch '^[A-F0-9]{40}$'){throw 'Invalid synthetic fingerprint.'}
    $secret=(& $Gpg --homedir $gpgSigningHome --batch --pinentry-mode loopback --passphrase candidate-test-only --armor --export-secret-keys $fingerprint) -join "`n"
    if($LASTEXITCODE -or !$secret.StartsWith('-----BEGIN PGP PRIVATE KEY BLOCK-----')){throw 'Cannot export the synthetic signing key.'}
    & $Gpg --homedir $gpgSigningHome --batch --armor --output (Convert-TestGpgPath (Join-Path $proof 'public-key.asc')) --export $fingerprint
    if($LASTEXITCODE){throw 'Cannot export synthetic public key.'}
    $env:GRADLE_USER_HOME=$gradleHome
    $env:ORG_GRADLE_PROJECT_signingInMemoryKey=$secret
    $env:ORG_GRADLE_PROJECT_signingInMemoryKeyPassword='candidate-test-only'
    # GitHub supplies an empty string for an optional secret that has not been set.
    [Environment]::SetEnvironmentVariable('ORG_GRADLE_PROJECT_signingInMemoryKeyId','')
    $candidate=Join-Path $proof 'maven'
    & (Join-Path $repository 'publishing/gradle.ps1') ':library:publishJvmPublicationToCandidateRepository' ':library:publishJsPublicationToCandidateRepository' ':engineTools:publishToolsPublicationToCandidateRepository' '-Pparikt.release.signing=true' "-Pparikt.candidateRepository=$candidate"
    $signatures=@(Get-ChildItem $candidate -Recurse -Filter '*.asc')
    if($signatures.Count -lt 14){throw 'Expected signed JVM, JS and engine-tools payloads.'}
    foreach($signature in $signatures) {
        $dataFile=$signature.FullName.Substring(0,$signature.FullName.Length-4)
        & $Gpg --homedir $gpgSigningHome --batch --verify (Convert-TestGpgPath $signature.FullName) (Convert-TestGpgPath $dataFile) *> (Join-Path $proof ($signature.Name+'.verification.log'))
        if($LASTEXITCODE){throw "Invalid signature: $($signature.Name)"}
    }
    Write-Host "PASS $($signatures.Count) Maven signatures verified using an isolated synthetic key; no upload. Evidence: $proof"
} finally {
    $secret=$null
    foreach($name in $keyVariables){Remove-Item "Env:$name" -ErrorAction SilentlyContinue}
    $env:GRADLE_USER_HOME=$previousGradleHome
    $gpgconf=Join-Path (Split-Path $Gpg) $(if($IsWindows){'gpgconf.exe'}else{'gpgconf'})
    if(Test-Path $gpgconf){& $gpgconf --homedir $gpgSigningHome --kill gpg-agent 2>$null}
    $resolved=[IO.Path]::GetFullPath($signingHome)
    if(!$resolved.StartsWith([IO.Path]::GetFullPath($proof)+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe synthetic key cleanup path.'}
    if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
