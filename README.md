# PariKt

PariKt is a Kotlin Multiplatform library that exposes the PARI/GP engine through a small common
Kotlin API.

New to the repository? Read the [contributor guide](docs/CONTRIBUTOR_GUIDE.md) for the project
layout, each target's implementation, PARI builds/caches, and a verification-script reference.

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

Source integration is currently the supported consumer installation. In your settings, after
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
and requires the Android SDK/NDK and host prerequisites below. Kotlin-only roots can omit AGP;
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
iOS backend implementation remains separate. See [the compatibility plan](KOTLIN_COMPATIBILITY_PLAN.md)
for evidence and exact limits; this matrix does not change the existing sample-app pins.

The standalone `examples/consumer` build demonstrates JVM, Native Windows/Linux and JS/Wasm
Node consumers. Its `parikt.properties` is independent of this repository's demo selection.
From this repository on Windows:

```powershell
.\gradlew.bat -p examples/consumer :app:runJvm
.\gradlew.bat -p examples/consumer :app:runDebugExecutableMingwX64
.\gradlew.bat -p examples/consumer :app:jsNodeDevelopmentRun
.\gradlew.bat -p examples/consumer :app:wasmJsNodeDevelopmentRun
```

See `examples/consumer/README.md` for Linux execution and additional checks. External consumers
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

Generated AARs, klibs, executables and web bundles contain the consumer-selected engine.
They cannot change PARI after compilation. Binary-only Maven installation is not implemented;
an ordinary Maven dependency plus a properties file alone cannot run these source-build steps.
See `PARI_VERSION_PLAN.md` for the implementation checklist and cache identity discussion.

JVM, Native, Android and JS/Wasm application modules can override the root default with a `parikt.properties` in their
own project directory. `terminalApp` inherits this repository's 2.15.0 default; `desktopApp`
selects 2.19.0. Both use `consoleUi`, which compiles against the default and imposes no explicit
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

The separate [module-version proofs](examples/module-versions/README.md) retain the focused
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
Current maintenance status and archived rollout notes are linked from [HANDOFF.md](HANDOFF.md).

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

## Android

The Android target uses the Android-KMP library plugin. JNI and the Android NDK build live in the
companion `:pariktAndroidNative` Android library, which PariKt consumes from its `androidMain`
source set. On Windows, the native dependency build runs in WSL; Linux and macOS can run the native
script directly.

Set the engine version in the root `parikt.properties`:

```properties
pari.version=2.19.0
```

Then sync Gradle and build the Android library. If native dependencies are missing, the first sync
builds GMP and PARI/GP for `arm64-v8a` and `x86_64` before Android Studio configures the NDK model;
later syncs reuse those outputs. GMP is pinned to 6.3.0 by the native build script. The native build
needs network access, Git, curl, xz, make, m4, patchelf, and (on Windows) a working WSL
installation. Set `nativeRunner=local` on Linux/macOS, or `nativeRunner=wsl` on Windows to override
the default runner. The Android build currently uses AGP 9.4.1 with Gradle 9.6.0 and Kotlin 2.4.21.
The Android app uses AGP's built-in Kotlin support.

Android applications also support module-local overrides and module-only configuration. One
source provider/native helper is shared by all apps selecting the same version. Helpers have
separate build/CXX directories per version and preserve AndroidX Startup and minSdk 21.
CLI configuration prepares native dependencies only for participating Android selections.
IDE model requests conservatively prepare all configured helpers after project configuration;
cold sync may build dependencies before a conflicting graph is resolved. Android Studio's sync
UI itself remains unverified in this rollout; both ABIs' NDK models configure/build successfully.

Production acceptance and optional runtime checks:

```powershell
pwsh -File tests/build_configuration/verify_android_module_versions.ps1 -Production
# Use the retained apks directory printed under build/android-tests/<run>/:
pwsh -File tests/build_configuration/verify_android_runtime.ps1 -ApkDirectory <run>/apks -DeviceSerial <serial> -AndroidSdk <sdk>
.\gradlew.bat :androidApp:verifyPariAndroidGraph
```

The runtime check requires a booted disposable device without the proof packages installed.
It checks deferred startup, explicit initialization/reinitialization and both selections, then
removes only its new proof installations.
Assembly and payload hashes are verified for arm64-v8a and x86_64; actual runtime coverage is
x86_64 API 36.1. ARM64 runtime and API-21 runtime remain unverified.

The common API lives in `parikt/src/commonMain/kotlin/com/elianfabian/parikt/PariKt.kt`. On Android,
the bundled AndroidX Startup initializer records the host app's cache directory. It does not
load JNI libraries or initialize PARI. Call suspending `PariKt.init()` when your application is
ready, optionally supplying its stack size (default: 4,000,000 bytes); the adapter automatically
applies the recorded temporary directory before native initialization. No context argument is
required. Then evaluate with `PariKt.eval("2 + 2")` and release native state with `PariKt.close()`.
Keep the Startup manifest entry enabled for automatic path configuration. Existing Android
callers that relied on eager initialization must add an explicit `init()` call. The Compose
demo already does this in its application-owned initialization coroutine.

The Android helper and verification scripts read the NDK SDK version from `gradle/libs.versions.toml`
(`ndk`). Its matching Linux/WSL bootstrap archive label is `ndkArchive` in the same catalog.

To try the Android target on an emulator, install Android SDK platform 36, open the project in
Android Studio, select the `androidApp` run configuration, and run it on an API 21+ `arm64-v8a` or
`x86_64` emulator. The app and library both support `minSdk` 21. The Compose BOM and Activity
Compose are pinned to versions that predate the AndroidX minSdk 23 baseline change. The Compose demo
verifies startup with `2 + 2` as soon as it opens, then lets you enter GP commands, run examples
such as `factor(123456)`, and tap an earlier result to edit and run it again. It displays the
detected PARI version and keeps a scrollable result history. You can also install it from a terminal
with `gradlew.bat :androidApp:installDebug` on Windows or `./gradlew :androidApp:installDebug` on
Linux/macOS when an emulator is running.

The demo packages only `arm64-v8a` and `x86_64`, so transitive graphics libraries cannot advertise
unsupported 32-bit PARI architectures. `-PnativeAbis=x86_64` selects a supported subset. Its normal
APK contains the selected ABIs; separate ABI APKs are demonstrated by the opt-in Android
[module-version proof](examples/module-versions/README.md#opt-in-android-proof), using standard
AGP ABI splits. All APKs preserve minSdk 21.

### JVM terminal app (Windows and WSL/Linux bring-up)

The repository includes a basic terminal consumer at `:terminalApp`. It initializes PariKt, prints
the PARI version and a `2 + 2` startup check, then accepts one GP command per line. Type `quit` or
`exit`, or send EOF, to close the engine. Terminal Java run tasks, including IDE-created `MainKt`
tasks, build host-specific native libraries before launch and store them under
`parikt/.native/jvm-<version>/<host>/`.

The first supported build hosts are Windows x86_64 and Linux x86_64 under WSL. They require separate
libraries: Windows builds produce DLLs (cross-compiled from WSL with MinGW-w64); Linux builds
produce `.so` files. Android `.so` files cannot be used by desktop JVMs. On WSL/Ubuntu, install the
host build prerequisites with
`sudo apt install build-essential git curl xz-utils m4 openjdk-21-jdk-headless`. For Windows builds,
the script uses an installed MinGW-w64 compiler if available; otherwise it downloads a pinned,
checksum-verified portable MinGW-w64 toolchain into the native build cache, with no system package
installation required.
The Linux build uses the Linux JDK's JNI headers; Windows cross-compilation uses the Windows JDK
headers supplied by the Windows Gradle run. JVM code loads these libraries with
`System.loadLibrary`; consumers must put their native directory on `java.library.path`. The terminal
run task does this automatically.

Run on Windows from PowerShell with WSL installed:

```powershell
gradlew.bat :terminalApp:run
```

Run the Linux build from a WSL checkout with a Linux JDK installed:

```bash
./gradlew :terminalApp:run
```

With a single selected version, you can override the output/library directory with
`-PjvmNativeDir=<directory>`. Global output/library directory overrides are rejected when multiple
versions are selected. `-PjvmNativeForce` rebuilds each requested engine; matching consumers reuse
the resulting cache entry. Earlier Windows/WSL terminal checks passed for PARI 2.17.1,
with an additional Windows check for 2.18.0. The standalone source consumer now verifies 2.15.0
and 2.19.0 on the Windows JVM. Clean-machine setup, macOS, missing-library diagnostics and packaged
distribution still need validation; this is not yet a published cross-platform distribution.

## Kotlin/Native (Linux and Windows)

The `linuxX64` and `mingwX64` targets call the C engine directly through cinterop. PARI/GMP and the bridge
are statically included in the executable; users do not need a JVM or separate PARI/GMP
shared libraries to run it. Standard operating-system libraries are still required. The backend,
tests and terminal code are shared between both targets.

From PowerShell on Windows with WSL x86_64:

```powershell
.\gradlew.bat :nativeTerminalApp:runMingwX64 --console=plain
.\gradlew.bat :nativeTerminalApp:runLinuxX64 --console=plain
.\gradlew.bat :parikt:verifyMingwX64 :parikt:verifyLinuxX64
```

The first two commands open the Windows and Linux native consoles respectively, with version
information and a startup self-check. Enter GP expressions, or `quit` / `exit` to close the
console. The third command runs both native test suites; Linux runs through WSL. On a Linux
x86_64 build host, use `bash ./gradlew` instead of `.\gradlew.bat`; Linux runs directly there.
Windows executables and tests must run on Windows.

Native modules support the same root defaults and local overrides as JVM modules. For example,
`nativeTerminalApp/parikt.properties` can select 2.19.0 while the root remains on 2.15.0.
Shared libraries with inherited defaults remain neutral; local library files impose strict
requirements. Module-only Native builds also work when every participating module selects locally.
Keep `kotlin.mpp.enableCInteropCommonization=true` in gradle.properties for shared Native metadata,
as in both examples. Matching applications share a provider and its version/target cache.

Default and scoped providers share stable Kotlin/interop identities. Source providers use a
stable `:parikt` leaf name; final links check every input klib for exactly one `libparikt.a`
matching the selected cache binary. Competing explicit providers fail before Gradle can merge
their versions. These source-integration identities rebuild Kotlin outputs without invalidating
compatible C caches. Inspect or verify them with:

```powershell
.\gradlew.bat :nativeTerminalApp:verifyPariNativeGraph verifyPariNativeIdentity
pwsh -File tests/build_configuration/verify_native_module_versions.ps1 -Production
```

The ten production acceptance cases pass on Windows x86_64/WSL, covering both target runtimes,
both orders, same-version reuse, rootless shared/independent chains, strict requirements,
higher/lower provider requests and restored defaults. The harness seeds prepared 2.15.0/2.19.0
C artifacts/stamps into disposable consumer-owned caches; builders validate them normally.
Root-default source installation retains its existing Android SDK prerequisites. Production
Android and web support the same scoped selection contract.

In Android Studio, sync Gradle after changing the build script, then run the native main
configuration (`runDebugExecutableMingwX64`). Generated debug/release run tasks forward console
input just like the custom launchers; click the Run console and enter a GP command at `gp>`.

To build without running:

```powershell
.\gradlew.bat :nativeTerminalApp:linkDebugExecutableMingwX64
.\gradlew.bat :nativeTerminalApp:linkDebugExecutableLinuxX64
```

The executables are `nativeTerminalApp/build/bin/mingwX64/debugExecutable/nativeTerminalApp.exe`
and `nativeTerminalApp/build/bin/linuxX64/debugExecutable/nativeTerminalApp.kexe`. The Windows
`.exe` runs directly without Gradle, WSL or Java. Both use the same `pari.version` setting as
the other targets. Builds, native tests and terminal runs have passed with PARI 2.15.0 and 2.19.0
on Windows/WSL. The Windows import table contains only ADVAPI32.dll, KERNEL32.dll and msvcrt.dll.

Gradle prepares Kotlin/Native's matching Linux C compiler/sysroot, builds static GMP 6.3.0
and the selected PARI source, and packages the C archive into the interop library. This avoids
mixing a newer host libc with Kotlin/Native's baseline. When building from Windows, the Linux
compiler is cached inside WSL for speed. WSL/Linux needs a C build environment, make, m4, git,
curl, tar and xz. Gradle itself needs a JDK; the resulting native executable does not.
Windows C compilation also runs in WSL, using checksum-verified xPack MinGW-w64 GCC 15.2.0-2
with MSVCRT selected explicitly and Kotlin/Native's matching Windows headers. The headers are
cached inside WSL too. This avoids mixing newer MinGW CRT functions with Kotlin/Native's runtime.

Native output lives under `parikt/.native/native-<version>/<linux-x86_64|windows-x86_64>/`, with intermediate
sources/toolchains under `~/.cache/parikt-kotlin-native` in Linux/WSL. Use `-PkotlinNativeForce`
to force a native rebuild. Bridge/wrapper/header/script changes invalidate the output cache.

Call the common suspending `PariKt.init()` and keep initialization, evaluation, command listing
and close sequential on the **same OS thread**. The native backend rejects engine calls from
another thread after initialization. A coroutine dispatcher with multiple threads does not
provide this guarantee. Concurrent initialization is unsupported. Native consumers can use
`runBlocking`, as the terminal demo does; they do not need to opt in to C interop themselves.
Use the default stack size unless deliberately tuning memory; native initialization failures
from tiny or excessive allocations are not recoverable guarantees. Optional PARI data packages
are not bundled. Linux uses `TMPDIR` for temporary files, falling back to `/tmp`. Windows uses
`TEMP`, then `TMP`, with the current directory as the final fallback.

Native support currently covers Linux and Windows x86_64. Apple targets, Linux ARM64,
publication, clean-machine validation and additional operating-system versions remain future work.
The existing Compose desktop app continues to use JVM.

## Shared Compose demo

`:consoleUi` shares a PariDroid-style GP console between Android, desktop JVM, Kotlin/JS, and
Kotlin/WasmJS, using the reference app's default Material theme and compact title/icon controls.
It shows the actual PARI version and runs `2 + 2` on startup. Enter a GP expression and press the
**check button** to run it. Enter inserts a newline, and the mobile keyboard requests a multiline
return key. The command field grows up to five visible lines and scrolls for longer input.
A vertical completion popup suggests the command token at the cursor; click a suggestion or press Tab.
**Previous**/**Next** browse session command history and restore your unfinished draft. Results
appear newest first; click a result to reuse its command or swipe a card sideways to remove it.

The top-right menu contains examples, **Copy output**, **Clear output**, **Restart engine**,
**Settings** and **Custom scripts**. Clearing output retains command history and PARI variables;
restarting clears PARI variables and functions while retaining displayed results and scripts.
Settings validate `parisize` (initial stack, default 4,000,000 bytes) and `parisizemax` (maximum
stack, default 0, disabling automatic growth). A nonzero maximum must be at least the initial
size; both sizes are limited to signed 32-bit values for compatibility across these backends.
**Apply and restart** saves the settings and reinitializes the engine on its owning UI thread.

The **Custom Scripts & Functions** modal keeps its editor and saved list together, with the
console visible behind it. Scripts can be created, renamed, edited and deleted. **Load** copies saved GP code into
the command field; **Run** evaluates it in the current session. For example, save and run
`sq(x)=x^2`, then enter `sq(12)` in the console. Scripts use GP syntax and the same evaluator as
typed commands. Settings and scripts survive application restarts: Android uses private
SharedPreferences, desktop uses `~/.parikt/console.preferences`, and web uses the current
origin's localStorage. Storage failures appear in the UI. Command history and output are only
kept for the current session.

Run `androidApp` from Android Studio on an arm64-v8a or x86_64 device/emulator, or build its APK
with `.\gradlew.bat :androidApp:assembleDebug`. Android minSdk remains 21. The shared UI uses
Compose Multiplatform 1.8.2 libraries to retain API 21 compatibility.

For the desktop window on Windows:

```powershell
.\gradlew.bat :desktopApp:run
```

On Linux x86_64 use `bash ./gradlew :desktopApp:run` in a graphical desktop session. Both commands
prepare the selected PARI native libraries and configure `java.library.path`, like `terminalApp`.
The terminal CLI remains available as `:terminalApp:run`. These Gradle run tasks are host-specific
development launchers. A portable Windows x64 package is available through the script below;
native installers and packages for other operating systems are not implemented. macOS native
build/runtime verification remains deferred.

To build a Windows executable with its Java runtime and selected PARI DLLs included:

```powershell
.\desktopApp\package_windows.ps1
# Or specify the packaging JDK explicitly:
.\desktopApp\package_windows.ps1 -JdkHome 'C:\path\to\jdk-21'
```

Run this on Windows x64 with `JAVA_HOME` pointing to a JDK containing `jpackage` (verified with
JBR 21). The existing Windows native-build prerequisites still apply when building. The script
uses `:desktopApp:stageWindowsApp`, which gets the final module's resolved engine through the
existing selection logic; it does not infer a version from cache directories. The current
desktop override selects PARI 2.19.0. No installer toolchain or custom Gradle plugin is needed.

Each invocation creates a new directory under `desktopApp/build/windows-package/output/`,
containing `PariKt/PariKt.exe` and `PariKt-windows-x64.zip`. Extract the ZIP and keep the entire
`PariKt` folder together, including `app` and `runtime`; the EXE is its launcher, not a single-file
bundle. The recipient does not need Java, Gradle or WSL installed. `PariKt-check.exe` is a console
diagnostic launcher that checks the bundled PARI version/arithmetic/factorization and exits.
Packaging runs that check and checks `PariKt.exe --check-engine` before making the ZIP. Logs are
stored beside the packaged folder. These are unsigned development binaries.

The browser commands below use the same Compose screen. The Kotlin/JS canvas renderer in this
Compose release is experimental. GP evaluation is synchronous on each platform's UI thread;
long calculations can pause the interface.

## Web engine development

Kotlin/JS and Kotlin/WasmJS share an Emscripten PARI engine and support browser and Node.js
consumers. The engine uses the same `pari.version` setting as Android/JVM, static GMP 6.3.0,
and Emscripten 4.0.21. The repository's Kotlin baseline is 2.4.21; library consumers can use the
tested Kotlin 2.0–2.4 combinations above. The Kotlin/JS target also needs WebAssembly
at runtime because PARI itself is compiled to Wasm.

Inside a coroutine or suspending function:

```kotlin
PariKt.init()
try {
    println(PariKt.getVersionName())
    println(PariKt.eval("factor(123456)"))
} finally {
    PariKt.close()
}
```

The public `init()` is now suspending on every target, so it can load the web engine before
initializing PARI. JVM callers must use a coroutine or a suspending `main()` (as in `terminalApp`).
Android callers also initialize explicitly; Startup only supplies the temporary-directory path.
There is no separate public `initAsync()` or synchronous initialization method.

`close()` is safe before initialization and on repeated calls; a closed engine can be initialized
again. Stack sizes must be positive and fit the backend's native representation (a signed 32-bit
`long` for Windows JNI). GP `quit()` returns an evaluation error and leaves the engine available;
use `PariKt.close()` to release it. Empty commands return an empty string.

`:parikt:jvmTest` runs boundary regressions in isolated child processes for every configured
JNI engine. Native and JS/Wasm tests share the public API cases: command enumeration beyond
1,000 functions, recoverable quit, empty input, repeated close and invalid initialization sizes.
The configuration-cache acceptance script also checks that unrelated JVM applications and tests
retain their library paths and do not prepare PARI.

The `:webApp` GP console runs with either compiler target. From PowerShell, run one at a time
and open the local address printed by Gradle:

```powershell
.\gradlew.bat :webApp:jsBrowserDevelopmentRun
.\gradlew.bat :webApp:wasmJsBrowserDevelopmentRun
```

Build static browser distributions with `:webApp:jsBrowserDistribution` and
`:webApp:wasmJsBrowserDistribution`. Serve the resulting `webApp/build/dist/js/productionExecutable/`
or `webApp/build/dist/wasmJs/productionExecutable/` directory over HTTP; opening `index.html`
directly as a file is not a supported Wasm loading workflow.

On Windows with WSL x86_64, run from PowerShell:

```powershell
.\gradlew.bat :parikt:verifyWebNative
.\gradlew.bat :parikt:jsNodeTest :parikt:wasmJsNodeTest
```

On Linux x86_64:

```bash
bash ./gradlew :parikt:verifyWebNative
```

The task prepares a checksum-verified Emscripten/Node toolchain under
`~/.cache/parikt-web` in Linux/WSL, builds the selected PARI source, and exercises it in Node.
Host tools currently required: Python 3.11+, a C compiler, make, m4, git, curl, tar, and xz.
The first build downloads a substantial compiler archive and compiles GMP/PARI; later builds
reuse the toolchain, GMP, and generated engine. Automatic toolchain setup currently supports
Linux/WSL x86_64 only. Other hosts can provide an activated Emscripten 4.0.21 SDK with
`-PwebEmsdkDir=<path>` and `-PwebNativeRunner=local`; those hosts remain unverified.

Generated engines are cached under `parikt/.native/web-<version>/` and staged by
`:parikt:prepareWebEnginePackage` into `build/parikt/web-engines/<version>/` for a stable local
npm dependency. This keeps lockfiles portable and avoids changing dependency URLs when selecting
a different PARI version. Native source/bridge changes also invalidate web test and bundle inputs.
`parikt.mjs` exports an async
factory; after it resolves, call `init()` and then `eval()`, `getSupportedCommands()`,
`getVersionCode()`, and `close()`. Keep the adapter, engine `.mjs`, and `.wasm` assets together.
The factory accepts Emscripten module options (for example `locateFile` or `wasmBinary`).
Temporary files use Emscripten's in-memory `/tmp`; optional PARI data packages are not bundled.
Evaluation is synchronous and runs on the caller's thread; heavy calculations can pause the
browser UI. A Worker-based API is future work. The web bridge accepts a positive signed 32-bit
byte count; that is a representation check, not a promise that every size can initialize PARI.
Use the default 4,000,000 bytes unless deliberately tuning memory: tiny requests can fail native
initialization, and large allocations depend on available Wasm memory. These are local project integrations,
not a published Maven/npm web distribution. Native builds can be forced with `-PwebNativeForce`.

Browser consumer tests are `:parikt:jsBrowserTest` and `:parikt:wasmJsBrowserTest`. They use headless
Chrome; set `CHROME_BIN` to another Chromium executable (such as Edge) if needed. The native
engine smoke has passed for PARI 2.15.0 and 2.19.0; Kotlin consumer tests use the selected version.
macOS build-host verification remains deferred.

Production web verification uses pwsh -File tests/build_configuration/verify_web_module_versions.ps1 -Production. It covers both Node targets, all four browser bundles, implementation-hidden shared dependencies, root defaults and module-only builds. Each runtime graph and final bundle contains one selected engine. JS tooling pins webpack 5.112.2 consistently with webpack-cli; Wasm retains its separate KGP tooling.
