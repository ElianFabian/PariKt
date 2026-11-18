#requires -Version 7.0
param([string]$KotlinVersion='2.4.21',[switch]$Browser,[string]$ChromeBinary=$env:CHROME_BIN)
$ErrorActionPreference='Stop'
$repository=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $repository 'tests/build_configuration/harness.ps1')
$proof=Join-Path $repository ('build/maven-web-consumer/'+[guid]::NewGuid().ToString('N').Substring(0,12))
$null=New-Item -ItemType Directory -Path $proof -Force
Copy-Item -LiteralPath (Join-Path $repository 'publishing/build/candidate-repository') -Destination (Join-Path $proof 'maven') -Recurse
$version=((Get-Content (Join-Path $repository 'gradle.metadata.properties') | Where-Object {$_ -match '^version='}) -split '=',2)[1].Trim()
$jar=Get-ChildItem (Join-Path $proof "maven/io/github/elianfabian/parikt-engine-tools/$version") -Filter '*.jar' |
    Where-Object {$_.Name -notmatch '-(sources|javadoc)\.jar$'} | Sort-Object LastWriteTime -Descending | Select-Object -First 1
[IO.Compression.ZipFile]::ExtractToDirectory($jar.FullName,(Join-Path $proof 'tools'))
if(Get-ChildItem (Join-Path $proof 'tools') -Recurse -Filter '*.kt') { throw 'Unexpected library Kotlin sources in engine tools.' }
$consumer=Join-Path $proof 'consumer'
function Write-WebProof([string]$Relative,[string]$Text) {
    $Text=$Text.Replace('__LIBRARY_VERSION__',$version)
    $path=Join-Path $consumer $Relative
    $null=New-Item -ItemType Directory -Path (Split-Path $path) -Force
    [IO.File]::WriteAllText($path,$Text,[Text.UTF8Encoding]::new($false))
}
Write-WebProof 'settings.gradle.kts' @'
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
buildscript { repositories { maven { url=uri("../maven") } }; dependencies { classpath("io.github.elianfabian:parikt-engine-tools:__LIBRARY_VERSION__") } }
dependencyResolutionManagement { repositories { maven { url=uri("../maven") }; mavenCentral() } }
rootProject.name="ExternalWebConsumer"
include(":shared",":appA",":appB")
apply(from=javaClass.classLoader.getResource("gradle/parikt.bootstrap.settings.gradle.kts")!!)
'@
Write-WebProof 'build.gradle.kts' ('plugins { kotlin("multiplatform") version "'+$KotlinVersion+'" apply false }')
Write-WebProof 'gradle.properties' "org.gradle.jvmargs=-Xmx2g`nkotlin.js.yarn=false`n"
Write-WebProof 'parikt.properties' "pari.version=2.19.0`n"
Write-WebProof 'appB/parikt.properties' "pari.version=2.15.0`n"
Write-WebProof 'shared/build.gradle.kts' ('plugins { kotlin("multiplatform") }; kotlin { js { useEsModules(); nodejs(); browser() }; wasmJs { nodejs(); browser() }; sourceSets { commonMain.dependencies { api("io.github.elianfabian:parikt:'+$version+'"); api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0") } } }')
Write-WebProof 'shared/src/commonMain/kotlin/Shared.kt' @'
package proof
import com.elianfabian.parikt.PariKt
import kotlinx.coroutines.withTimeout
suspend fun exercise(expected: Int) = withTimeout(30_000) {
    PariKt.init()
    try {
        check(PariKt.getVersionCode()==expected)
        check(PariKt.eval("2+2").substringAfterLast('=').trim()=="4")
        check(PariKt.getSupportedCommands().contains("factor"))
    } finally { PariKt.close() }
    PariKt.init()
    try { check(PariKt.eval("7*6").substringAfterLast('=').trim()=="42") } finally { PariKt.close() }
    println("PASS web Maven $expected")
}
'@
Write-WebProof 'user-webpack/marker.js' 'config.plugins=config.plugins||[]; config.plugins.push({apply() { console.log("PASS preserved user Webpack fragment"); }});'
foreach($app in @('appA','appB')) {
    Write-WebProof "$app/build.gradle.kts" @'
plugins { kotlin("multiplatform") }
kotlin {
    js { useEsModules(); nodejs(); browser { commonWebpackConfig { configDirectory=rootProject.file("user-webpack") } }; binaries.executable() }
    wasmJs { nodejs(); browser { commonWebpackConfig { configDirectory=rootProject.file("user-webpack") } }; binaries.executable() }
    sourceSets.commonMain.dependencies { implementation(project(":shared")) }
}
'@
    $expected=if($app -eq 'appA'){135936}else{134912}
    Write-WebProof "$app/src/commonMain/kotlin/Main.kt" ('suspend fun main() { proof.exercise('+$expected+') }')
    if($Browser) {
        $build=Join-Path $consumer "$app/build.gradle.kts"
        [IO.File]::AppendAllText($build,@'

kotlin {
    sourceSets.getByName("commonTest").dependencies {
        implementation(kotlin("test"))
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    }
    js().browser { testTask { useKarma { useChromeHeadless() } } }
    wasmJs().browser { testTask { useKarma { useChromeHeadless() } } }
}
'@)
        Write-WebProof "$app/src/commonTest/kotlin/BrowserSmoke.kt" ('import kotlin.test.Test; import kotlinx.coroutines.test.runTest; import kotlinx.coroutines.withContext; import kotlinx.coroutines.Dispatchers; class BrowserSmoke { @Test fun mavenEngine()=runTest { withContext(Dispatchers.Default) { proof.exercise('+$expected+') } } }')
    }
}
$wrapper=if($KotlinVersion -match '^2\.[0-3]\.') {
    Join-Path $repository ('build/publication-wrapper/8.8/'+$(if($IsWindows){'gradlew.bat'}else{'gradlew'}))
} else {Join-Path $repository $(if($IsWindows){'gradlew.bat'}else{'gradlew'})}
$log=Join-Path $proof 'node.log'
$result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments @(':appA:jsNodeDevelopmentRun',':appB:jsNodeDevelopmentRun',':appA:wasmJsNodeDevelopmentRun',':appB:wasmJsNodeDevelopmentRun','--no-parallel','--console=plain')
Assert-PariKtOutput -Result $result -Log $log -Expected @('PASS web Maven 135936','PASS web Maven 134912')
$log=Join-Path $proof 'webpack.log'
$result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments @(':appA:jsBrowserProductionWebpack',':appB:jsBrowserProductionWebpack',':appA:wasmJsBrowserProductionWebpack',':appB:wasmJsBrowserProductionWebpack','--no-parallel','--console=plain')
Assert-PariKtOutput -Result $result -Log $log -Expected @('PASS preserved user Webpack fragment')
if($Browser) {
    if(!$ChromeBinary -and $IsWindows) {
        $ChromeBinary=@('C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe') | Where-Object {Test-Path $_} | Select-Object -First 1
    }
    if(!$ChromeBinary){throw 'Set CHROME_BIN or -ChromeBinary for headless browser execution.'}
    $previousChrome=$env:CHROME_BIN
    try {
        $env:CHROME_BIN=$ChromeBinary
        $log=Join-Path $proof 'browser.log'
        $result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments @(':appA:jsBrowserTest',':appB:jsBrowserTest',':appA:wasmJsBrowserTest',':appB:wasmJsBrowserTest','--no-parallel','--console=plain')
        Assert-PariKtOutput -Result $result -Log $log
        foreach($app in @('appA','appB')) {foreach($target in @('js','wasmJs')) {
            $reports=@(Get-ChildItem (Join-Path $consumer "$app/build/test-results/${target}BrowserTest") -Filter '*.xml')
            $count=0
            foreach($report in $reports){[xml]$xml=Get-Content $report.FullName; if([int]$xml.testsuite.failures -or [int]$xml.testsuite.errors){throw "Failed $app $target browser report"};$count += [int]$xml.testsuite.tests-[int]$xml.testsuite.skipped}
            if($count -lt 1){throw "Missing actual $app $target browser execution"}
        }}
    } finally {$env:CHROME_BIN=$previousChrome}
}
Write-WebProof 'shared/parikt.properties' "pari.version=2.19.0`n"
$log=Join-Path $proof 'conflict.log'
$result=Invoke-PariKtGradle -Repository $consumer -Wrapper $wrapper -Log $log -Arguments @(':appB:compileKotlinJs','--no-parallel','--console=plain')
Assert-PariKtOutput -Result $result -Log $log -Failure $true -Expected @('PARI requirement conflict',':shared')
Write-Host "PASS Kotlin $KotlinVersion JS/Wasm Maven Node engines, bundles, preserved fragments and conflicts. Browser runtime executed: $Browser. Evidence: $proof"
