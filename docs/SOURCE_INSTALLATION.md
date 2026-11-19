# Install PariKt from source

This guide covers the Gradle source installer, engine selection and cache settings.
The [platform guide](PLATFORM_GUIDE.md) lists prerequisites and build commands.

## Choose PARI independently of PariKt

Set the engine version in the **consuming project's** root `parikt.properties`:

```properties
pari.version=2.19.0
```

Pin the PariKt source revision separately, for example with a Git submodule at `vendor/PariKt`.
Changing PARI requires editing only this file and syncing/rebuilding. There is no default version
for a missing file, no custom Gradle plugin, and no PariKt release for each PARI release.
The previous `pariVersion` Gradle property is rejected with migration instructions.
This repository's own file selects PARI only for its demos/tests. External consumers must provide
their own selection files; the settings script never falls back to the checkout's demo selection.
Builds on all supported targets can omit the root file when every participating module selects a version locally,
as described below.

For source integration, in your settings, after
configuring plugin/dependency repositories, add:

```kotlin
extra["pariktSourceDir"] = file("vendor/PariKt")
apply(from = file("vendor/PariKt/gradle/parikt.settings.gradle.kts"))
```

The script includes `:parikt` and `:pariktAndroidNative` and imports a private `pariktLibs` version
catalog. In the root build, share their plugin classpath:

```kotlin
plugins {
    alias(pariktLibs.plugins.android.library) apply false
    alias(pariktLibs.plugins.android.kotlin.multiplatform.library) apply false
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
}
```

Your Kotlin module can then declare `implementation(project(":parikt"))`. If your root build
already declares these plugins, keep one declaration using the same versions. The current build
baseline is Gradle 9.6.0, Kotlin 2.4.21 and AGP 9.4.1. Sharing AGP enables the Android target/model
and requires the Android SDK/NDK and [host prerequisites](PLATFORM_GUIDE.md#android). Kotlin-only roots can omit AGP;
default and scoped providers then configure the non-Android targets. JVM/Native/web consumers
need the Kotlin plugin classpath:

```kotlin
plugins {
    alias(pariktLibs.plugins.kotlin.multiplatform) apply false
    alias(pariktLibs.plugins.kotlin.jvm) apply false // If the consumer uses this plugin.
}
```

### Kotlin consumer compatibility

Source consumers compile PariKt with their own Kotlin compiler. The tested release points cover
Kotlin 2.0 through 2.4; the repository's sample apps keep their pinned toolchain. These are the
complete consumer-matrix combinations:

| Kotlin | Gradle | Android Gradle plugin |
| --- | --- | --- |
| 2.0.0 | 8.5 | 8.3.2, with R8 8.5.10 override |
| 2.0.21 | 8.8 | 8.5.2 |
| 2.1.21 | 8.9 | 8.7.2 |
| 2.2.21 | 8.11.1 | 8.10.1 |
| 2.3.21 | 8.14 | 8.13.2 |
| 2.4.21 | 9.6.0 | 9.4.1 |

To use the imported plugin aliases with a different consumer toolchain, override their catalog
versions in **your settings**, after applying `parikt.settings.gradle.kts`:

```kotlin
dependencyResolutionManagement.versionCatalogs.named("pariktLibs") {
    version("kotlin", "2.3.21")
    version("agp", "8.13.2") // Only needed when using Android plugin aliases.
}
```

Use the matching Gradle wrapper in the consuming build. AGP 8 consumers load
`com.android.library` and KGP in the parent classpath and omit the
`android.kotlin.multiplatform.library` alias from the root plugin block above; PariKt uses
`androidTarget` internally. AGP 9 uses the current Android KMP library plugin. For the oldest
2.0.0 Android profile, the root build supplies the newer shrinker before its `plugins` block:

```kotlin
buildscript {
    repositories { google(); mavenCentral() }
    dependencies { classpath("com.android.tools:r8:8.5.10") }
}
```

All six profiles verify JVM, JS/Wasm Node and Windows/Linux Native consumers with PARI 2.15.0
and 2.19.0, plus Android debug and R8-shrunk release builds. JVM checks include one implementation,
strict configuration-cache reuse and conflicting explicit requirements. Android packages retain
minSdk 21, Startup metadata and ARM64/x86_64 libraries. Kotlin 2.0.0 and 2.4.21 release APKs were
also run on an API36.1 x86_64 emulator. Web boundary/lifecycle tests pass on all six compilers.

The verified host is Windows with WSL for Linux execution. Other historical patches, future
releases, older-compiler browser runtime and API21-device runtime are not individually verified.
See [Apple targets](APPLE_TARGETS.md) for separate Intel/ARM macOS and simulator verification,
and [the compatibility plan](../KOTLIN_COMPATIBILITY_PLAN.md) for exact version coverage.

The standalone `examples/consumer` build demonstrates JVM, Native Windows/Linux and JS/Wasm
Node consumers. Its `parikt.properties` is independent of this repository's demo selection.
From this repository on Windows:

```powershell
.\gradlew.bat -p examples/consumer :app:runJvm
.\gradlew.bat -p examples/consumer :app:runDebugExecutableMingwX64
.\gradlew.bat -p examples/consumer :app:jsNodeDevelopmentRun
.\gradlew.bat -p examples/consumer :app:wasmJsNodeDevelopmentRun
```

See the [standalone consumer example](../examples/consumer/README.md) for Linux execution and additional checks. External consumers
own generated module outputs under `build/parikt/modules`, native engines under
`build/parikt/native`, and staged web assets under `build/parikt/web-engines/<version>`. This repository's
development build retains its existing `parikt/.native` engine caches. Shared host source/toolchain
work caches are reused. Native builders lock each work cache so simultaneous consumer builds
wait rather than modifying the same compiler/source stages.
Native build tasks invoke the builders to validate their own fingerprints on each requested
build. A cache hit is reported by the script; it need not carry Gradle's `UP-TO-DATE` label.

The four native builders share PARI acquisition under the build host's
`~/.cache/parikt/sources`: one Git object store and pristine snapshots keyed by resolved commit.
Each backend derives its own mutable workspace for patches/configuration. Existing downloaded
release tags can seed the shared store without downloading again; legacy caches remain intact.
Selecting an already cached release, including during a forced compilation, does not fetch it
again. `master`/`latest` refresh their resolved revision.

JVM builders additionally share complete compatible engines under `~/.cache/parikt/engines/jvm`.
The key includes the source revision, bridge/JNI inputs, compiler identity and build options.
The Linux JVM's configured optional-data path is an output-dependent build option, so different
data paths use different entries. Consumer outputs are staged copies; matching modules share a
source component and native task. Force rebuilds replace the matching cache entry, and other
consumers refresh their staged output on their next builder invocation. Android, Native and web retain their existing per-version target caches while sharing source acquisition.

Source-built AARs, klibs, executables and web bundles contain the consumer-selected engine.
Changing the engine requires rebuilding. The [Maven installation guide](../publishing/README.md#consumer-settings)
covers the separate binary bindings and engine-tools bootstrap. See the
[version plan](../PARI_VERSION_PLAN.md) for cache identity and design history.

JVM, Native, Android and JS/Wasm application modules can override the root default with a `parikt.properties` in their
own project directory. All regular applications inherit this repository's **2.19.0** default;
different versions are exercised only by opt-in verification suites and the module-version example.
The terminal and desktop apps use `consoleUi`, which compiles against the default and imposes no explicit
engine requirement. A library with its own file does impose a requirement: a conflicting app
fails with both files and the dependency path. Empty or invalid local files fail explicitly.
Use `pariktSelections` to inspect effective versions and origins.

Each application routes direct and transitive JVM dependencies to one source provider per
distinct version, and its JavaExec/test tasks use that provider's native output. Matching
selections reuse the provider and compatible engine cache. Production builds on all supported platforms support
**module-only configuration**: omit the root file and put `parikt.properties` in every module
that directly or transitively uses PariKt, including shared libraries. Unrelated modules need
no selection. Supported KMP libraries include common metadata routing.

Every local library file is a strict requirement, so a shared library's selection must agree
with all consuming apps. Independent applications with separate dependency chains can select
different versions. No module supplies a default for its siblings. A present empty/invalid root
file remains an error even if all modules have local files.

Without a root default, `:parikt` and `:pariktAndroidNative` are empty selectors. Invoke the
application's task; default-engine tasks such as `:parikt:jvmJar` fail with instructions to
provide a root default, even if all local files agree. Module-only JVM/Native/web builds sharing only Kotlin plugins configure no Android model or bootstrap. Module-only Android builds share AGP/KGP in the root as shown above; their internal providers/helpers use each module's explicit selection.

Verify the existing applications without opening a window:

```powershell
.\gradlew.bat :terminalApp:verifyPariEngine :desktopApp:verifyPariEngine
.\gradlew.bat :terminalApp:verifyPariJvmGraph :desktopApp:verifyPariJvmGraph verifyPariJvmIdentity
.\tests\build_configuration\verify_application_versions.ps1
pwsh -File tests/build_configuration/verify_production_module_only.ps1
```

The module-only acceptance suite is verified on Windows x86_64 with WSL. It expects prepared
2.15.0/2.19.0 JVM engine caches, which the application suite above populates; its cases reject
source fetches or engine recompilation while checking compatible reuse.

The separate [module-version proofs](../examples/module-versions/README.md) retain the focused
JVM fixture and add opt-in Native, JS/Wasm and Android compatibility proofs. `examples/consumer` also remains
the standalone installation example.

## Gradle configuration cache

Production source integration supports Gradle's configuration cache on the verified Windows/WSL
toolchain. Enable it per command, or set `org.gradle.configuration-cache=true` in your build's
`gradle.properties`:

```powershell
.\gradlew.bat :terminalApp:verifyPariEngine --configuration-cache
.\gradlew.bat :androidApp:assembleDebug --configuration-cache
```

Repeat the same command to reuse the configured task graph. Root and module-local
`parikt.properties` are tracked inputs: adding, editing or removing them updates selection and
strict dependency checks. This cache is separate from shared PARI downloads and compiled-engine
caches; native builders still validate their fingerprints and retain their concurrency locks.
Generated provider/helper scripts have stable contents before Gradle evaluates them. No custom
plugin is required. Configuration caching remains opt-in; the repository does not enable it globally.

The PowerShell acceptance suite uses the production installer with shared Kotlin fixture sources,
requires real cache hits with zero configuration-cache problems, and checks version-file changes,
missing/invalid selections, strict conflicts and restored module-only builds:

```powershell
pwsh -File tests/build_configuration/verify_configuration_cache.ps1 -Platforms
```

Default and scoped engine providers use the same `parikt/build.gradle.kts` definition. Settings
copies it into generated scoped projects and supplies the selected version; sources remain in
the pinned checkout. Android configuration is enabled when the consuming root shares AGP.
There is no custom Gradle plugin. Verification scripts share `tests/build_configuration/harness.ps1`
for process/log handling and fixture operations; run `verify_harness.ps1` to check those helpers.
Current maintenance status and archived rollout notes are linked from [HANDOFF.md](../HANDOFF.md).

`-Platforms` also checks the real JVM launchers, Android debug APK, Native links and identity,
JS/Wasm tests and graph diagnostics. Standalone consumer execution, JVM tests, Compose browser
distributions, interactive JVM/Windows Native launchers and missing Native metadata recovery also
pass strict cache storage/reuse. Verified host: Windows x86_64 with WSL. Android Studio Rabbit 1
(2026.2.1) also completes a manually triggered GUI sync/model import with prepared caches.
Fresh-host setup, broader IDE run/debug workflows and other hosts retain their coverage limits.

To exercise the Linux JVM bootstrap with isolated empty caches (WSL on Windows), run
`pwsh -File tests/build_configuration/verify_cold_jvm.ps1`. It uses installed host build tools,
downloads Gradle/dependencies/sources, builds and runs a consumer, then verifies warm cache reuse.
It preserves existing caches and retains its new temporary root for diagnosis. This is a focused
cold-cache check, not a complete fresh-machine installation proof.
`-GradleExecutable <path-to-bin/gradle>` uses an installed distribution while retaining empty
dependency/native caches. With that option, `-ResumeLogDirectory <previous-run>` continues a
failed run and preserves the original evidence. Linux state is kept in a new durable directory
under `~/.cache/parikt-verification`; logs are under `build/cold-jvm/`.
Verified on 2026-11-05 with PARI 2.19.0 on WSL Linux x86_64: fresh wrapper/dependency/source
downloads, native compilation, consumer runtime and warm configuration-cache/engine reuse pass.
Installed JDK/C tools remain prerequisites; this does not verify fresh OS setup or other targets.
See the handoff for evidence and scope.
