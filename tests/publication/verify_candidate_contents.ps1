#requires -Version 7.0
param([string]$Repository,[switch]$AppleOnly)
$ErrorActionPreference='Stop'
if(!$Repository) { $Repository=Join-Path $PSScriptRoot '../../publishing/build/candidate-repository' }
$Repository=[IO.Path]::GetFullPath($Repository)
$archives=@(Get-ChildItem $Repository -Recurse -File | Where-Object {
    $_.Extension -in @('.jar','.aar','.klib') -and $_.Directory.Name -notlike '*-SNAPSHOT'
})
# Snapshot repositories retain historical builds; inspect the authoritative current
# snapshot entries, just as Maven/Gradle resolve them, rather than obsolete iterations.
foreach($metadata in Get-ChildItem $Repository -Recurse -Filter 'maven-metadata.xml') {
    [xml]$xml=Get-Content $metadata.FullName -Raw
    foreach($entry in $xml.metadata.versioning.snapshotVersions.snapshotVersion) {
        if($entry.extension -notin @('jar','aar','klib')) { continue }
        $classifier=if($entry.classifier){'-'+$entry.classifier}else{''}
        $name=$xml.metadata.artifactId+'-'+$entry.value+$classifier+'.'+$entry.extension
        $path=Join-Path $metadata.DirectoryName $name
        if(-not (Test-Path -LiteralPath $path)) { throw "Missing current snapshot artifact: $path" }
        $archives+=Get-Item -LiteralPath $path
    }
}
if(!$archives.Count) { throw "No candidate artifacts in $Repository" }
$counts=@{Jvm=0;Android=0;Native=0}
foreach($archive in $archives) {
    $zip=[IO.Compression.ZipFile]::OpenRead($archive.FullName)
    try {
        foreach($entry in $zip.Entries) {
            if($entry.FullName -match '\.(?:a|so(?:\.\d+)*|dll|dylib|wasm)$') { throw "Engine binary leaked into $($archive.Name): $($entry.FullName)" }
        }
        if($archive.Name -match '^parikt-jvm-' -and $archive.Name -notmatch '-(?:sources|javadoc)\.jar$') {
            foreach($name in @('com/elianfabian/parikt/PariKt.class','META-INF/parikt.kotlin_module')) {
                if(!$zip.GetEntry($name)) { throw "Missing $name in $($archive.Name)" }
            }
            $counts.Jvm++
        }
        if($archive.Extension -eq '.aar') {
            $rules=$zip.GetEntry('proguard.txt')
            if(!$rules) { throw "Missing consumer JNI rules in $($archive.Name)" }
            $reader=[IO.StreamReader]::new($rules.Open())
            try { $text=$reader.ReadToEnd() } finally { $reader.Dispose() }
            if(!$text.Contains('-keep class com.elianfabian.parikt.PariNative')) { throw 'Missing JNI class/method keep rule.' }
            $counts.Android++
        }
        if($archive.Extension -eq '.klib' -and $archive.DirectoryName -match 'parikt-(linux|mingw|macos|ios|tvos|watchos)') { $counts.Native++ }
    } finally { $zip.Dispose() }
}
foreach($pom in Get-ChildItem $Repository -Recurse -Filter '*.pom') {
    [xml]$xml=Get-Content $pom.FullName -Raw
    foreach($value in @($xml.project.name,$xml.project.description,$xml.project.url,$xml.project.licenses.license.name,$xml.project.developers.developer.id,$xml.project.scm.connection)) {
        if([string]::IsNullOrWhiteSpace([string]$value)) { throw "Incomplete POM: $($pom.FullName)" }
    }
}
if($AppleOnly) {
    if($counts.Native -lt 24){throw 'Expected main and cinterop klibs for all twelve Apple targets.'}
} elseif(!$counts.Jvm -or !$counts.Android) { throw 'Expected JVM JAR and Android AAR candidates.' }
Write-Host "PASS $($archives.Count) candidate archives contain no engine payloads; JVM identities, Android JNI rules and POM fields verified. Native klibs inspected: $($counts.Native)"
