#requires -Version 7.0
param([string[]]$KotlinVersions=@('2.0.0','2.4.21'))
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
if(!$IsWindows -and !$IsLinux -and !$IsMacOS) { throw 'Unsupported Native consumer host.' }
$target=if($IsWindows){'mingwX64'}elseif($IsLinux){'linuxX64'}elseif((& uname -m).Trim() -eq 'arm64'){'macosArm64'}else{'macosX64'}
$suffix=$target.Substring(0,1).ToUpperInvariant()+$target.Substring(1)
$proof=Join-Path $repository ('build/maven-native-consumer/'+[guid]::NewGuid().ToString('N').Substring(0,12))
$null=New-Item -ItemType Directory -Path $proof -Force
Copy-Item -LiteralPath (Join-Path $repository 'publishing/build/candidate-repository') -Destination (Join-Path $proof 'maven') -Recurse
$version=((Get-Content (Join-Path $repository 'gradle.metadata.properties') | Where-Object {$_ -match '^version='}) -split '=',2)[1].Trim()
$jar=Get-ChildItem (Join-Path $proof "maven/io/github/elianfabian/parikt-engine-tools/$version") -Filter '*.jar' |
    Where-Object {$_.Name -notmatch '-(sources|javadoc)\.jar$'} | Sort-Object LastWriteTime -Descending | Select-Object -First 1
[IO.Compression.ZipFile]::ExtractToDirectory($jar.FullName,(Join-Path $proof 'tools'))
if(Get-ChildItem (Join-Path $proof 'tools') -Recurse -Filter '*.kt') { throw 'Unexpected Kotlin library sources in engine tools.' }
function Write-NativeProof([string]$Path,[string]$Text) {
    $Text=$Text.Replace('__LIBRARY_VERSION__',$version)
    $null=New-Item -ItemType Directory -Path (Split-Path $Path) -Force
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
foreach($kotlin in $KotlinVersions) {
    $consumer=Join-Path $proof "consumer-$kotlin"
    Write-NativeProof (Join-Path $consumer 'settings.gradle.kts') @'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
buildscript { repositories { maven { url=uri("../maven") } }; dependencies { classpath("io.github.elianfabian:parikt-engine-tools:__LIBRARY_VERSION__") } }
dependencyResolutionManagement { repositories { maven { url=uri("../maven") }; mavenCentral() } }
rootProject.name="ExternalNativeConsumer"
include(":shared",":appA",":appB")
apply(from=javaClass.classLoader.getResource("gradle/parikt.bootstrap.settings.gradle.kts")!!)
'@
    Write-NativeProof (Join-Path $consumer 'build.gradle.kts') ('plugins { kotlin("multiplatform") version "'+$kotlin+'" apply false }')
    Write-NativeProof (Join-Path $consumer 'gradle.properties') "org.gradle.jvmargs=-Xmx2g`n"
    Write-NativeProof (Join-Path $consumer 'parikt.properties') "pari.version=2.19.0`n"
    Write-NativeProof (Join-Path $consumer 'appB/parikt.properties') "pari.version=2.15.0`n"
    Write-NativeProof (Join-Path $consumer 'shared/build.gradle.kts') ('plugins { kotlin("multiplatform") }; kotlin { '+$target+'(); sourceSets { commonMain.dependencies { api("io.github.elianfabian:parikt:'+$version+'"); api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") } } }')
    Write-NativeProof (Join-Path $consumer 'shared/src/commonMain/kotlin/Shared.kt') @'
import com.elianfabian.parikt.PariKt
suspend fun exercise(expected: Int) {
    check(runCatching { PariKt.eval("2+2") }.isFailure)
    PariKt.init()
    try {
        check(PariKt.getVersionCode() == expected)
        check(PariKt.eval("2+2").substringAfterLast('=').trim()=="4")
        check(PariKt.getSupportedCommands().contains("factor"))
    } finally { PariKt.close() }
    PariKt.init()
    try { check(PariKt.eval("7*6").substringAfterLast('=').trim()=="42") } finally { PariKt.close() }
    println("PASS Native Maven $expected")
}
'@
    foreach($app in @('appA','appB')) {
        Write-NativeProof (Join-Path $consumer "$app/build.gradle.kts") ('plugins { kotlin("multiplatform") }; kotlin { '+$target+' { binaries { executable { entryPoint="main" } } }; sourceSets { commonMain.dependencies { implementation(project(":shared")) } } }')
        $expected=if($app -eq 'appA'){135936}else{134912}
        Write-NativeProof (Join-Path $consumer "$app/src/commonMain/kotlin/App.kt") ('fun main()=kotlinx.coroutines.runBlocking { exercise('+$expected+') }')
    }
    $wrapper=if($kotlin -match '^2\.[0-3]\.') {
        Join-Path $repository ('build/publication-wrapper/8.8/'+$(if($IsWindows){'gradlew.bat'}else{'gradlew'}))
    } else {Join-Path $repository $(if($IsWindows){'gradlew.bat'}else{'gradlew'})}
    $tasks=@(":appA:linkDebugExecutable$suffix",":appB:linkDebugExecutable$suffix",'--no-parallel','--console=plain')
    $log=Join-Path $proof "$kotlin-links.log"
    $result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments $tasks
    Assert-PariKtOutput -Result $result -Log $log -Expected @('exactly one selected PARI 2.19.0 archive','exactly one selected PARI 2.15.0 archive')
    foreach($app in @('appA','appB')) {
        $extension=if($IsWindows){'.exe'}else{'.kexe'}
        $executable=Join-Path $consumer "$app/build/bin/$target/debugExecutable/$app$extension"
        $expected=if($app -eq 'appA'){135936}else{134912}
        $output=& $executable 2>&1
        $output | Set-Content (Join-Path $proof "$kotlin-$app-runtime.log")
        if($LASTEXITCODE -or ($output -join "`n") -notlike "*PASS Native Maven $expected*") { throw "Native runtime failed: $app" }
    }
    $archives=Get-ChildItem (Join-Path $consumer 'build/parikt-binary/native') -Recurse -Filter libparikt.a
    if($archives.Count -ne 2) { throw "Expected two selected engine archives, got $($archives.Count)." }
    $hashes=@{}; foreach($archive in $archives){$hashes[$archive.FullName]=(Get-FileHash $archive.FullName).Hash}
    $warmLog=Join-Path $proof "$kotlin-reuse.log"
    $warm=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $warmLog -Arguments $tasks
    Assert-PariKtOutput -Result $warm -Log $warmLog -Expected @('artifacts are up to date')
    foreach($file in $hashes.Keys){if((Get-FileHash $file).Hash -ne $hashes[$file]){throw "Warm run changed $file"}}
    Write-NativeProof (Join-Path $consumer 'shared/parikt.properties') "pari.version=2.19.0`n"
    $conflictLog=Join-Path $proof "$kotlin-conflict.log"
    $conflict=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $conflictLog -Arguments @((':appB:compileKotlin'+$suffix),'--no-parallel','--console=plain')
    Assert-PariKtOutput -Result $conflict -Log $conflictLog -Failure $true -Expected @('PARI requirement conflict',':shared')
    Write-Host "PASS Kotlin $kotlin Native Maven runtime, independent versions, archive guards, conflicts and reuse"
}
Write-Host "Evidence: $proof"
