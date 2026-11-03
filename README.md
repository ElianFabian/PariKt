# PariKt

PariKt is a Kotlin Multiplatform library that exposes the PARI/GP engine through a small common
Kotlin API.

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
their own file; the settings script never falls back to the checkout's demo selection.

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
baseline is Gradle 9.6.0, Kotlin 2.4.21 and AGP 9.4.1. Android SDK/NDK and the host prerequisites
below are still required: the source library currently configures all implemented targets,
including its Android native model, even for a JVM-only consumer.

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
`build/parikt/native`, and staged web assets under `build/parikt/web-engine`. This repository's
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
consumers refresh their staged output on their next builder invocation. Native/Web/Android
module routing remains gated by the module-version plan.

Generated AARs, klibs, executables and web bundles contain the consumer-selected engine.
They cannot change PARI after compilation. Binary-only Maven installation is not implemented;
an ordinary Maven dependency plus a properties file alone cannot run these source-build steps.
See `PARI_VERSION_PLAN.md` for the implementation checklist and cache identity discussion.

JVM-only application modules can override the root default with a `parikt.properties` in their
own project directory. `terminalApp` inherits this repository's 2.15.0 default; `desktopApp`
selects 2.19.0. Both use `consoleUi`, which compiles against the default and imposes no explicit
engine requirement. A library with its own file does impose a requirement: a conflicting app
fails with both files and the dependency path. Empty or invalid local files fail explicitly.
Use `pariktSelections` to inspect effective versions and origins.

Each application routes direct and transitive JVM dependencies to one source provider per
distinct version, and its JavaExec/test tasks use that provider's native output. Matching
selections reuse the provider and compatible engine cache. Root configuration remains required
by production integration; modules with non-JVM targets must retain the root default until
their linking/packaging proofs in [the module-version plan](PARI_MODULE_VERSION_PLAN.md) pass.

Verify the existing applications without opening a window:

```powershell
.\gradlew.bat :terminalApp:verifyPariEngine :desktopApp:verifyPariEngine
.\gradlew.bat :terminalApp:verifyPariJvmGraph :desktopApp:verifyPariJvmGraph verifyPariJvmIdentity
.\tests\build_configuration\verify_application_versions.ps1
```

The separate [module-version JVM proof](examples/module-versions/README.md) remains a focused
regression fixture. `examples/consumer` also remains the standalone installation example.

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

The common API lives in `parikt/src/commonMain/kotlin/com/elianfabian/parikt/PariKt.kt`. On Android,
the bundled AndroidX Startup initializer obtains the host app's cache directory and initializes
PARI/GP before `Application.onCreate()`. No Android initialization call or context passing is
required. After startup, evaluate with `PariKt.eval("2 + 2")` and release native state with
`PariKt.close()`. Startup uses the default PARI stack size of 4,000,000 bytes.

To try the Android target on an emulator, install Android SDK platform 36, open the project in
Android Studio, select the `androidApp` run configuration, and run it on an API 21+ `arm64-v8a` or
`x86_64` emulator. The app and library both support `minSdk` 21. The Compose BOM and Activity
Compose are pinned to versions that predate the AndroidX minSdk 23 baseline change. The Compose demo
verifies startup with `2 + 2` as soon as it opens, then lets you enter GP commands, run examples
such as `factor(123456)`, and tap an earlier result to edit and run it again. It displays the
detected PARI version and keeps a scrollable result history. You can also install it from a terminal
with `gradlew.bat :androidApp:installDebug` on Windows or `./gradlew :androidApp:installDebug` on
Linux/macOS when an emulator is running.

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

`:consoleUi` shares the dark GP console between Android, desktop JVM, Kotlin/JS, and Kotlin/WasmJS.
It shows the actual PARI version and runs `2 + 2` on startup. Choose an example or enter a GP
expression, then press **Run command** or Enter (Shift+Enter inserts a new line). Previous
commands can be clicked to reuse them. **Clear output** clears the visible history and retains
the engine's variables.

Run `androidApp` from Android Studio on an arm64-v8a or x86_64 device/emulator, or build its APK
with `.\gradlew.bat :androidApp:assembleDebug`. Android minSdk remains 21. The shared UI uses
Compose Multiplatform 1.8.2 libraries to retain API 21 compatibility.

For the desktop window on Windows:

```powershell
.\gradlew.bat :desktopApp:run
```

On Linux x86_64 use `bash ./gradlew :desktopApp:run` in a graphical desktop session. Both commands
prepare the selected PARI native libraries and configure `java.library.path`, like `terminalApp`.
The terminal CLI remains available as `:terminalApp:run`. This is a host-specific development
launcher; native installers and a packaged distribution containing libraries for every OS are
not implemented. macOS native build/runtime verification remains deferred.

The browser commands below use the same Compose screen. The Kotlin/JS canvas renderer in this
Compose release is experimental. GP evaluation is synchronous on each platform's UI thread;
long calculations can pause the interface.

## Web engine development

Kotlin/JS and Kotlin/WasmJS share an Emscripten PARI engine and support browser and Node.js
consumers. The engine uses the same `pari.version` setting as Android/JVM, static GMP 6.3.0,
and Emscripten 4.0.21. The current Kotlin build/consumer test baseline is 2.4.21; compatibility
with older Kotlin consumers has not been established. The Kotlin/JS target also needs WebAssembly
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
Android's Startup initializer retains its internal synchronous path; Android consumers do not
need to call initialization explicitly. There is no separate public `initAsync()` or synchronous
initialization method.

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
`:parikt:prepareWebEnginePackage` into `build/parikt/web-engine/` for a stable local
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
