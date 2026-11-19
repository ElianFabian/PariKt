#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Bundle,
    [Parameter(Mandatory)][string]$Tag,
    [switch]$UnsignedPreview
)
$ErrorActionPreference = 'Stop'
& "$PSScriptRoot/../build_configuration/verify_release_metadata.ps1" -Release -Tag $Tag
& "$PSScriptRoot/verify_candidate_contents.ps1" -Repository $Repository

$version = $Tag.Substring(1)
$group = 'io.github.elianfabian'
$groupPath = 'io/github/elianfabian'
$root = [IO.Path]::GetFullPath($Repository)
$bundlePath = [IO.Path]::GetFullPath($Bundle)
$nativeTargets = @(
    'linuxx64', 'mingwx64', 'macosx64', 'macosarm64', 'iosarm64', 'iosx64', 'iossimulatorarm64',
    'tvosarm64', 'tvosx64', 'tvossimulatorarm64', 'watchosarm64', 'watchosdevicearm64',
    'watchosx64', 'watchossimulatorarm64'
)
$native = @($nativeTargets | ForEach-Object { "parikt-$_" })
$targets = @('parikt-jvm', 'parikt-android', 'parikt-js', 'parikt-wasmjs') + $native
$components = @('parikt','parikt-engine-tools') + $targets
$rootModulePath = Join-Path $root "$groupPath/parikt/$version/parikt-$version.module"
$rootModule = Get-Content -LiteralPath $rootModulePath -Raw | ConvertFrom-Json
$redirects = @($rootModule.variants | ForEach-Object { $_.'available-at' } | Where-Object { $_ })
$actualTargets = @($redirects.module | Sort-Object -Unique)
if (Compare-Object $targets $actualTargets) {
    throw "Root module must expose every published target. Found: $($actualTargets -join ', ')"
}
foreach ($redirect in $redirects) {
    $expectedUrl = "../../$($redirect.module)/$version/$($redirect.module)-$version.module"
    if ($redirect.group -cne $group -or $redirect.version -cne $version -or $redirect.url -cne $expectedUrl) {
        throw "Invalid root metadata redirect: $($redirect.url)"
    }
}

$payloads = [Collections.Generic.List[IO.FileInfo]]::new()
foreach ($component in $components) {
    $directory = Join-Path $root "$groupPath/$component/$version"
    $stem = "$component-$version"
    $extension = switch ($component) {
        'parikt-android' { 'aar' }
        { $_ -in $native -or $_ -in @('parikt-js','parikt-wasmjs') } { 'klib' }
        default { 'jar' }
    }
    $required = @("$stem.pom", "$stem.$extension", "$stem-sources.jar", "$stem-javadoc.jar")
    if ($component -ne 'parikt-engine-tools') { $required += "$stem.module" }
    if ($component -in $native) { $required += "$stem-cinterop-pari.klib" }
    foreach ($name in $required) {
        $file = Get-Item -LiteralPath (Join-Path $directory $name)
        if ($file.PSIsContainer -or $file.Length -eq 0) { throw "Missing or empty artifact: $name" }
    }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $directory "$stem.pom") -Raw
    if ($pom.project.groupId -cne $group -or $pom.project.artifactId -cne $component -or $pom.project.version -cne $version) {
        throw "Unexpected coordinates in $stem.pom"
    }

    $modulePath = Join-Path $directory "$stem.module"
    if (Test-Path -LiteralPath $modulePath) {
        $module = Get-Content -LiteralPath $modulePath -Raw | ConvertFrom-Json
        foreach ($entry in $module.variants.files | Where-Object { $_ }) {
            if ($entry.url -notmatch '^[A-Za-z0-9_.-]+$' -or !$entry.url.StartsWith($stem)) {
                throw "Invalid artifact URL in $stem.module"
            }
            $file = Get-Item -LiteralPath (Join-Path $directory $entry.url)
            if ($file.Length -ne $entry.size -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $entry.sha256) {
                throw "Artifact does not match module metadata: $($entry.url)"
            }
        }
    }

    foreach ($file in Get-ChildItem -LiteralPath $directory -File) {
        if ($file.Name -match '\.(asc|md5|sha1|sha256|sha512)$') { continue }
        if (!$file.Name.StartsWith("$stem.") -and !$file.Name.StartsWith("$stem-")) {
            throw "Unexpected file in release coordinates: $($file.Name)"
        }
        $payloads.Add($file)
    }
}

$zipInputs = [Collections.Generic.List[IO.FileInfo]]::new()
foreach ($file in $payloads) {
    $zipInputs.Add($file)
    if (!$UnsignedPreview) {
        $signature = Get-Item -LiteralPath "$($file.FullName).asc"
        if (!(Get-Content -LiteralPath $signature.FullName -Raw).StartsWith('-----BEGIN PGP SIGNATURE-----')) {
            throw "Missing armored signature for $($file.Name)"
        }
        $zipInputs.Add($signature)
    }
}
if (Test-Path -LiteralPath $bundlePath) { throw "Bundle output already exists: $bundlePath" }
$null = New-Item -ItemType Directory -Path (Split-Path $bundlePath) -Force
$tempBundle = "$bundlePath.partial"
$zip = [IO.Compression.ZipFile]::Open($tempBundle, 'Create')
try {
    foreach ($file in $zipInputs | Sort-Object FullName) {
        $entryName = [IO.Path]::GetRelativePath($root, $file.FullName).Replace('\','/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $file.FullName, $entryName)
        foreach ($algorithm in @('MD5','SHA1','SHA256','SHA512')) {
            $entry = $zip.CreateEntry("$entryName.$($algorithm.ToLowerInvariant())")
            $writer = [IO.StreamWriter]::new($entry.Open())
            try { $writer.Write((Get-FileHash -LiteralPath $file.FullName -Algorithm $algorithm).Hash.ToLowerInvariant()) }
            finally { $writer.Dispose() }
        }
    }
} finally {
    $zip.Dispose()
}
Move-Item -LiteralPath $tempBundle -Destination $bundlePath
Write-Host "Validated $($components.Count) components and $($payloads.Count) payload files for $Tag. Bundle: $bundlePath"
