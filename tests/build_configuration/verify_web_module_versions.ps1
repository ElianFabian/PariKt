#requires -Version 7.0
# Disposable JS/Wasm proof. The checked-in fixture and its lockfiles are never edited.
param([switch]$Production)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'harness.ps1')
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source = Join-Path $repositoryRoot 'examples/module-versions'
$scratch = Join-Path $repositoryRoot $(if ($Production) { 'build/wp' } else { 'build/web-tests' })
$runRoot = Join-Path $scratch ([Guid]::NewGuid().ToString('N').Substring(0, 12))
$fixture = Join-Path $runRoot 'f'
$logs = Join-Path $runRoot 'logs'
$utf8 = [Text.UTF8Encoding]::new($false)
$onWindows = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
$wrapper = Join-Path $repositoryRoot $(if ($onWindows) { 'gradlew.bat' } else { 'gradlew' })
$null = New-Item -ItemType Directory -Path $fixture, $logs -Force
Copy-PariKtFixture -Source $source -Destination $fixture
if ($Production) {
    foreach ($version in @('2.19.0', '2.15.0')) {
        foreach ($name in @('.stamp', 'parikt-engine.mjs', 'parikt-engine.wasm', 'parikt.mjs', 'package.json')) {
            $destination = Join-Path $fixture "build/parikt/native/web-$version/$name"
            $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
            Copy-Item -LiteralPath (Join-Path $repositoryRoot "parikt/.native/web-$version/$name") -Destination $destination
        }
        foreach ($abi in @('arm64-v8a', 'x86_64')) {
            foreach ($library in @('gmp', 'pari')) {
                $relative = "android-$version/$abi/$library/lib/lib$library.so"
                $destination = Join-Path $fixture "build/parikt/native/$relative"
                $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
                Copy-Item -LiteralPath (Join-Path $repositoryRoot "parikt/.native/$relative") -Destination $destination
            }
        }
    }
    $sdk = Get-Content (Join-Path $repositoryRoot 'local.properties') | Where-Object { $_ -match '^sdk.dir=' }
    [IO.File]::WriteAllLines((Join-Path $fixture 'local.properties'), [string[]]$sdk, $utf8)
    $settings = @'
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "ProductionWebVersions"
extra["pariktSourceDir"] = file(providers.gradleProperty("pariktSourceDir").get())
apply(from = File(extra["pariktSourceDir"] as File, "gradle/parikt.settings.gradle.kts"))
include(":webAppA", ":webAppB", ":webSharedLogic")
listOf(":webAppA", ":webAppB").forEach { project(it).buildFileName = "../web-app.gradle.kts" }
gradle.projectsEvaluated {
    rootProject.extra["parikt.proof.webpackVersion"] = rootProject.extra["parikt.webpackVersion"]
    rootProject.extra["parikt.proof.kotlinVersion"] = "__KOTLIN_VERSION__"
    rootProject.apply(from = File(settingsDir, "web-verification.gradle.kts"))
}
'@
    $kotlinVersion = (Get-Content (Join-Path $repositoryRoot 'gradle/libs.versions.toml') | Where-Object { $_ -match '^kotlin = ' }).Split('"')[1]
    [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle.kts'), $settings.Replace('__KOTLIN_VERSION__', $kotlinVersion), $utf8)
    [IO.File]::WriteAllText((Join-Path $fixture 'build.gradle.kts'), @'
plugins {
    alias(pariktLibs.plugins.android.library) apply false
    alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
}
'@, $utf8)
    $app = [IO.File]::ReadAllText((Join-Path $fixture 'web-app.gradle.kts'))
    $app = $app.Substring(0, $app.IndexOf("kotlin {`n")).Replace('extra["parikt.selections"]', 'gradle.extensions.extraProperties["parikt.selections"]')
    $app = $app.Replace('extra["parikt.engineProjects"]', 'gradle.extensions.extraProperties["parikt.engines"]')
    $app += @'
kotlin {
    js {
        useEsModules(); nodejs()
        browser { commonWebpackConfig { outputFileName = "app.js"; configDirectory = rootProject.file("web-webpack.config.d") } }
        binaries.executable()
    }
    wasmJs {
        nodejs()
        browser { commonWebpackConfig { outputFileName = "app.js"; configDirectory = rootProject.file("web-webpack.config.d") } }
        binaries.executable()
    }
    sourceSets.commonMain {
        kotlin.srcDir(rootProject.file("webAppA/src/commonMain/kotlin"))
        kotlin.srcDir(files(settingsSource).builtBy(generateSettings))
        resources.setSrcDirs(listOf(rootProject.file("webAppA/src/commonMain/resources")))
        dependencies {
            implementation(project(":webSharedLogic"))
            implementation(project(":parikt"))
            implementation(pariktLibs.kotlinx.coroutines.core)
        }
    }
}
'@
    [IO.File]::WriteAllText((Join-Path $fixture 'web-app.gradle.kts'), $app, $utf8)
    # Match real applications whose engine is hidden behind a shared library implementation.
    $app = $app.Replace('            implementation(project(":parikt"))', '')
    [IO.File]::WriteAllText((Join-Path $fixture 'web-app.gradle.kts'), $app, $utf8)
    $sharedBuild = Join-Path $fixture 'webSharedLogic/build.gradle.kts'
    [IO.File]::WriteAllText($sharedBuild, [IO.File]::ReadAllText($sharedBuild).Replace('api(project(":parikt"))', 'implementation(project(":parikt"))'), $utf8)
    $verification = [IO.File]::ReadAllText((Join-Path $fixture 'web-verification.gradle.kts'))
    $verification = $verification.Replace('extra["parikt.engineProjects"]', 'gradle.extensions.extraProperties["parikt.engines"]')
    $verification = $verification.Replace('extra["parikt.selections"]', 'gradle.extensions.extraProperties["parikt.selections"]')
    $verification = $verification.Replace('extra["parikt.checkout"]', 'gradle.extensions.extraProperties["parikt.sourceDirectory"]')
    $verification = $verification.Replace('val knownEngines = engines.keys.associateWith', 'val knownEngines = (engines.keys + listOf("2.15.0", "2.19.0")).distinct().associateWith')
    [IO.File]::WriteAllText((Join-Path $fixture 'web-verification.gradle.kts'), $verification, $utf8)
}
function Invoke-WebProof([string]$Name, [string[]]$Tasks, [string[]]$Expected, [bool]$Failure = $false, [string[]]$Absent = @(), [string[]]$Any = @()) {
    if ($Production) {
        $Tasks = @($Tasks | ForEach-Object { $_.Replace(':verifyWebGraph', ':verifyPariWebGraph') })
        $Expected = @($Expected | ForEach-Object { $_.Replace('web graph PARI=', 'web PARI=') })
    }
    $log = Join-Path $logs ($Name + '.log')
    $result = Invoke-PariKtGradle -Repository $repositoryRoot -Wrapper $wrapper -Log $log -Arguments (@('-p', $fixture, "-PpariktSourceDir=$repositoryRoot", '-PwebProof', '--no-parallel', '--console=plain', '--warning-mode=all') + $Tasks)
    $output = $result.Output
    Assert-PariKtOutput -Result $result -Log $log -Failure $Failure -Expected $Expected -Absent ($Absent + @('has been deprecated')) -Any $Any
    if ($Failure -and ($output.Contains('[web-native]') -or $output.Contains('[pari-source]'))) {
        throw "Negative case $Name reached native preparation. See $log"
    }
    $script:lastOutput = $output
    Write-Host "PASS: $Name ($log)"
}
try {
    $upgrade = @('kotlinUpgradePackageLock', 'kotlinWasmUpgradePackageLock')
    $forward = @(':webAppA:jsNodeProductionRun', ':webAppB:jsNodeProductionRun', ':webAppA:wasmJsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun')
    $reverse = @(':webAppB:wasmJsNodeProductionRun', ':webAppA:wasmJsNodeProductionRun', ':webAppB:jsNodeProductionRun', ':webAppA:jsNodeProductionRun')
    $runtimes = @('PASS web sharedLogic PARI 2.19.0 arithmetic/factorization/error recovery', 'PASS web sharedLogic PARI 2.15.0 arithmetic/factorization/error recovery')
    $noBuild = @('[pari-source] fetching', '[web-native] building')
    $unused = @(':pariktEngine_2_15_0:buildWebNativeDependencies', ':pariktEngine_2_15_0:prepareWebEnginePackage')
    if ($Production) { $unused = @(':pariktEngine_2_15_0:parikt:buildWebNativeDependencies', ':pariktEngine_2_15_0:parikt:prepareWebEnginePackage') }
    Invoke-WebProof 'single-selected-payload' ($upgrade + @(':webAppA:jsNodeProductionRun')) @($runtimes[0]) $false ($noBuild + $unused)
    if (Test-Path -LiteralPath (Join-Path $fixture 'build/parikt/web-engines/2.15.0/parikt-engine.wasm')) { throw 'Unused engine payload was staged' }
    Invoke-WebProof 'forward-identity-npm-bundles' ($forward + @('verifyWebIdentity', 'verifyWebNpm', 'verifyWebBundles', ':webAppA:verifyWebGraph', ':webAppB:verifyWebGraph')) ($runtimes + @('PASS web identity js', 'PASS web identity wasmJs', 'PASS webAppB wasm npm: one @parikt/engine-2-15-0', 'PASS :webAppB wasmJs bundle: exactly one PARI 2.15.0')) $false $noBuild
    foreach ($version in @('2.19.0', '2.15.0')) {
        if ([regex]::Matches($script:lastOutput, "PASS web sharedLogic PARI $([regex]::Escape($version)) ").Count -ne 2) { throw "Both Node targets were not exercised for $version" }
    }
    Invoke-WebProof 'reverse-and-cache' ($reverse + @('verifyWebNpm', 'verifyWebBundles')) ($runtimes + @(':webSharedLogic:compileKotlinJs UP-TO-DATE', ':webSharedLogic:compileKotlinWasmJs UP-TO-DATE')) $false $noBuild
    $override = Join-Path $fixture 'webAppB/parikt.properties'
    [IO.File]::WriteAllText($override, "pari.version=2.19.0`n", $utf8)
    Invoke-WebProof 'same-version-reuse' ($upgrade + $forward + @('verifyWebNpm', 'verifyWebBundles')) @($runtimes[0], ':webSharedLogic:compileKotlinJs UP-TO-DATE', ':webSharedLogic:compileKotlinWasmJs UP-TO-DATE', 'PASS webAppB js npm: one @parikt/engine-2-19-0') $false ($noBuild + $unused + @($runtimes[1]))
    Remove-Item -LiteralPath $override
    Invoke-WebProof 'override-removal' @(':webAppB:jsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun', ':webAppB:verifyWebGraph') @($runtimes[0], 'web graph PARI=2.19.0') $false ($noBuild + $unused)
    [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
    Invoke-WebProof 'override-restored' ($upgrade + @(':webAppB:jsNodeProductionRun', ':webAppB:wasmJsNodeProductionRun', 'verifyWebNpm', 'verifyWebBundles')) @($runtimes[1], 'PASS webAppB js npm: one @parikt/engine-2-15-0') $false $noBuild
    $requirement = Join-Path $fixture 'webSharedLogic/parikt.properties'
    [IO.File]::WriteAllText($requirement, "pari.version=2.15.0`n", $utf8)
    Invoke-WebProof 'explicit-shared-conflict' @(':webAppA:verifyWebGraph') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :webAppA -> :webSharedLogic', $requirement) $true
    Remove-Item -LiteralPath $requirement
    $appScript = Join-Path $fixture 'web-app.gradle.kts'
    $original = [IO.File]::ReadAllBytes($appScript)
    $provider = ':pariktEngine_2_19_0'
    $providerExpected = @('2_19_0', '2_15_0')
    if ($Production) {
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.15.0`n", $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'webAppA/parikt.properties'), "pari.version=2.19.0`n", $utf8)
        $provider = ':pariktEngine_2_19_0:parikt'
        $providerExpected = @('2_19_0', '2.15.0')
    }
    [IO.File]::AppendAllText($appScript, "`nkotlin { sourceSets.commonMain.dependencies { implementation(project(`"$provider`")) } }`n", $utf8)
    Invoke-WebProof 'duplicate-provider' @(':webAppB:verifyWebGraph') $providerExpected $true @() @('PARI provider conflict', 'com.elianfabian.parikt:engine')
    [IO.File]::WriteAllBytes($appScript, $original)
    if ($Production) {
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.19.0`n", $utf8)
        Remove-Item -LiteralPath (Join-Path $fixture 'webAppA/parikt.properties')
    }
    Invoke-WebProof 'restored-valid-build' ($forward + @('verifyWebNpm', 'verifyWebBundles', ':webAppA:verifyWebGraph', ':webAppB:verifyWebGraph')) ($runtimes + @('web graph PARI=2.19.0', 'web graph PARI=2.15.0')) $false $noBuild
    if ($Production) {
        Remove-Item -LiteralPath (Join-Path $fixture 'parikt.properties')
        foreach ($module in @('webAppA', 'webAppB', 'webSharedLogic')) {
            [IO.File]::WriteAllText((Join-Path $fixture "$module/parikt.properties"), "pari.version=2.19.0`n", $utf8)
        }
        Invoke-WebProof 'rootless-shared-version' ($upgrade + $forward + @('verifyWebIdentity', 'verifyWebNpm', 'verifyWebBundles')) @($runtimes[0], 'PASS web identity js', 'PASS web identity wasmJs', 'PASS webAppB js npm: one @parikt/engine-2-19-0') $false ($noBuild + @($runtimes[1]))
        Invoke-WebProof 'rootless-default-engine-task' @(':parikt:prepareWebEnginePackage') @('No root PARI default', 'Module overrides are not a default') $true
        [IO.File]::WriteAllText($override, "pari.version=2.15.0`n", $utf8)
        Invoke-WebProof 'rootless-shared-conflict' @(':webAppB:jsNodeProductionRun') @('PARI requirement conflict', '2.19.0', '2.15.0', 'dependency path: :webAppB -> :webSharedLogic') $true
        foreach ($relative in @('build.gradle.kts', 'src/commonMain/kotlin/proof/SharedWebLogic.kt')) {
            $destination = Join-Path $fixture "webAlternateLogic/$relative"
            $null = New-Item -ItemType Directory -Path (Split-Path $destination) -Force
            Copy-Item -LiteralPath (Join-Path $fixture "webSharedLogic/$relative") -Destination $destination
        }
        [IO.File]::WriteAllText((Join-Path $fixture 'webAlternateLogic/parikt.properties'), "pari.version=2.15.0`n", $utf8)
        [IO.File]::WriteAllText((Join-Path $fixture 'web-app-b.gradle.kts'), [IO.File]::ReadAllText($appScript).Replace(':webSharedLogic', ':webAlternateLogic'), $utf8)
        [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":webAlternateLogic`")`nproject(`":webAppB`").buildFileName = `"../web-app-b.gradle.kts`"`n", $utf8)
        Invoke-WebProof 'rootless-independent-versions' ($upgrade + $forward + @('verifyWebIdentity', 'verifyWebNpm', 'verifyWebBundles', ':webAppA:verifyWebGraph', ':webAppB:verifyWebGraph')) ($runtimes + @('PASS webAppB js npm: one @parikt/engine-2-15-0')) $false $noBuild
        [IO.File]::WriteAllText((Join-Path $fixture 'parikt.properties'), "pari.version=2.19.0`n", $utf8)
        Invoke-WebProof 'root-default-restored' ($upgrade + $forward + @('verifyWebNpm', 'verifyWebBundles')) $runtimes $false $noBuild
        # Retain only final browser distributions alongside logs, allowing real browser checks
        # after the disposable source/build fixture is removed.
        foreach ($appName in @('webAppA', 'webAppB')) {
            foreach ($target in @('js', 'wasmJs')) {
                $destination = Join-Path $runRoot "browser/$appName/$target"
                $null = New-Item -ItemType Directory -Path $destination -Force
                Copy-Item -Path (Join-Path $fixture "$appName/build/dist/$target/productionExecutable/*") -Destination $destination -Recurse -Force
            }
        }
        Write-Host "Browser artifacts: $(Join-Path $runRoot 'browser')"
    }
    foreach ($lock in (Get-ChildItem (Join-Path $fixture 'kotlin-js-store') -Filter 'package-lock.json' -Recurse -File)) {
        if ([IO.File]::ReadAllText($lock.FullName) -match '(?i)"resolved"\s*:\s*"(?:file:/|[A-Z]:[\\/]|/home/|/Users/)') { throw "Nonportable npm lock: $($lock.FullName)" }
    }
    Write-Host 'PASS: portable-lockfiles'
} finally {
    Remove-PariKtFixture -Path $fixture -Parent $scratch
}
