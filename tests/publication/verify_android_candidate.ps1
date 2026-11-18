#requires -Version 7.0
param([string]$AndroidSdk=$env:ANDROID_HOME,[string]$ResumeDirectory)
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
if(!$AndroidSdk -or !(Test-Path $AndroidSdk)) { throw 'Supply -AndroidSdk or ANDROID_HOME.' }
$proof=Join-Path $repository ('build/maven-android-consumer/'+[guid]::NewGuid().ToString('N').Substring(0,12))
if($ResumeDirectory) {
    $proof=[IO.Path]::GetFullPath($ResumeDirectory)
    $allowed=[IO.Path]::GetFullPath((Join-Path $repository 'build/maven-android-consumer'))+[IO.Path]::DirectorySeparatorChar
    if(!$proof.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase) -or !(Test-Path (Join-Path $proof 'maven'))) { throw 'ResumeDirectory must name a retained Android Maven fixture.' }
}
$null=New-Item -ItemType Directory -Path $proof -Force
if(!$ResumeDirectory) { Copy-Item -LiteralPath (Join-Path $repository 'publishing/build/candidate-repository') -Destination (Join-Path $proof 'maven') -Recurse }
$version=((Get-Content (Join-Path $repository 'gradle.metadata.properties') | Where-Object {$_ -match '^version='}) -split '=',2)[1].Trim()
$jar=Get-ChildItem (Join-Path $proof "maven/io/github/elianfabian/parikt-engine-tools/$version") -Filter '*.jar' |
    Where-Object {$_.Name -notmatch '-(sources|javadoc)\.jar$'} | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if(!$ResumeDirectory) { [IO.Compression.ZipFile]::ExtractToDirectory($jar.FullName,(Join-Path $proof 'tools')) }
if(Get-ChildItem (Join-Path $proof 'tools') -Recurse -Filter '*.kt') { throw 'Unexpected library Kotlin sources in engine tools.' }
$consumer=Join-Path $proof 'consumer'
if(Test-Path (Join-Path $consumer 'shared/parikt.properties')) { Remove-Item -LiteralPath (Join-Path $consumer 'shared/parikt.properties') }
function Write-AndroidProof([string]$Relative,[string]$Text) {
    $Text=$Text.Replace('__LIBRARY_VERSION__',$version)
    $path=Join-Path $consumer $Relative
    $null=New-Item -ItemType Directory -Path (Split-Path $path) -Force
    [IO.File]::WriteAllText($path,$Text,[Text.UTF8Encoding]::new($false))
}
Write-AndroidProof 'settings.gradle.kts' @'
pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
buildscript { repositories { maven { url=uri("../maven") } }; dependencies { classpath("io.github.elianfabian:parikt-engine-tools:__LIBRARY_VERSION__") } }
dependencyResolutionManagement { repositories { maven { url=uri("../maven") }; google(); mavenCentral() } }
rootProject.name="ExternalAndroidConsumer"
include(":shared",":appA",":appB")
apply(from=javaClass.classLoader.getResource("gradle/parikt.bootstrap.settings.gradle.kts")!!)
'@
Write-AndroidProof 'build.gradle.kts' @'
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    kotlin("android") version "2.0.21" apply false
}
'@
Write-AndroidProof 'gradle.properties' "org.gradle.jvmargs=-Xmx2g`nandroid.useAndroidX=true`nandroid.suppressUnsupportedCompileSdk=36`n"
Write-AndroidProof 'local.properties' ('sdk.dir='+$AndroidSdk.Replace('\','/')+"`n")
Write-AndroidProof 'parikt.properties' "pari.version=2.19.0`n"
Write-AndroidProof 'appB/parikt.properties' "pari.version=2.15.0`n"
Write-AndroidProof 'shared/build.gradle.kts' ('plugins { id("com.android.library"); kotlin("android") }; android { namespace="proof.maven.shared"; compileSdk=36; defaultConfig { minSdk=21 }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget="17" } }; dependencies { api("io.github.elianfabian:parikt:'+$version+'"); api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") }')
Write-AndroidProof 'shared/src/main/kotlin/Shared.kt' @'
package proof.maven.shared
import com.elianfabian.parikt.PariKt
suspend fun exercise(expected: Int): String {
    PariKt.init()
    try {
        check(PariKt.getVersionCode()==expected)
        check(PariKt.eval("2+2").substringAfterLast('=').trim()=="4")
        check(PariKt.getSupportedCommands().contains("factor"))
    } finally { PariKt.close() }
    return "PASS Android Maven $expected"
}
'@
foreach($app in @('appA','appB')) {
    $package='proof.maven.'+$app.ToLowerInvariant()
    Write-AndroidProof "$app/build.gradle.kts" ('plugins { id("com.android.application"); kotlin("android") }; android { namespace="'+$package+'"; compileSdk=36; ndkVersion=pariktLibs.versions.ndk.get(); defaultConfig { applicationId="'+$package+'"; minSdk=21; targetSdk=36; versionCode=1; versionName="1" }; compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }; kotlinOptions { jvmTarget="17" }; buildTypes { getByName("release") { isMinifyEnabled=true; signingConfig=signingConfigs.getByName("debug"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } } }; dependencies { implementation(project(":shared")) }')
    Write-AndroidProof "$app/src/main/AndroidManifest.xml" ('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:theme="@android:style/Theme.Material.Light.NoActionBar" android:label="Maven proof"><activity android:name="'+$package+'.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>')
    $expected=if($app -eq 'appA'){135936}else{134912}
    Write-AndroidProof "$app/src/main/kotlin/MainActivity.kt" ('package '+$package+"`nimport proof.maven.shared.exercise`n"+'class MainActivity:android.app.Activity() { override fun onCreate(state:android.os.Bundle?) { super.onCreate(state); val result=kotlinx.coroutines.runBlocking { exercise('+$expected+') }; setContentView(android.widget.TextView(this).apply { text=result }); android.util.Log.i("PariKtMavenProof",result) } }')
}
$wrapper=Join-Path $repository ('build/publication-wrapper/8.8/'+$(if($IsWindows){'gradlew.bat'}else{'gradlew'}))
$log=Join-Path $proof 'apk-builds.log'
$result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments @(':appA:assembleDebug',':appB:assembleDebug',':appA:assembleRelease',':appB:assembleRelease',':shared:assembleDebug','--no-parallel','--console=plain')
Assert-PariKtOutput -Result $result -Log $log
$aapt=Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Filter $(if($IsWindows){'aapt2.exe'}else{'aapt2'}) -Recurse | Sort-Object FullName -Descending | Select-Object -First 1
$ndk=Get-PariKtToolVersion -Repository $repository -Name 'ndk'
$hostTag=if($IsWindows){'windows-x86_64'}elseif($IsLinux){'linux-x86_64'}else{'darwin-x86_64'}
$strip=Join-Path $AndroidSdk "ndk/$ndk/toolchains/llvm/prebuilt/$hostTag/bin/llvm-strip$(if($IsWindows){'.exe'})"
foreach($app in @('appA','appB')) {
    $pari=if($app -eq 'appA'){'2.19.0'}else{'2.15.0'}
    foreach($variant in @('debug','release')) {
        $apk=Join-Path $consumer "$app/build/outputs/apk/$variant/$app-$variant.apk"
        $badging=& $aapt.FullName dump badging $apk
        if($LASTEXITCODE -or ($badging -join "`n") -notmatch "sdkVersion:'21'") { throw "APK minSdk mismatch: $apk" }
        $manifest=& $aapt.FullName dump xmltree --file AndroidManifest.xml $apk
        if($LASTEXITCODE -or ($manifest -join "`n") -notmatch 'com.elianfabian.parikt.PariKtInitializer') { throw "Missing Startup temporary-directory initializer in $apk" }
        $zip=[IO.Compression.ZipFile]::OpenRead($apk)
        try {
            $payloads=@($zip.Entries | Where-Object {$_.FullName -match '^lib/[^/]+/[^/]+\.so$'})
            if($payloads.Count -ne 8) { throw "Expected eight engine/runtime payloads (four per ABI), got $($payloads.Count): $apk" }
            foreach($abi in @('arm64-v8a','x86_64')) {
                foreach($component in @('gmp','pari')) {
                    $entry=$zip.GetEntry("lib/$abi/lib$component.so")
                    if(!$entry){throw "Missing $abi/$component in $apk"}
                    $raw=Join-Path $consumer "build/parikt-binary/engines/android-$pari/$abi/$component/lib/lib$component.so"
                    $stripped=Join-Path $proof "$pari-$abi-$component.so"
                    & $strip --strip-unneeded -o $stripped $raw
                    if($LASTEXITCODE){throw "Cannot strip expected $raw"}
                    $expected=Get-FileHash $stripped
                    $stream=$entry.Open();try{$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($stream))}finally{$stream.Dispose()}
                    if($hash -ne $expected.Hash){throw "Wrong engine payload in $apk"}
                }
                if(!$zip.GetEntry("lib/$abi/libparidroid.so")){throw "Missing JNI bridge for $abi"}
                if(!$zip.GetEntry("lib/$abi/libc++_shared.so")){throw "Missing C++ runtime for $abi"}
            }
        } finally {$zip.Dispose()}
    }
}
$shared=Get-ChildItem (Join-Path $consumer 'shared/build/outputs/aar') -Filter '*.aar' | Select-Object -First 1
$zip=[IO.Compression.ZipFile]::OpenRead($shared.FullName)
try{if($zip.Entries | Where-Object {$_.FullName -match '\.so$'}){throw 'Shared Android library unexpectedly embeds an engine.'}}finally{$zip.Dispose()}
Write-AndroidProof 'shared/parikt.properties' "pari.version=2.19.0`n"
$conflictLog=Join-Path $proof 'conflict.log'
$conflict=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $conflictLog -Arguments @(':appB:assembleDebug','--no-parallel','--console=plain')
Assert-PariKtOutput -Result $conflict -Log $conflictLog -Failure $true -Expected @('PARI requirement conflict',':shared')
Write-Host "PASS Android Maven debug/R8 APKs, both ABIs, minSdk21, engine-free shared AAR and explicit conflicts. Runtime NOT executed. Evidence: $proof"
