#requires -Version 7.0
[CmdletBinding()]
param(
    [string[]]$KotlinVersion = @('2.0.0', '2.0.21', '2.1.21', '2.2.21', '2.3.21', '2.4.21'),
    [ValidateSet('Jvm', 'Web', 'WebTests', 'Native', 'Android')][string[]]$Checks = @('Jvm'),
    [string]$ResumeDirectory
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repository = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$runDirectory = Join-Path $repository ('build/kotlin-consumers/' + [guid]::NewGuid().ToString('N').Substring(0, 12))
$results = @()
if ($ResumeDirectory) {
    $runDirectory = [IO.Path]::GetFullPath($ResumeDirectory)
    $allowedRoot = [IO.Path]::GetFullPath((Join-Path $repository 'build/kotlin-consumers')) + [IO.Path]::DirectorySeparatorChar
    if (-not $runDirectory.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath (Join-Path $runDirectory 'results.json'))) { throw 'ResumeDirectory must name a retained consumer-matrix run under build/kotlin-consumers.' }
    $results = @(Get-Content -LiteralPath (Join-Path $runDirectory 'results.json') -Raw | ConvertFrom-Json)
    if (-not (Test-Path -LiteralPath (Join-Path $runDirectory 'results-initial.json'))) {
        Copy-Item -LiteralPath (Join-Path $runDirectory 'results.json') -Destination (Join-Path $runDirectory 'results-initial.json')
    }
}
$null = New-Item -ItemType Directory -Path $runDirectory -Force
$utf8 = [Text.UTF8Encoding]::new($false)
$windowsHost = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$checksums = @{}
foreach ($version in $KotlinVersion) {
    if ($version -notin @('2.0.0', '2.0.21', '2.1.21', '2.2.21', '2.3.21', '2.4.21')) { throw "Unconfigured Kotlin version: $version" }
    # Kotlin 2.0.0 predates Gradle 8.8; later representative patches support 8.8.
    $gradleVersion = if ($version -eq '2.0.0') { '8.5' } else { '8.8' }
    $androidGradleVersion = $null
    $r8Version = $null
    $retainedProfile = $results | Where-Object Kotlin -eq $version | Select-Object -First 1
    $withAndroidTools = $Checks -contains 'Android' -or ($ResumeDirectory -and $retainedProfile.Agp)
    if ($withAndroidTools) {
        switch ($version) {
            '2.0.0' { $androidGradleVersion = '8.3.2'; $r8Version = '8.5.10' }
            '2.0.21' { $androidGradleVersion = '8.5.2' }
            '2.1.21' { $androidGradleVersion = '8.7.2'; $gradleVersion = '8.9' }
            '2.2.21' { $androidGradleVersion = '8.10.1'; $gradleVersion = '8.11.1' }
            '2.3.21' { $androidGradleVersion = '8.13.2'; $gradleVersion = '8.14' }
            '2.4.21' { $androidGradleVersion = '9.4.1'; $gradleVersion = '9.6.0' }
        }
    }
    $modernAndroid = $androidGradleVersion -and $androidGradleVersion.StartsWith('9.')
    if (-not $checksums.ContainsKey($gradleVersion)) {
        $content = (Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip.sha256").Content
        $checksums[$gradleVersion] = $(if ($content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($content) } else { [string]$content }).Trim()
        if ($checksums[$gradleVersion] -notmatch '^[a-fA-F0-9]{64}$') { throw 'Invalid Gradle distribution checksum.' }
    }
    $checksum = $checksums[$gradleVersion]
    $fixture = Join-Path $runDirectory $version
    function Write-ConsumerFile([string]$Relative, [string]$Content) {
        $path = Join-Path $fixture $Relative
        $null = New-Item -ItemType Directory -Path (Split-Path $path) -Force
        [IO.File]::WriteAllText($path, $Content, $utf8)
    }
    Write-ConsumerFile 'gradle/wrapper/gradle-wrapper.properties' @"
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip
distributionSha256Sum=$checksum
networkTimeout=60000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"@
    Copy-Item -LiteralPath (Join-Path $repository 'gradle/wrapper/gradle-wrapper.jar') -Destination (Join-Path $fixture 'gradle/wrapper/gradle-wrapper.jar')
    foreach ($wrapperName in @('gradlew', 'gradlew.bat')) { Copy-Item -LiteralPath (Join-Path $repository $wrapperName) -Destination (Join-Path $fixture $wrapperName) }
    Write-ConsumerFile 'settings.gradle.kts' @"
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "KotlinConsumer"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
dependencyResolutionManagement.versionCatalogs.named("pariktLibs") { version("kotlin", "$version") }
include(":appA", ":appB", ":shared")
if (providers.gradleProperty("compat.web").isPresent) include(":webA", ":webB")
if (providers.gradleProperty("compat.native").isPresent) include(":nativeA", ":nativeB")
if (providers.gradleProperty("compat.android").isPresent) include(":androidA", ":androidB")
"@
    Write-ConsumerFile 'gradle.properties' "org.gradle.jvmargs=-Xmx2048m`nkotlin.native.ignoreDisabledTargets=true`nkotlin.mpp.enableCInteropCommonization=true`nkotlin.js.yarn=false`nandroid.useAndroidX=true`nandroid.suppressUnsupportedCompileSdk=36`n"
    Write-ConsumerFile 'parikt.properties' "pari.version=2.15.0`n"
    Write-ConsumerFile 'appB/parikt.properties' "pari.version=2.19.0`n"
    Write-ConsumerFile 'build.gradle.kts' @'
plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false
}
'@
    if ($withAndroidTools) {
        # Match Android dex/shrinker tooling to each compiler, not just JVM metadata compilation.
        $rootBuild = [IO.File]::ReadAllText((Join-Path $fixture 'build.gradle.kts'))
        $kotlinAndroidPlugin = if ($modernAndroid) { "id(`"com.android.kotlin.multiplatform.library`") version `"$androidGradleVersion`" apply false" } else { "id(`"org.jetbrains.kotlin.android`") version `"$version`" apply false" }
        $rootBuild = $rootBuild.Replace('plugins {', "plugins {`n    id(`"com.android.library`") version `"$androidGradleVersion`" apply false`n    id(`"com.android.application`") version `"$androidGradleVersion`" apply false`n    $kotlinAndroidPlugin")
        if ($r8Version) { $rootBuild = "buildscript { repositories { google(); mavenCentral() }; dependencies { classpath(`"com.android.tools:r8:$r8Version`") } }`n" + $rootBuild }
        Write-ConsumerFile 'build.gradle.kts' $rootBuild
        Copy-Item -LiteralPath (Join-Path $repository 'local.properties') -Destination (Join-Path $fixture 'local.properties')
        # This matrix varies Kotlin/AGP, not the C toolchain. Seed prepared C outputs;
        # the ordinary native builder still validates stamps and rebuilds mismatches.
        foreach ($pari in @('2.15.0','2.19.0')) {
            $prepared = Join-Path $repository "parikt/.native/android-$pari"
            if (Test-Path -LiteralPath $prepared) {
                $destination = Join-Path $fixture 'build/parikt/native'
                $null = New-Item -ItemType Directory -Path $destination -Force
                if (-not (Test-Path -LiteralPath (Join-Path $destination "android-$pari"))) {
                    Copy-Item -LiteralPath $prepared -Destination $destination -Recurse
                }
            }
        }
    }
    Write-ConsumerFile 'shared/build.gradle.kts' @'
import org.gradle.kotlin.dsl.withGroovyBuilder
plugins { id("org.jetbrains.kotlin.multiplatform") }
if (providers.gradleProperty("compat.android").isPresent) {
    pluginManager.apply("com.android.library")
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "probe.shared")
        setProperty("compileSdk", 36)
        getProperty("defaultConfig").withGroovyBuilder { setProperty("minSdk", 21) }
        getProperty("compileOptions").withGroovyBuilder {
            setProperty("sourceCompatibility", JavaVersion.VERSION_17)
            setProperty("targetCompatibility", JavaVersion.VERSION_17)
        }
    }
}
kotlin {
    jvm()
    if (providers.gradleProperty("compat.android").isPresent) androidTarget {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    if (providers.gradleProperty("compat.web").isPresent) {
        js { useEsModules(); nodejs() }
        wasmJs { nodejs() }
    }
    if (providers.gradleProperty("compat.native").isPresent) { linuxX64(); mingwX64() }
    sourceSets.commonMain.dependencies {
        api(project(":parikt"))
        implementation(pariktLibs.kotlinx.coroutines.core)
    }
}
'@
    if ($modernAndroid) {
        $sharedBuild = [IO.File]::ReadAllText((Join-Path $fixture 'shared/build.gradle.kts'))
        $legacyStart = $sharedBuild.IndexOf('if (providers.gradleProperty("compat.android").isPresent) {')
        $kotlinStart = $sharedBuild.IndexOf('kotlin {', $legacyStart)
        $sharedBuild = $sharedBuild.Substring(0, $legacyStart) + 'if (providers.gradleProperty("compat.android").isPresent) pluginManager.apply("com.android.kotlin.multiplatform.library")' + "`n" + $sharedBuild.Substring($kotlinStart)
        $sharedBuild = $sharedBuild.Replace('if (providers.gradleProperty("compat.android").isPresent) androidTarget {', 'if (providers.gradleProperty("compat.android").isPresent) (this as ExtensionAware).extensions.getByName("android").withGroovyBuilder { setProperty("namespace", "probe.shared"); setProperty("compileSdk", 36); setProperty("minSdk", 21); (getProperty("compilerOptions") as org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions).apply {')
        $sharedBuild = $sharedBuild.Replace('compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)', 'jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }')
        Write-ConsumerFile 'shared/build.gradle.kts' $sharedBuild
    }
    Write-ConsumerFile 'shared/src/commonMain/kotlin/probe/Probe.kt' @'
package probe
import com.elianfabian.parikt.PariKt
import com.elianfabian.parikt.getVersionName
suspend fun checkEngine(expected: String) {
    PariKt.init()
    try {
        check(PariKt.getVersionName() == expected) { "Expected PARI $expected, got ${PariKt.getVersionName()}" }
        check(PariKt.eval("2+2").substringAfterLast('=').trim() == "4")
        check(PariKt.eval("factor(123456)").contains("643"))
        println("PASS Kotlin consumer PARI $expected arithmetic/factorization")
    } finally { PariKt.close() }
}
'@
    foreach ($app in @('appA', 'appB')) {
        Write-ConsumerFile "$app/build.gradle.kts" @'
plugins { id("org.jetbrains.kotlin.jvm"); application }
dependencies { implementation(project(":shared")) }
application { mainClass.set("probe.MainKt") }
kotlin { jvmToolchain(21) }
'@
        Write-ConsumerFile "$app/src/main/kotlin/probe/Main.kt" @'
package probe
suspend fun main() {
    checkEngine(checkNotNull(System.getProperty("parikt.expected.version")))
    val copies = Thread.currentThread().contextClassLoader.getResources("com/elianfabian/parikt/PariKt.class").toList()
    check(copies.size == 1) { "Duplicate PariKt implementations: $copies" }
    println("PASS one PariKt implementation")
}
'@
    }
    $wrapper = Join-Path $fixture $(if ($windowsHost) { 'gradlew.bat' } else { 'gradlew' })
    foreach ($kind in @('web', 'native')) {
        foreach ($suffix in @('A', 'B')) {
            $module = "$kind$suffix"
            $pari = if ($suffix -eq 'A') { '2.15.0' } else { '2.19.0' }
            if ($suffix -eq 'B') { Write-ConsumerFile "$module/parikt.properties" "pari.version=$pari`n" }
            $targets = if ($kind -eq 'web') {
                'js { useEsModules(); nodejs(); binaries.executable() }; wasmJs { nodejs(); binaries.executable() }'
            } else {
                'linuxX64 { binaries.executable { entryPoint = "probe.main" } }; mingwX64 { binaries.executable { entryPoint = "probe.main" } }'
            }
            Write-ConsumerFile "$module/build.gradle.kts" @"
plugins { id("org.jetbrains.kotlin.multiplatform") }
kotlin {
    $targets
    sourceSets.commonMain.dependencies {
        implementation(project(":shared"))
        implementation(pariktLibs.kotlinx.coroutines.core)
    }
}
"@
            $main = if ($kind -eq 'web') { "package probe`nsuspend fun main() { checkEngine(`"$pari`") }`n" } else {
                "package probe`nimport kotlinx.coroutines.runBlocking`nfun main(): Unit = runBlocking { checkEngine(`"$pari`") }`n"
            }
            Write-ConsumerFile "$module/src/commonMain/kotlin/probe/Main.kt" $main
        }
    }
    foreach ($check in $Checks) {
        if ($check -eq 'Android') {
            foreach ($suffix in @('A','B')) {
                $module = "android$suffix"
                if ($suffix -eq 'B') { Write-ConsumerFile "$module/parikt.properties" "pari.version=2.19.0`n" }
                $androidAppPlugins = if ($modernAndroid) { 'id("com.android.application")' } else { 'id("com.android.application"); id("org.jetbrains.kotlin.android")' }
                Write-ConsumerFile "$module/build.gradle.kts" @"
plugins { $androidAppPlugins }
android {
    namespace = "probe.android$suffix"
    compileSdk = 36
    defaultConfig { applicationId = "probe.android$suffix"; minSdk = 21; targetSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies { implementation(project(":shared")); implementation(pariktLibs.kotlinx.coroutines.core) }
"@
                Write-ConsumerFile "$module/src/main/AndroidManifest.xml" '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:label="Kotlin consumer"><activity android:name="probe.MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>'
                $pari = if ($suffix -eq 'A') { '2.15.0' } else { '2.19.0' }
                Write-ConsumerFile "$module/src/main/kotlin/probe/MainActivity.kt" @"
package probe
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.runBlocking
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runBlocking { checkEngine("$pari") }
        setContentView(TextView(this).apply { text = "PASS PARI $pari" })
    }
}
"@
            }
        }
        $tasks = switch ($check) {
            'Jvm' { @(':appA:run', ':appB:run', '--configuration-cache', '--configuration-cache-problems=fail') }
            'Web' { @('-Pcompat.web', ':webA:jsNodeDevelopmentRun', ':webB:jsNodeDevelopmentRun', ':webA:wasmJsNodeDevelopmentRun', ':webB:wasmJsNodeDevelopmentRun') }
            # Keep the npm workspace graph identical to Web so both checks can share a lock.
            'WebTests' { @('-Pcompat.web', ':parikt:jsNodeTest', ':parikt:wasmJsNodeTest') }
            'Native' {
                @('-Pcompat.native', ':nativeA:linkDebugExecutableLinuxX64', ':nativeB:linkDebugExecutableLinuxX64') +
                    $(if ($windowsHost) { @(':nativeA:linkDebugExecutableMingwX64', ':nativeB:linkDebugExecutableMingwX64') } else { @() })
            }
            'Android' { @('-Pcompat.android', ':androidA:assembleDebug', ':androidB:assembleDebug', ':androidA:assembleRelease', ':androidB:assembleRelease', ':androidA:verifyPariAndroidGraph', ':androidB:verifyPariAndroidGraph') }
        }
        $logName = if ($ResumeDirectory) { "$($check.ToLower())-retry-$([guid]::NewGuid().ToString('N').Substring(0,8)).log" } else { "$($check.ToLower()).log" }
        $log = Join-Path $fixture $logName
        $result = Invoke-PariKtGradle -Repository $repository -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repository", '--no-parallel', '--console=plain') + $tasks)
        $passed = $result.ExitCode -eq 0
        if ($passed -and $check -eq 'Native') {
            foreach ($suffix in @('A','B')) {
                $expectedPari = if ($suffix -eq 'A') { '2.15.0' } else { '2.19.0' }
                if ($windowsHost) {
                    $executable = Join-Path $fixture "native$suffix/build/bin/mingwX64/debugExecutable/native$suffix.exe"
                    $nativeLog = Join-Path $fixture "native$suffix-windows-runtime.log"
                    & $executable | Tee-Object -FilePath $nativeLog
                    if ($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath $nativeLog -SimpleMatch "PASS Kotlin consumer PARI $expectedPari")) { $passed = $false }
                }
                $executable = Join-Path $fixture "native$suffix/build/bin/linuxX64/debugExecutable/native$suffix.kexe"
                $nativeLog = Join-Path $fixture "native$suffix-linux-runtime.log"
                if ($windowsHost) {
                    $linuxPath = '/mnt/' + $executable.Substring(0,1).ToLowerInvariant() + $executable.Substring(2).Replace('\','/')
                    & wsl --exec $linuxPath | Tee-Object -FilePath $nativeLog
                } else {
                    & $executable | Tee-Object -FilePath $nativeLog
                }
                if ($LASTEXITCODE -ne 0 -or -not (Select-String -LiteralPath $nativeLog -SimpleMatch "PASS Kotlin consumer PARI $expectedPari")) { $passed = $false }
            }
        } elseif ($passed -and $check -notin @('Android','WebTests')) {
            $passed = $result.Output.Contains('PASS Kotlin consumer PARI 2.15.0') -and $result.Output.Contains('PASS Kotlin consumer PARI 2.19.0')
        }
        if ($passed -and $check -eq 'WebTests') {
            foreach ($task in @('jsNodeTest','wasmJsNodeTest')) {
                $reports = @(Get-ChildItem -LiteralPath (Join-Path $fixture "build/parikt/modules/parikt/test-results/$task") -Filter '*.xml')
                if (-not $reports.Count) { throw "No XML test results for $task" }
                $count = 0
                foreach ($report in $reports) {
                    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
                    if ([int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0) { throw "Failed tests in $($report.FullName)" }
                    $count += [int]$xml.testsuite.tests
                }
                if ($count -lt 2) { throw "Expected lifecycle and boundary tests for $task, got $count" }
                Write-Host "PASS $version $task $count boundary/lifecycle tests"
            }
        }
        if ($passed -and $check -eq 'Android') {
            $sdkLine = Get-Content -LiteralPath (Join-Path $fixture 'local.properties') | Where-Object { $_ -match '^sdk.dir=' } | Select-Object -First 1
            $sdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
            $aapt = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory | Sort-Object Name -Descending |
                ForEach-Object { Join-Path $_.FullName $(if ($windowsHost) { 'aapt2.exe' } else { 'aapt2' }) } |
                Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
            if (-not $aapt) { throw "No aapt2 in $sdk" }
            foreach ($suffix in @('A','B')) {
                $apk = Join-Path $fixture "android$suffix/build/outputs/apk/debug/android$suffix-debug.apk"
                $badging = & $aapt dump badging $apk
                if ($LASTEXITCODE -ne 0 -or -not ($badging -match "sdkVersion:'21'")) { throw "APK does not preserve minSdk 21: $apk" }
                $manifest = & $aapt dump xmltree --file AndroidManifest.xml $apk
                if ($LASTEXITCODE -ne 0 -or -not ($manifest -match 'com.elianfabian.parikt.PariKtInitializer')) { throw "Missing Startup initializer: $apk" }
                $zip = [IO.Compression.ZipFile]::OpenRead($apk)
                try {
                    foreach ($abi in @('arm64-v8a','x86_64')) {
                        foreach ($library in @('libpari.so','libgmp.so','libparidroid.so')) {
                            if (@($zip.Entries | Where-Object FullName -eq "lib/$abi/$library").Count -ne 1) { throw "Expected one $abi/$library in $apk" }
                        }
                    }
                } finally { $zip.Dispose() }
                Write-Host "PASS Android $suffix API21, Startup, ARM64/x86_64 native payloads"
            }
        }
        $warmPassed = $null
        $conflictPassed = $null
        if ($passed -and $check -eq 'Jvm') {
            $warmLog = Join-Path $fixture 'jvm-warm.log'
            $warm = Invoke-PariKtGradle -Repository $repository -Wrapper $wrapper -Log $warmLog -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repository", '--no-parallel', '--console=plain') + $tasks)
            $warmPassed = $warm.ExitCode -eq 0 -and $warm.Output.Contains('Configuration cache entry reused.') -and
                $warm.Output.Contains('PASS Kotlin consumer PARI 2.15.0') -and $warm.Output.Contains('PASS Kotlin consumer PARI 2.19.0') -and
                $warm.Output.Contains('reusing compiled engine')
            Write-ConsumerFile 'shared/parikt.properties' "pari.version=2.15.0`n"
            try {
                $conflict = Invoke-PariKtGradle -Repository $repository -Wrapper $wrapper -Log (Join-Path $fixture 'jvm-conflict.log') -Arguments @('-p', $fixture, "-PpariktSourceDir=$repository", ':appB:run', '--no-parallel', '--console=plain')
                $conflictPassed = $conflict.ExitCode -ne 0 -and $conflict.Output.Contains('PARI requirement conflict') -and -not $conflict.Output.Contains('[jvm-native]')
            } finally { Remove-Item -LiteralPath (Join-Path $fixture 'shared/parikt.properties') }
            $passed = $passed -and $warmPassed -and $conflictPassed
        }
        $previousLog = ($results | Where-Object { $_.Kotlin -eq $version -and $_.Check -eq $check } | Select-Object -Last 1).Log
        $results = @($results | Where-Object { $_.Kotlin -ne $version -or $_.Check -ne $check })
        $results += [pscustomobject]@{ Kotlin = $version; Gradle = $gradleVersion; Agp = $androidGradleVersion; R8Override = $r8Version; Check = $check; Passed = $passed; WarmPassed = $warmPassed; ConflictPassed = $conflictPassed; Log = $log; PreviousLog = $previousLog }
        $results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $runDirectory 'results.json')
        Write-Host "Kotlin $version $check passed=$passed ($log)"
        if (-not $passed) { Get-Content -LiteralPath $log -Tail 45 }
    }
}
Write-Host "Results: $runDirectory"
if (@($results | Where-Object { -not $_.Passed }).Count) { throw 'Kotlin consumer matrix has failures; retained logs contain diagnostics.' }
