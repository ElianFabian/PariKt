# Understanding and developing PariKt

PariKt exposes the PARI/GP mathematics engine through a Kotlin Multiplatform API. Kotlin provides
the API and applications; PARI and GMP remain C libraries compiled for each destination.
You do not need to understand every Gradle script to make a first change. Start with the map
below, follow the backend you are changing, then choose its verification checks.

This guide describes the current source implementation. See [README](../README.md) for consumer
installation and detailed host prerequisites, [HANDOFF](../HANDOFF.md) for current work and
coverage limits, and [the module-version plan](../PARI_MODULE_VERSION_PLAN.md) for design history.

- [Repository map](#1-repository-map)
- [How a Kotlin call reaches PARI](#2-how-a-kotlin-call-reaches-pari)
- [Version selection and Gradle scripts](#3-version-selection-and-the-gradle-scripts)
- [Downloads, caches and generated output](#4-downloads-caches-and-generated-output)
- [Building and testing](#5-building-and-testing)
- [Choosing checks for a change](#6-making-a-change-without-losing-coverage)

## 1. Repository map

| Location | Responsibility |
| --- | --- |
| `settings.gradle.kts` | Installs PariKt's source integration and includes the repository's application modules. |
| `build.gradle.kts`, `gradle/libs.versions.toml` | Share plugin classpaths and pin dependency/tool versions. |
| `parikt.properties` | This build's default PARI version. A module's file can override it. |
| `parikt/` | Public Kotlin API, platform adapters, C bridges, and the canonical engine build definition. |
| `pariktAndroidNative/` | Android library helper that builds/packages GMP, PARI and JNI libraries through the NDK. |
| `gradle/parikt.*.gradle.kts` | Source installation, selection discovery, dependency routing and graph verification. These are Gradle scripts, not a custom plugin. |
| `terminalApp/` | JVM command-line application using PariKt. |
| `nativeTerminalApp/` | Standalone Linux/Windows Kotlin/Native command-line application. |
| `consoleUi/` | Shared Compose console, history/completion, engine settings, scripts and platform persistence adapters. |
| `androidApp/`, `desktopApp/`, `webApp/` | Platform application entry points and packaging around the shared console. |
| `examples/consumer/` | An independent consumer build showing how to install the pinned PariKt sources. |
| `examples/module-versions/` | Compatibility fixtures: multiple applications, shared libraries and deliberately competing version requirements. Some fixtures retain an experimental provider scaffold. |
| `tests/build_configuration/` | Scripts that test Gradle selection, native/source caches, packaging and real consumer builds. |
| `docs/` | This guide and archived handoff history. Historical notes can contain superseded implementation details. |

The two examples folders have different purposes. Use `consumer` to learn installation; use
`module-versions` to understand and reproduce compatibility scenarios. Application demos exercise
the real product modules, while disposable fixtures let tests change dependency graphs freely.

### Shared sample application

`consoleUi/commonMain` owns the sample behavior. `ConsoleApp.kt` renders the console and routes
to the settings/scripts pages in `ConsolePages.kt`. `ConsoleState.kt` keeps cursor-aware command
completion, session history, output, initialization and engine restart separate from rendering.
`ConsoleSession.kt` adapts the public PariKt API; evaluation and restart stay on the UI thread
that initialized PARI. Changing pages preserves that session. Running a saved script completes
before leaving its page, so navigation cannot cancel the operation.

`ConsolePreferences.kt` validates stack sizes and encodes settings/scripts as versioned,
length-prefixed fields, preserving multiline Unicode GP code. Small platform `ConsoleStore`
actuals persist that common format: Android SharedPreferences, a replaced file under the JVM
user's `.parikt` directory, or browser localStorage shared by JS/Wasm on the same origin. History
and output are not persisted. Saving must succeed before in-memory preferences change; load/save
errors remain visible. Engine settings restart PARI, clearing its variables/functions.

Run these checks after changing the shared sample:

```powershell
.\gradlew.bat :consoleUi:jvmTest :desktopApp:test :androidApp:assembleDebug :webApp:compileKotlinJs :webApp:compileKotlinWasmJs --no-parallel --console=plain
```

The state tests cover draft restoration, cursor completion, script persistence/renaming/deletion,
invalid settings and storage failures. The JVM store test checks file replacement and reload.
The desktop integration test uses the selected real PARI engine to exercise examples, completion,
history, output clearing, saved function execution and settings/restart. It uses in-memory
preferences to avoid modifying a developer's saved data, and captures phone-size screens under
`desktopApp/build/reports/sample-ui/`. Android packaging and web compilation supplement these
tests; they do not establish device/browser runtime coverage for a new UI change.

### Where Kotlin code lives

Under `parikt/src`, `commonMain` contains `PariKt`, extensions such as `getVersionName()`, and the
internal `expect object PariNative`. An `expect` declaration describes operations whose actual
implementation depends on the target. Each backend supplies its own `actual object PariNative`.

| Source set | What to read |
| --- | --- |
| `commonMain` | Public initialization/evaluation/close contract and platform adapter interface. |
| `jvmMain` | Desktop native-library loading, temporary directory setup and JNI declarations. |
| `androidMain` | Android JNI loading, AndroidX Startup initializer, manifest and NDK makefiles. |
| `nativeMain`, `nativeInterop` | Kotlin/Native C calls and the cinterop definition. |
| `webMain` | Shared JS/Wasm engine adapter and external JavaScript module interface. |
| `jsMain`, `wasmJsMain` | Platform-specific conversion of the engine-loading Promise into suspension. |
| `commonTest` | Shared boundary scenarios used by JVM, Native and web tests. |
| `jvmTest`, `nativeTest`, `webTest` | Backend test runners and platform-specific checks. |

## 2. How a Kotlin call reaches PARI

The common call path is:

```text
Application -> PariKt -> platform PariNative -> platform bridge -> shared C wrapper -> PARI -> GMP
```

PARI evaluates GP expressions; GMP supplies multiprecision arithmetic used by PARI. The bridge
turns Kotlin/JavaScript strings and results into C calls. The shared wrapper
[`paridroid.c`](../parikt/native/paridroid/paridroid.c) initializes PARI, captures printed results,
handles recoverable GP errors and enumerates commands. It uses PARI's actual command-table bounds
for the selected release. It is shared across backends, so changes here need broad verification.

### JVM: dynamically loaded libraries through JNI

[`build-jvm.sh`](../parikt/native/build-jvm.sh) builds GMP/PARI and the JNI wrapper for Windows or
Linux x86_64. [`jni_bridge.c`](../parikt/native/paridroid/jni_bridge.c) implements the native methods
declared in `jvmMain/PariNative.kt`. The Gradle integration prepares the selected engine and sets
`java.library.path` on participating `JavaExec` and `Test` tasks.

Windows loads the PARI DLL shipped alongside `paridroid.dll`; its basename can vary by PARI
release. Linux loads GMP, PARI and the wrapper as shared libraries. The Kotlin classes themselves
are kept identical between providers; the selected native libraries supply the mathematics engine.
Do not try to load two PARI releases into one application's process.

JNI entry points are private and consistently named `nativeInit`, `nativeEval`,
`nativeGetSupportedCommands`, `nativeClose`, `nativeGetVersionCode` and
`nativeSetTemporaryDirectory` on JVM/Android. The internal Kotlin adapters expose ordinary
`init`, `eval`, `getSupportedCommands`, `close` and `getVersionCode` operations on every target.

### Android: JNI libraries packaged for the device ABI

Android uses the same JNI/shared-C bridge with an Android Kotlin adapter.
[`build-native.sh`](../parikt/native/build-native.sh) builds GMP/PARI for the selected ABIs.
The `pariktAndroidNative` helper runs `ndk-build` using
[`Android.mk`](../parikt/src/androidMain/jni/Android.mk), which compiles the JNI wrapper and links
the prepared libraries. The application packages the selected ABI's `.so` files.

AndroidX Startup only records the host application's cache directory for GP temporary files.
It loads no JNI libraries and initializes no engine. The application calls suspending
`PariKt.init()` at its chosen time; the adapter then loads libraries, applies that path and
initializes PARI with the requested stack size. There is no hardcoded consumer application ID. Android keeps
minSdk 21 and supports `arm64-v8a` and `x86_64`; the proof apps build separate APKs for those ABIs.
The helper can prepare missing native dependencies during configuration before the Android NDK
model needs them. Consequently, a cold Android configuration/sync can compile C dependencies.

### Kotlin/Native: static libraries in the executable

[`build-kotlin-native.sh`](../parikt/native/build-kotlin-native.sh) compiles GMP, PARI and the
[`Native C bridge`](../parikt/native/kotlin-native/bridge.c) into `libparikt.a`.
[`pari.def`](../parikt/src/nativeInterop/cinterop/pari.def) tells Kotlin cinterop how to expose
the bridge functions and embed the static library in the interop klib. A klib is a Kotlin library
artifact containing compiler metadata/code and, here, the native archive needed at final link.

`nativeMain/PariNative.kt` calls these functions directly. The final Linux or Windows executable
contains the selected engine; it does not load the JVM DLLs at runtime. KGP prepares its managed
toolchain, and a deferred argument provider reads the generated `konan.txt` only after preparation.
The adapter checks that PARI operations stay on the OS thread that initialized it.

### JS and WasmJS: an Emscripten engine behind a JavaScript adapter

[`build-web.sh`](../parikt/native/build-web.sh) compiles GMP/PARI and the
[`web C bridge`](../parikt/native/web/bridge.c) with Emscripten. The outputs include a JavaScript
module, a `.wasm` binary and `parikt.mjs`, which wraps the exported C functions.
Both Kotlin/JS and Kotlin/WasmJS call this engine through JavaScript interop. Even the Kotlin/JS
target therefore requires WebAssembly support in its runtime.

The engine factory loads asynchronously, so public `PariKt.init()` is suspending on every target.
Once loaded, evaluation is synchronous. Heavy GP work can block the browser UI thread.
Generated Kotlin bindings import a version-specific npm package such as `@parikt/engine-2-19-0`.
Gradle stages package metadata separately from the actual payload, then supplies the selected
payload for execution/bundling. A browser distribution must be served over HTTP.

### API lifecycle

In a coroutine or suspending function:

```kotlin
PariKt.init()
try {
    println(PariKt.eval("factor(123456)"))
} finally {
    PariKt.close()
}
```

Every target, including Android, requires an explicit `init()` call. Android Startup supplies
the cache path automatically; leave its manifest entry enabled. Calls to `eval()` and
command listing require initialization. Repeated `close()` and close-before-init are safe;
reinitialization after close is supported. Empty input returns an empty string. GP `quit()`
produces a recoverable evaluation error; `PariKt.close()` releases the engine. Stack sizes must
be positive and fit backend limits; the default is 4,000,000 bytes. Evaluation returns GP's
formatted text, including recoverable error diagnostics. It is not a structured result API or
a promise that arbitrary native memory failures are recoverable. Treat the engine as shared
process state and serialize access; a suspending initializer does not make evaluation concurrent.

## 3. Version selection and the Gradle scripts

Pin PariKt's source revision independently of `pari.version`. For example, the root can select
2.15.0 while `desktopApp/parikt.properties` selects 2.19.0. The terminal and desktop applications
can share `consoleUi`, yet each receives one final engine. A shared library with an explicit
2.15.0 requirement cannot be silently used by an application explicitly requiring 2.19.0:
that dependency path fails with a conflict diagnostic.

Without a root file, every participating module needs its own selection. A sibling override
does not supply a default. An unrelated module does not become a participant merely because a
root default exists. Internal `parikt.participants` is the set of project paths that depend on
PariKt directly or through other project dependencies; it controls runtime/package wiring.

| Script | Responsibility |
| --- | --- |
| `parikt.settings.gradle.kts` | Installation entry point: source location, catalog, repositories, default modules and selection/routing setup. |
| `parikt.properties.settings.gradle.kts` | Parses/validates version files and reports obsolete or invalid configuration. |
| `parikt.selections.settings.gradle.kts` | Discovers module directories/selections and actual participation; supplies `pariktSelections`. |
| `parikt.jvm.settings.gradle.kts` | Creates version-provider/helper projects, installs platform routing hooks and identity checks. Its name reflects its original JVM implementation, but it now coordinates all targets. |
| `parikt.jvm.gradle.kts` | JVM/common dependency substitution, explicit-requirement/provider conflict checks, JVM launch/test wiring and graph diagnostics. |
| `parikt.native.gradle.kts` | Native graph/final-link checks, including the selected embedded static archive. |
| `parikt.web.gradle.kts` | Web graph and selected engine-package checks. |
| `parikt.android.gradle.kts` | Android graph/helper selection checks. |
| `parikt.selector.gradle.kts` | No-root-default placeholder with useful errors for attempts to build an unspecified default engine. |
| `parikt.konan-arguments.gradle.kts` | Deferred, configuration-cache-compatible Kotlin/Native command arguments. |

### What an internal generated project contains

The default engine is `:parikt`. Another selected version has a path such as
`:pariktEngine_2_19_0:parikt`; the parent is just a namespace. Settings copies the canonical
[`parikt/build.gradle.kts`](../parikt/build.gradle.kts) into the generated project's directory and
supplies its version through extra properties. It also creates the Android manifest/helper script
when needed. The project references the original Kotlin/C sources; it is not a copied source tree.
Do not edit generated files: settings regenerates them.

This is an ordinary Gradle project supplying a version-specific implementation. Providers retain
the same Kotlin class/module identities, allowing a shared library compiled against one provider
to be used with the application's selected provider. Routing replaces inherited implementations;
explicit competing requirements/providers are rejected. Native checks also inspect embedded
archives, and web checks inspect npm dependencies/bundles. One provider is reused for a selected
version within the build. This is build-time selection, not a runtime version switch.

Android is configured when the root shares AGP; Kotlin-only consumers can omit AGP. There is no
custom Gradle plugin or binary-only Maven installation. Configuration caching is opt-in; use
`--configuration-cache --configuration-cache-problems=fail` to verify it. It skips repeat Gradle
configuration when inputs are unchanged, independently of whether C engines are already compiled.

## 4. Downloads, caches and generated output

Downloading a source version, compiling an engine and staging it for a consumer are different
operations. A forced compilation should still reuse an already acquired source revision.

| Layer | Typical location and purpose |
| --- | --- |
| Canonical PARI sources | Build host's `~/.cache/parikt/sources`: one Git object store and pristine snapshots keyed by resolved commit. WSL paths refer to the Linux user's home. |
| Backend work/toolchains | `~/.cache/parikt-jvm-native`, `parikt-kotlin-native`, `parikt-web`, `paridroid-native`: mutable backend workspaces and prepared tools/stages. |
| Shared compiled JVM engines | `~/.cache/parikt/engines/jvm`: fingerprinted compatible engine entries reused across consumers; `PARIKT_ENGINE_CACHE_DIR` can relocate this cache. |
| Repository engine outputs | `parikt/.native/jvm-<version>/<host>`, `native-<version>/<host>`, `android-<version>/<abi>`, `web-<version>`. |
| External consumer engine outputs | Consumer's `build/parikt/native/`, keeping generated engines out of the pinned source checkout. |
| Default/scoped module outputs | External default modules under `build/parikt/modules/`; generated scoped projects under `build/parikt/engineProjects/`. Android helpers use their own version-specific output directories. |
| Web packages | Consumer's `build/parikt/web-engines/<version>`: staged version-specific npm package and payload. |

Source acquisition is shared by all four native builders through `pari-source.sh`. Backends use
separate mutable workspaces so patches do not contaminate the canonical snapshot. Fingerprints
include relevant source revision, bridge/build recipe, compiler, target/ABI and options. JVM
compiled-engine entries additionally track a generation so another consumer can refresh after a
forced replacement. Locks serialize writers to the same cache/workspace.

Reuse means compatible source/build work is reused, not that every output path is the same file.
ABI/target outputs and consumer copies are intentional. Do not remove fingerprint inputs just to
obtain a cache hit. A native task can run and print a cache-hit message without Gradle marking
the task `UP-TO-DATE`: the builder validates its own cache on each requested invocation.

## 5. Building and testing

Run commands from the repository root. The examples below use PowerShell on the verified
Windows x86_64 + WSL setup. Use PowerShell 7 (`pwsh`) for the full script collection; some helpers
also support Windows PowerShell 5.1. Linux uses `bash ./gradlew` and its local prerequisites;
not every script/host combination has been exercised. The pinned baseline is Gradle 9.6.0,
Kotlin 2.4.21, AGP 9.4.1, SDK 36 and NDK 28.2.13676358. Consult the README's backend prerequisites
before the first native build. Initial builds can download toolchains and take substantial time.

The NDK SDK version (`ndk`) and its Linux download archive label (`ndkArchive`) are maintained
together in `gradle/libs.versions.toml`. Gradle and Android verification read the SDK version;
the WSL/Linux bootstrap reads the archive label. Update both catalog entries for an NDK upgrade.

### Start with the backend you changed

```powershell
# JVM API and isolated native-boundary regressions
.\gradlew.bat :parikt:jvmTest --no-parallel

# Native Linux through WSL, then Windows on the host
.\gradlew.bat :parikt:verifyLinuxX64 :parikt:verifyMingwX64 --no-parallel

# Both web adapters in Node; direct engine smoke is available separately
.\gradlew.bat :parikt:jsNodeTest :parikt:wasmJsNodeTest :parikt:verifyWebNative --no-parallel

# Real Android application build; this does not execute the app
.\gradlew.bat :androidApp:assembleDebug --no-parallel

# Discover effective selections and inspect real application graphs
.\gradlew.bat pariktSelections :terminalApp:verifyPariJvmGraph :androidApp:verifyPariAndroidGraph
```

`EngineBoundaryChecks` covers large command listings, recoverable quit, empty input, invalid sizes
and lifecycle/reinitialization. JVM tests isolate scenarios in bounded child processes for every
configured engine, so a native crash becomes a test failure instead of killing the test worker.
Native/web runners reuse the public contract cases and add backend checks. Browser tests are
`:parikt:jsBrowserTest` and `:parikt:wasmJsBrowserTest`; they require a Chromium executable
(`CHROME_BIN` can select it). Gradle test reports are under the module's `build/reports/tests/`
and `build/test-results/`; custom Native verification also prints directly to the Gradle log.

### Prepare engines before reuse acceptance

Several acceptance suites intentionally fail if a supposedly reusable engine rebuilds or fetches
sources. They are not cold-install tests. The production Native/web/Android fixtures seed prepared
artifacts from `parikt/.native`; inspect the suite's opening setup if a seed file is missing.
For the repository's current default 2.15.0 / desktop override 2.19.0 configuration, prepare both:

```powershell
foreach ($engine in @(':parikt', ':pariktEngine_2_19_0:parikt')) {
    .\gradlew.bat "${engine}:buildJvmNativeDependencies" "${engine}:buildLinuxX64NativeDependencies" "${engine}:buildMingwX64NativeDependencies" "${engine}:buildWebNativeDependencies" --no-parallel
    if ($LASTEXITCODE) { throw "Engine preparation failed: $engine" }
}
.\gradlew.bat :pariktAndroidNative:buildNativeDependencies :pariktEngine_2_19_0:androidNative:buildNativeDependencies --no-parallel
```

These project paths depend on the current selections; use the corresponding provider paths if
you change them. The scripts' fixed demo expectations are documented in their opening checks.

### Verification script reference

All paths below are under `tests/build_configuration/`. Run a PowerShell script with
`pwsh -File tests/build_configuration/<name>.ps1`. Prefer `-Production` when verifying the shipped
installer; omitting it on the three platform suites tests the older compatibility scaffold.

| Script / options | What it proves; notable setup or effects |
| --- | --- |
| `verify_harness.ps1` | Shared tool-catalog version/missing-key handling, real Gradle argument/log paths, expected failures, output assertions and fixture copy/cleanup boundaries. No PARI compilation. |
| `verify_cold_jvm.ps1 [-Version 2.19.0] [-GradleExecutable <path-to-bin/gradle>] [-ResumeLogDirectory <previous-run>]` / `verify_cold_jvm.sh` | Cold Linux JVM consumer with empty Gradle dependency, PARI source, compiled-engine, native-work and Konan cache directories; then warm strict configuration/native-cache reuse. Windows uses WSL. Requires installed Linux JDK/JNI headers, GCC, Git, curl, tar/xz, make and m4. Default mode also downloads the Gradle distribution; the optional Linux `bin/gradle` script reuses an installed distribution as a prerequisite. Downloads real dependencies and compiles PARI/GMP. Retains a new durable `~/.cache/parikt-verification/cold-jvm.*` root and logs under `build/cold-jvm/`; existing caches and examples are untouched. Resume requires the same version and `-GradleExecutable` and preserves original cold evidence. This is not an empty operating system or all-target setup test. |
| `verify_properties.ps1` | Missing, empty, invalid, unknown and obsolete property diagnostics before native setup. |
| `verify_module_selections.ps1` | Discovery/inheritance/overrides, nested/custom module paths, participation and missing-selection diagnostics without native compilation. |
| `verify_production_module_only.ps1` | Real installer in Kotlin-only rootless JVM consumers: shared/independent versions, conflicts, selectors, reuse and actual Java compilation. Requires both JVM engines prepared. |
| `verify_application_versions.ps1` | Real `terminalApp`/`desktopApp`/`consoleUi` behavior, both task orders, class identity, reuse and conflicts. Temporarily changes real module files and restores original bytes. Requires the documented demo defaults. |
| `verify_configuration_cache.ps1 [-Platforms]` | Strict cache storage and actual reuse across version-file additions/edits/removal/errors; unrelated JavaExec/Test tasks keep their library paths and build no engine. `-Platforms` adds the real root JVM/Native/Android/web graph. Requires prepared engines. |
| `verify_native_module_versions.ps1 -Production` | Real Linux/Windows Native applications on both versions, metadata identity, final-link archive hashes, orders, reuse, rootless chains and conflicts. Uses prepared C artifacts; Windows/WSL executes both hosts. |
| `verify_web_module_versions.ps1 -Production` | Both Node adapters, browser bundles, npm/bundle selected-engine identity, orders, reuse, rootless chains, conflicts and portable lockfiles. Retains browser artifacts; does not itself open pages in a browser. |
| `verify_android_module_versions.ps1 -Production [-AndroidSdk <path>]` | Both versions and ABI split APKs, class identity, exact native payload/hash checks, reuse, rootless chains and provider/helper conflicts. Builds/packages; does not run a device. Retains final proof APKs. |
| `verify_android_runtime.ps1 -ApkDirectory <path> -DeviceSerial <serial> -AndroidSdk <path>` | Runs retained proof APKs on a booted disposable supported-ABI device: no startup engine/JNI loading, explicit init with custom size, reinit, version, arithmetic, factorization, enumeration, empty input and error recovery. Checks process log ordering; rejects preinstalled proof packages and removes its new installations. Does not create an emulator. |
| `verify_module_versions.ps1` | Independent JVM scaffold: shared-library compatibility, later consumer reuse, forced rebuild with one producer, generation reuse, strict conflicts and module-only runtime. Intentionally recompiles an engine. |
| `verify_source_cache.ps1` / `verify_source_cache.sh` | Concurrent acquisition, pristine snapshots, repair, moving revisions, legacy imports and retry/cleanup using a local fake Git remote. Bash worker also checks builder syntax; PowerShell selects WSL on Windows. Does not compile PARI. |
| `verify_jvm_cache.ps1` / `verify_jvm_cache.sh` | Real concurrent Windows engine builds from separate workspaces: one compiled producer, forced replacement/generation refresh, repair and compatible reuse. Requires Windows/WSL, `JAVA_HOME`, and an already prepared compiler/GMP source cache. Can take time. |
| `verify_native_lock.sh` | Focused Bash test of shared work-lock serialization and cleanup. From Windows: `wsl bash tests/build_configuration/verify_native_lock.sh`. |
| `verify_consumer.py` | Full standalone consumer on 2.15.0 and 2.19.0: JVM, Native, Node adapters and Android helper assembly. Temporarily changes `examples/consumer/parikt.properties` and restores it. Can download/build engines. Run with `python tests/build_configuration/verify_consumer.py`. |
| `verify_bridge_cache.py` | JNI source edit changes the compiled-engine fingerprint, restoration restores it, and unchanged reuse follows. Requires an already built standalone JVM consumer. Temporarily appends a C comment and restores exact bytes; run without other edits/builds touching that source. |

The cold JVM verifier hides legacy `git -C` source-cache lookups outside its isolated root;
the production source helper normally imports those Git objects to avoid duplicate downloads.
Upstream fetches still use real Git. Without this test-only filtering, an empty new acquisition
store would not prove a fresh source download. End-to-end cold verification passed on 2026-11-05
for PARI 2.19.0 on WSL Linux x86_64, including wrapper/dependency/source downloads, native
compilation, runtime, warm reuse and real resume. Installed JDK/C tools remain prerequisites;
see the handoff for logs and remaining platform limits.

For example, after preparation, run these sequentially:

```powershell
pwsh -File tests/build_configuration/verify_harness.ps1
pwsh -File tests/build_configuration/verify_production_module_only.ps1
pwsh -File tests/build_configuration/verify_application_versions.ps1
pwsh -File tests/build_configuration/verify_configuration_cache.ps1 -Platforms
pwsh -File tests/build_configuration/verify_native_module_versions.ps1 -Production
pwsh -File tests/build_configuration/verify_web_module_versions.ps1 -Production
pwsh -File tests/build_configuration/verify_android_module_versions.ps1 -Production
```

Stop on a failed command and inspect its log before continuing. `PASS` can describe an expected
rejection: negative cases must fail Gradle with the intended diagnostic and avoid native work.
The suite checks this distinction; a standalone `BUILD FAILED` in a negative-case log is expected.

### Logs, cleanup and avoiding interference

`harness.ps1` is dot-sourced by nine suites, not run as a standalone test. It launches Gradle with
merged disk logs (compiler daemons can otherwise keep pipe handles open), checks output, copies
fixtures without generated caches, and validates fixture paths before recursive cleanup.
Suite-specific assertions and real-file restoration remain in the individual scripts.

Disposable suites place fixtures/logs below `build/` and remove fixture directories in `finally`,
retaining logs. Look for the path printed with each `PASS` or exception. Common roots include
`configuration-cache-tests`, `production-module-only`, `application-version-tests`, `np` (Native),
`wp` (web), `android-tests`, `module-selection-tests`, `module-version-tests` and
`consumer-verification`. Android retained APKs are under the run's `apks/`; pass that directory to
the runtime verifier. Low-level Bash checks may clean temporary logs when they exit.

Run root builds serially. `--no-parallel` controls tasks within one build; it does not prevent two
separate Gradle invocations from writing the same NDK or web compiler outputs. Do not run the
real-application or source-edit suites alongside a build using those files. Independent disposable
fixtures have separate outputs, but still share native work caches and may wait on their locks.
Do not edit generated providers or delete all caches as a first response to a failure. Check the
selection, prerequisite/seed file, engine fingerprint and task log first.

## 6. Making a change without losing coverage

| Change | Start with |
| --- | --- |
| Common API or shared C wrapper | JVM subprocess tests, Native/web tests, Android build and runtime smoke where available. |
| JNI bridge or JVM loader | JVM subprocess tests; Android verification too when the shared JNI C file changes. |
| Native bridge/cinterop/linking | Native backend tests and production Native version suite. |
| Web bridge/adapter/package generation | Both Node tests, production web suite; browser tests/manual page use when browser behavior changes. |
| Settings/routing/provider build definition | Property/discovery checks, production module-only/real-app/platform suites and strict cache store/reuse. |
| Cache/source/build scripts | Source/lock/JVM-cache checks as relevant; real prepared-engine reuse and backend smoke. |
| Shared test harness | `verify_harness.ps1`, its PowerShell 5.1 run when applicable, and the affected acceptance suites. |

Preserve all targets, both examples folders, Android minSdk 21, one selected engine per final
application and explicit conflict rejection. Update the handoff and module checklist with actual
results. Valid up-to-date test results are different from a forced rebuild; record which occurred.
Device/runtime coverage is also distinct from successful assembly: ARM64/API21 runtime, fresh
machines and broader IDE workflows retain the limits recorded in the handoff. GUI sync and the
API36.1 x86_64 demo run/Kotlin breakpoint/step/resume checks pass. This source integration does
not currently provide a published binary distribution; its public Kotlin API uses one selected
engine per application.
