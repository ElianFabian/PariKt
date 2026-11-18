#requires -Version 7.0
param([string[]]$KotlinVersions=@('2.0.0','2.0.21','2.1.21','2.2.21','2.3.21','2.4.21'),
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*$')][string]$SharedModule='shared',[switch]$ConfigurationCache)
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
$candidate=Join-Path $repository 'publishing/build/candidate-repository'
if(-not (Test-Path $candidate)) { throw 'Build the local Maven candidate first (publishing/README.md).' }
$metadata=@{}
foreach($line in Get-Content (Join-Path $repository 'gradle.metadata.properties')) {
    if($line -match '^([^#=]+)=(.*)$') { $metadata[$Matches[1].Trim()]=$Matches[2].Trim() }
}
$version=$metadata['version']
$options=@('--no-parallel','--console=plain')
if($ConfigurationCache){$options+=@('--configuration-cache','--configuration-cache-problems=fail')}
$proof=Join-Path $repository ('build/maven-consumer/'+[guid]::NewGuid().ToString('N').Substring(0,12))
$null=New-Item -ItemType Directory -Path $proof -Force
# Copy only published Maven artifacts; never use includeBuild, source providers, or local engines.
Copy-Item -LiteralPath $candidate -Destination (Join-Path $proof 'maven') -Recurse
$jar=Get-ChildItem (Join-Path $proof "maven/io/github/elianfabian/parikt-engine-tools/$version") -Filter '*.jar' |
    Where-Object {$_.Name -notmatch '-(sources|javadoc)\.jar$'} | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(!$jar) { throw 'Missing published engine-tools artifact.' }
$tools=Join-Path $proof 'engine-tools'
[IO.Compression.ZipFile]::ExtractToDirectory($jar.FullName,$tools)
if(Get-ChildItem $tools -Recurse -Filter '*.kt') { throw 'Engine tools must not contain Kotlin library sources.' }
function Write-ProofFile([string]$Path,[string]$Text) {
    if($SharedModule -ne 'shared'){$Text=$Text.Replace('project(":shared")',('project(":'+$SharedModule+'")'))}
    $null=New-Item -ItemType Directory -Path (Split-Path $Path) -Force
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
foreach($kotlin in $KotlinVersions) {
    $consumer=Join-Path $proof "consumer-$kotlin"
    Write-ProofFile (Join-Path $consumer 'settings.gradle.kts') @'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
buildscript {
    repositories { maven { url=uri("../maven") } }
    dependencies { classpath("io.github.elianfabian:parikt-engine-tools:__LIBRARY_VERSION__") }
}
dependencyResolutionManagement { repositories { maven { url = uri("../maven") }; mavenCentral() } }
rootProject.name = "ExternalPariKtConsumer"
include(":shared", ":appA", ":appB", ":appC")
apply(from = javaClass.classLoader.getResource("gradle/parikt.bootstrap.settings.gradle.kts")!!)
'@
    $settings=Join-Path $consumer 'settings.gradle.kts'
    $settingsText=[IO.File]::ReadAllText($settings).Replace('__LIBRARY_VERSION__',$version)
    if($SharedModule -ne 'shared') {
        $settingsText=$settingsText.Replace('":shared"',('":'+$SharedModule+'"'))+"`n"+('project(":'+$SharedModule+'").projectDir=file("shared")')
    }
    [IO.File]::WriteAllText($settings,$settingsText,[Text.UTF8Encoding]::new($false))
    Write-ProofFile (Join-Path $consumer 'build.gradle.kts') ('plugins { kotlin("jvm") version "'+$kotlin+'" apply false }')
    Write-ProofFile (Join-Path $consumer 'gradle.properties') "org.gradle.jvmargs=-Xmx2g`n"
    Write-ProofFile (Join-Path $consumer 'parikt.properties') "pari.version=2.19.0`n"
    Write-ProofFile (Join-Path $consumer 'appB/parikt.properties') "pari.version=2.15.0`n"
    Write-ProofFile (Join-Path $consumer 'shared/build.gradle.kts') ('plugins { kotlin("jvm"); `java-library` }; dependencies { api("io.github.elianfabian:parikt:'+$version+'") }')
    Write-ProofFile (Join-Path $consumer 'shared/src/main/kotlin/Shared.kt') @'
import com.elianfabian.parikt.PariKt
suspend fun exercise(expected: String) {
    check(runCatching { PariKt.eval("2+2") }.isFailure)
    PariKt.init()
    try {
        check(PariKt.eval("2+2").substringAfterLast('=').trim() == "4")
        val version = PariKt.eval("version()[1..3]").substringAfterLast('=').filterNot(Char::isWhitespace)
        check(version == expected) { "Expected $expected, received $version" }
        check(PariKt.getSupportedCommands().contains("factor"))
    } finally { PariKt.close() }
    PariKt.init()
    try { check(PariKt.eval("7*6").substringAfterLast('=').trim() == "42") } finally { PariKt.close() }
    println("PASS external Maven engine $expected")
}
'@
    foreach($app in @('appA','appB','appC')) {
        Write-ProofFile (Join-Path $consumer "$app/build.gradle.kts") @'
plugins { kotlin("jvm"); application }
dependencies { implementation(project(":shared")) }
application { mainClass.set("AppKt") }
'@
        $expected=if($app -eq 'appB'){'[2,15,0]'}else{'[2,19,0]'}
        Write-ProofFile (Join-Path $consumer "$app/src/main/kotlin/App.kt") ('suspend fun main() = exercise("'+$expected+'")')
    }
    $wrapper=if($kotlin -match '^2\.[0-3]\.') {
        Join-Path $repository ('build/publication-wrapper/8.8/'+$(if($IsWindows){'gradlew.bat'}else{'gradlew'}))
    } else { Join-Path $repository $(if($IsWindows){'gradlew.bat'}else{'gradlew'}) }
    $log=Join-Path $proof "$kotlin-runtime.log"
    $runtimeTasks=@(':appA:run',':appB:run',':appC:run')
    $result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments ($runtimeTasks+$options)
    Assert-PariKtOutput -Result $result -Log $log -Expected @('PASS external Maven engine [2,19,0]','PASS external Maven engine [2,15,0]')
    if(([regex]::Matches($result.Output,'> Task :preparePariJvm_2_19_0')).Count -ne 1) { throw 'Expected one shared 2.19.0 preparation task.' }
    $engineRoot=Join-Path $consumer 'build/parikt-binary/jvm'
    $before=@{}
    Get-ChildItem $engineRoot -Recurse -File | Where-Object {$_.Extension -in @('.dll','.so')} | ForEach-Object { $before[$_.FullName]=(Get-FileHash $_.FullName).Hash }
    if(!$before.Count) { throw 'No engine binaries provisioned.' }
    $warmLog=Join-Path $proof "$kotlin-reuse.log"
    $warmTasks=if($ConfigurationCache){$runtimeTasks}else{@(':appA:run',':appC:run')}
    $warm=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $warmLog -Arguments ($warmTasks+$options)
    Assert-PariKtOutput -Result $warm -Log $warmLog -Expected @('PASS external Maven engine [2,19,0]','up to date')
    if($ConfigurationCache -and !$warm.Output.Contains('Configuration cache entry reused.')){throw 'Expected strict configuration-cache reuse with the Maven bootstrap.'}
    foreach($file in $before.Keys) { if((Get-FileHash $file).Hash -ne $before[$file]) { throw "Warm run changed $file" } }
    Write-ProofFile (Join-Path $consumer 'shared/parikt.properties') "pari.version=2.15.0`n"
    $conflictLog=Join-Path $proof "$kotlin-conflict.log"
    $conflict=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $conflictLog -Arguments @(':appA:run','--no-parallel','--console=plain')
    Assert-PariKtOutput -Result $conflict -Log $conflictLog -Failure $true -Expected @('PARI requirement conflict',(':'+$SharedModule))
    Remove-Item -LiteralPath (Join-Path $consumer 'shared/parikt.properties')
    Write-Host "PASS Kotlin $kotlin external Maven runtime, independent selections, conflict rejection and reuse"
}
Write-Host "Evidence: $proof"
