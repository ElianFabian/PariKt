# PariKt handoff and continuation plan

## Handoff maintenance rule

Whenever project code, build configuration, or documentation changes, update this handoff in the
same change: check off completed checklist items, revise any affected facts or decisions, and record
verification results or blockers. Do not mark an item complete without confirming it.

## Verified reference: `paridroidgp`

`paridroidgp` is an Android library module inside a repository that also contains the Paridroid app.
Its Android namespace and the app's `applicationId` are both `fr.ubordeaux.math.paridroid`. That
identity match matters: the native wrapper sets `GPTMPDIR` to
`/data/data/fr.ubordeaux.math.paridroid/cache`, and the native build configures PARI with the same
package-specific data directory. Those paths work in that paired app setup; they should not be
copied as assumptions for an arbitrary app consuming PariKt.

The module uses the standard Android library plugin with Kotlin Android, `externalNativeBuild`/
`ndk-build`, and `Android.mk`. `Android.mk` declares prebuilt `libgmp.so` and `libpari.so` for the
selected ABI, then compiles the JNI bridge together with `paridroid.c` into `libparidroid.so`. The
native script builds GMP and PARI only; the Android Gradle/NDK build compiles the wrapper and JNI
bridge.

Its `build.gradle.kts` behavior is:

1. Read `pariVersion` and `nativeAbis` from Gradle properties. Default ABIs are `arm64-v8a` and
   `x86_64`; NDK version is `28.2.13676358`; minimum Android API is 21.
2. Prefer `.native/android-$pariVersion` when it contains GMP and PARI libraries for all selected
   ABIs. Copy valid legacy `.native/android-pari-$pariVersion` or `.native/pari-$pariVersion`
   outputs there once, preserving the old caches. For PARI 2.17.1 only, it can fall back to valid
   bundled libraries under `src/main/jni`.
3. If dependencies are missing at configuration time, run `native/build-native.sh` immediately using
   WSL on Windows and bash locally elsewhere. This bootstrap path ignores `nativeRunner`, streams
   output, and fails Gradle if the process fails. As a result, Docker does not replace WSL for a
   fresh Windows checkout when dependencies are absent.
4. Register `buildPariNative` and wire it to Android NDK tasks when dependencies are missing or
   `nativeForce` is present. This task supports `local`, `wsl`, and `docker`; its default is `wsl`
   on Windows and `local` elsewhere. The later task runner is separate from the configuration-time
   bootstrap above.
5. Pass the chosen dependency directory to `Android.mk` using `NATIVE_DEPS_DIR`.

`ParidroidGP` calls `loadLibrarySafe` for `gmp`, `pari`, and `paridroid` in its object initializer
and catches `Throwable`. `PariNative` has a second object initializer that loads the same three
libraries and catches `Exception`; it runs when `ParidroidGP` first calls a native method. Thus
loading is duplicated on the normal path, with different failure handling. `ParidroidGP` checks
initialization for eval and command listing. Its close method calls native close and resets its
initialized flag. The comments describe init as one-shot because PARI owns global state; the code
itself permits a later init after close. Preserve this as observed behavior until tested before
changing lifecycle semantics.

## PariKt current state

PariKt is a KMP library in `:parikt`. Android and JVM are implemented in the documented host
matrix. Kotlin/JS and Kotlin/WasmJS now have working Node and Chromium-browser consumer bindings
around an Emscripten PARI engine. Kotlin/Native linuxX64 uses a statically linked C interop
backend and has a separate native terminal demo. Other Kotlin/Native targets are not configured.

Common API and expect declaration: `src/commonMain/kotlin/com/elianfabian/parikt/`. Android JNI
Kotlin: `src/androidMain/kotlin/`. The NDK makefiles remain under `src/androidMain/jni/`; shared
wrapper and native dependency build script are under `native/paridroid/` and
`native/build-native.sh`.

PariKt now uses `com.android.kotlin.multiplatform.library` with the KMP `android {}` target DSL.
Because that plugin does not support `externalNativeBuild`, a separate `:pariktAndroidNative` module
applies `com.android.library` and owns the existing `ndk-build` project and native dependency
bootstrap. PariKt consumes that bridge module as an `androidMain` dependency; this preserves NDK
packaging without relying on AGP's legacy KMP Android integration. The Android app relies on AGP
built-in Kotlin. The selected AGP version is 9.4.1 with Gradle 9.6.0; Kotlin is set to 2.4.21.
`:androidApp:assembleDebug` succeeds with this migration, and the APK contains `libgmp.so`,
`libpari.so`, and `libparidroid.so` for arm64-v8a and x86_64. PariKt has no bundled-prebuilt
fallback.

The `:androidApp` module is a Kotlin/Jetpack Compose GP console for emulator checks. It uses
Material 3, Compose BOM 2025.06.01, and Activity Compose 1.10.1 so both the demo app and PariKt can
target minSdk 21. Its application ID differs from the PariKt namespace, exercising AndroidX
Startup's merged provider and app-specific cache directory. On launch it runs a visible `2 + 2`
self-check and displays a decoded PARI version with ready/error status. Users can enter commands,
run example chips, review scrollable output history, tap a previous command to edit/rerun, and clear
results. The API-21 AVD on this machine is 32-bit x86, outside PariKt's native ABI list; runtime
verification uses an x86_64 API 36 emulator.

The first `:androidApp:assembleDebug` log exposed SDK-level requirements, a missing `paridroid.h`
include path, and Java/Kotlin JVM targets 11 vs 21. The app uses compileSdk 36 and JVM 17 for both
compilers. AndroidX raised the default minSdk for new releases from 21 to 23; to retain the user's
minSdk 21 preference, the demo pins Compose BOM 2025.06.01 and Activity Compose 1.10.1. With these
versions the app and library both declare minSdk 21, and `:androidApp:assembleDebug` succeeds.
`Android.mk` adds the wrapper header directory. Earlier Kotlin-only root plugin centralization
failed; the current root shares both AGP and Kotlin plugins and resolves the multiple-load warning
(see below). The first emulator launch revealed the
initializer metadata was absent from the merged manifest because PariKt's manifest used the legacy
`src/main` path. It has been moved to `src/androidMain/AndroidManifest.xml`. On an x86_64 API 36
emulator using the API-21-compatible dependency versions, Startup initialization succeeded, `2 + 2`
returned 4, `factor(123456)` returned the expected factors, version code 135425 was displayed, and
the close button reported successful shutdown. The installed API-21 AVD is x86, which PariKt does
not currently package; direct API-21 runtime testing therefore remains open. Reinitialization after
close has not been tested.

The initial AGP 9.4.1 sync log showed deprecated opt-out properties and legacy variant API calls.
The opt-outs were removed; the dependency flag was replaced with the recommended
`android.dependency.useConstraints=false`. The app's Kotlin Android plugin was removed in favor of
built-in Kotlin. The KMP target was migrated to the Android-KMP library plugin, while
`externalNativeBuild` and PARI/GMP bootstrap moved to `:pariktAndroidNative`, a standalone Android
library dependency. Version pins are AGP 9.4.1, Gradle 9.6.0, and Kotlin 2.4.21. The Android-KMP
plugin avoids build variants, so its local dependency selection explicitly prefers the helper
library's debug variant and falls back to release. `:androidApp:assembleDebug` passes and its output
contains all three PariKt native libraries for both configured ABIs. The pasted AGP opt-out and
legacy-variant warnings no longer appeared in the build output. The official KMP compatibility table
currently lists Kotlin 2.4.20–2.4.21 through AGP 9.3.1, so AGP 9.4.1 is newer than that listed range
even though this project's sync, assemble, and emulator smoke run pass. Recheck the matrix when
Kotlin publishes an update. The multiple-Kotlin-plugin-load warning is now resolved: root
`build.gradle.kts` declares all three AGP plugins and all three Kotlin plugins with `apply false`;
the four modules apply versionless plugin IDs. Both plugin families must be available from the
shared parent classloader. Earlier attempts shared only Kotlin, leaving AGP's types in child
classloaders; the resulting `BaseVariant`/`BaseExtension` lookup errors were incorrectly treated
as evidence of an AGP API incompatibility. Sharing both plugin families passes `help --warning-mode all`,
`:androidApp:assembleDebug`, and `:terminalApp:compileKotlin` with no multiple-load warning.
AGP 9.4.1, Kotlin 2.4.21, built-in Android Kotlin, and the Android-KMP target are preserved.
The migrated app was
installed and launched on the API 36.1 x86_64 emulator; AndroidX Startup loaded GMP/PARI/JNI, and
the UI displayed PARI 2.17.1 plus the successful `2 + 2 returned 4` check. The emulator was shut
down afterward.

Kotlin standard-library artifacts are supplied by the Kotlin Multiplatform plugin for each target.
Do not add a separate Wasm stdlib dependency; the explicit
`org.jetbrains.kotlin:kotlin-stdlib-wasm:2.2.10` coordinate failed resolution during sync. The
explicit stdlib dependencies and unused catalog aliases have been removed. The coroutines dependency
remains on the JVM and Wasm source sets.

PariKt's Android `PariNative` has a single object initializer that loads `gmp`, `pari`, and
`paridroid`; its `loadLibraries()` expect/actual method is a no-op after object initialization. The
loader rethrows `UnsatisfiedLinkError` with the library name and ABI/dependency guidance. Runtime
library loading and GP evaluation succeeded on the x86_64 emulator. Do not carry over `paridroidgp`
's duplicate loader.

The JNI C bridge now explicitly includes `<jni.h>`. The reference gets that include through its
generated `fr_ubordeaux_math_paridroid_PariNative.h`; PariKt does not have an equivalent generated
header. Native compilation succeeded for `arm64-v8a` and `x86_64`.

Android callers are initialized automatically by `PariKtInitializer`, registered in
`src/androidMain/AndroidManifest.xml` through AndroidX Startup Runtime 1.2.0. It receives the host
app's application `Context`, passes the app cache path through JNI, and sets `GPTMPDIR` before
`pari_init`. The initializer runs before the host app's `Application.onCreate()`. This is the only
Android initialization path; it uses a 4,000,000-byte stack. The merged provider and GP evaluation
were verified in the demo host app.

`setTemporaryDirectory` is currently an expect/actual method: Android delegates to the C setter, JVM
creates a writable host temp directory and sets the native `GPTMPDIR`. JS/WasmJS use Emscripten's
in-memory `/tmp`, configured by the web C bridge before `pari_init`; the web setter accepts only
that virtual path. There is no Kotlin/Native actual because those targets are not configured.
The C setter uses `setenv` on POSIX/Emscripten and `_putenv_s` on Windows.

The native build's `--datadir` is separate from `GPTMPDIR`: PARI uses `datadir` for optional
architecture-independent data packages including `elldata`, `galdata`, `galpol`, and `seadata`
([PARI GP defaults](https://pari.math.u-bordeaux.fr/dochtml/ref/GP_defaults.html)). PariKt's native
build currently installs only headers and `libpari`, not those data packages. Basic arithmetic does
not depend on them, but calls that use those optional datasets may. The configured path inherited
from Paridroid should not be mistaken for an app cache path or proof that those files are shipped.

## Completed groundwork

- [x] Read the `paridroidgp` Gradle build, native build script, NDK makefiles, JNI bridge, Kotlin
  wrapper, and paired app package configuration.
- [x] Compare those files with PariKt's Android/KMP Gradle, JNI, native wrapper, and version
  configuration.
- [x] Record verified reference behavior and open implementation questions in this handoff.

## Continuation checklist

### Phase 1 — Compare and align Android build behavior

- [x] Add a configuration-time native dependency bootstrap before Android Studio's NDK model
  generation, using the selected version/ABIs and configured `nativeRunner`.
- [x] User confirmed Gradle sync works after removing the unresolved Wasm stdlib dependency.
- [x] Remove the unresolved explicit Wasm stdlib dependency; Kotlin Multiplatform adds the correct
  target stdlib automatically.
- [x] Validate Gradle configuration, Android Kotlin compilation, and NDK configure/build for the app
  on `arm64-v8a` and `x86_64`.
- [ ] Check task ordering against `paridroidgp`: GMP/PARI artifacts must exist before `ndk-build`
  consumes them. Confirm that tasks skip valid builds and respond correctly to `pariVersion`, ABI
  changes, and `nativeForce`.
- [ ] Decide whether PariKt needs `paridroidgp`'s bundled-prebuilt fallback. If added, use prebuilts
  only when their PARI version and every selected ABI match; otherwise build into
  `.native/android-$pariVersion`.
- [x] Name generated Android ABI artifacts `.native/android-$pariVersion`, parallel to JVM's
  `.native/jvm-$pariVersion`. Migrate complete legacy caches by copying them to the new name and
  retain the old directories. Verified the existing 2.16.0 cache migrated for `arm64-v8a` and
  `x86_64`, and `:androidApp:assembleDebug` completed with Android native dependencies up to date.
- [ ] Verify migration behavior when the legacy cache is incomplete for selected ABIs; such a cache
  should not be copied as valid and Gradle should rebuild into the new directory.
- [x] Remove Docker from PariKt's `gradle.properties` runner comment; PariKt currently supports only
  `local` and `wsl`.
- [x] Compare JNI bridge and `Android.mk` source/include paths with `paridroidgp`; add PariKt's
  missing explicit `jni.h` include.
- [x] Confirm the AAR contains `libgmp.so`, `libpari.so`, and `libparidroid.so` for both `arm64-v8a`
  and `x86_64`.
- [x] Improve PariKt's single loader to report missing/incompatible `.so` files with the library
  name and ABI/dependency guidance; emulator loading succeeded on x86_64.
- [x] Migrate away from AGP 9 deprecated variant calls: KMP Android target uses the Android-KMP
  plugin, NDK/JNI integration lives in `:pariktAndroidNative`, and the app uses built-in Kotlin.
- [x] Sync and build the AGP 9.4.1 migration; confirm the pasted deprecated-property and
  legacy-variant warnings are gone. Confirm the helper AAR's native libraries are transitively
  packaged for arm64-v8a and x86_64.
- [x] Run the migrated app on an API 36.1 x86_64 emulator; Startup initialized the native libraries
  and the UI confirmed `2 + 2 = 4`.

### Phase 2 — Resolve Android paths for a reusable library

- [x] Confirm `--datadir` is PARI's location for optional data packages, separate from `GPTMPDIR`.
- [ ] Decide which optional PARI data packages PariKt will ship/support. If any are included,
  package them and configure a valid lookup path; test data-dependent GP commands.
- [x] Add an AndroidX Startup initializer that passes the host app's cache directory through JNI
  before `pari_init`, without putting Android `Context` in `commonMain`.
- [x] Make AndroidX Startup the only Android initialization path; remove the context-taking public
  facade.
- [x] Verify Android initialization from the demo app with a different application ID; GP evaluation
  succeeds after Startup initializes with its cache directory.
- [x] Test the library from a host app whose application ID differs from `com.elianfabian.parikt`
  (`com.elianfabian.parikt.demo`).

### Phase 3 — Runtime behavior and API checks

- [x] Run the emulator smoke app on x86_64 API 36: Startup, `2 + 2`, `factor(123456)`, version code,
  and close all succeeded.
- [x] Add a minimal `:androidApp` Jetpack Compose host app for emulator smoke checks.
- [x] Replace the one-shot demo with a visible-ready-state GP console, example commands, editable
  command input, and tap-to-reuse result history; rebuilt and verified `factor(123456)` through its
  UI on the x86_64 emulator.
- [x] Fix the Compose SDK requirements, wrapper header include path, and app JVM target mismatch;
  `:androidApp:assembleDebug` succeeded afterward.
- [x] Move AndroidX Startup registration to `src/androidMain/AndroidManifest.xml` after the merged
  manifest omitted it; verified the initializer is now registered and runs.
- [x] Restore the demo app's minSdk 21 with Compose BOM 2025.06.01 and Activity Compose 1.10.1;
  `:androidApp:assembleDebug` passes with minSdk 21.
- [ ] Check behavior when reinitializing after `PariKt.close()`.
- [ ] Check native load failures, empty GP command handling, and concurrent calls. Change behavior
  based on reproducible results or a clear API requirement.
- [ ] Test another `pariVersion` through Gradle. Keep GMP pinned at 6.3.0 unless build evidence
  requires an update.
- [x] Replace the Wasm temp-directory stub with the web C bridge's explicit in-memory `/tmp`
  configuration before PARI initialization; verified by successful Node engine initialization.

### Phase 4 — Clone readiness and docs

- [ ] Confirm a clean clone can sync and build with documented prerequisites and no machine-local
  files.
- [x] Verify `.gitignore` and Git-tracked app/manifest files; document Android build/install
  commands and provide the Compose consumer app.
- [x] Mark Android target build, AAR contents, and initial runtime smoke checks complete;
  reinitialization and broader API checks remain open above.

### Phase 5 — JVM target and terminal smoke app

Current status: the KMP `jvm("jvm")` target and `jvmMain` actual exist. JVM bindings load libraries
in dependency order with `System.loadLibrary`, report load failures, and set `GPTMPDIR` to a created
`java.io.tmpdir/parikt` directory before PARI initialization. The matching native library directory
must be provided through `java.library.path`; `:terminalApp:run` sets this from its selected host
artifact directory. JNI init/set-temp calls match the bridge's `nativeInit` and
`nativeSetTemporaryDirectory` exports. A basic `:terminalApp` consumer is available.
`native/build-jvm.sh` and `:parikt:buildJvmNativeDependencies` define separate Linux x86_64 and
Windows x86_64 build paths; Windows is cross-compiled from WSL with MinGW-w64. The JNI C bridge is
shared by Android/JVM builds under `native/paridroid/`. Both host native builds and terminal smoke
runs have succeeded for PARI 2.17.1. Android's NDK-specific build remains separate.

Environment checked during JVM bring-up: Windows x86_64 with JetBrains Runtime/OpenJDK 21.0.7 and
Windows JNI headers. WSL is Ubuntu x86_64. The Windows Gradle wrapper was corrected to return
Gradle's exit status. `:terminalApp:compileKotlin` and `:androidApp:assembleDebug` completed
successfully; the latter compiled the relocated shared JNI bridge for Android. For JVM verification,
temporary toolchains were staged under `/tmp` rather than installed system-wide. The Windows
cross-build and Linux build both produced PARI 2.17.1 native libraries, and the terminal app ran
`2 + 2` and `factor(123456)` successfully on Windows and WSL/Linux. This does not verify a clean
machine's package installation flow, another PARI version, or missing-library diagnostics. README
lists the expected WSL packages.

Implementation plan, in dependency order:

1. **Support the two current development environments.** The first JVM matrix is Windows x86_64 and
   Linux x86_64 under WSL. Each host gets its own GMP/PARI/JNI artifacts; Linux `.so` files, Windows
   `.dll` files, and Android `.so` files cannot be substituted. macOS is a later matrix expansion.
   PARI recommends WSL for Windows users. Its developer guide documents MinGW cross-compilation from
   Linux, which is the basis for PariKt's Windows path.
2. **Build and cache host libraries.** `native/build-jvm.sh` selects PARI source from `pariVersion`,
   keeps GMP pinned internally, builds Linux shared libraries or cross-compiles Windows libraries
   with MinGW-w64, and caches source/staging under the WSL user's cache. Final artifacts are keyed
   in ignored `.native/jvm-<version>/<host>` directories. Android's NDK-specific
   `native/build-native.sh` remains separate. Validate MinGW shared-library output and runtime
   dependencies before treating Windows support as complete.
3. **Define and implement the desktop JNI packaging/loading contract.** The JNI bridge retains
   package `com.elianfabian.parikt.PariNative`; it moved to `native/paridroid/jni_bridge.c` for
   Android/JVM reuse. `:terminalApp:run` uses the ignored version/host directory (overridable with
   `jvmNativeDir`), and JVM actual loads host libraries in dependency order with actionable errors.
   Linux build embeds `$ORIGIN` rpath for bundled shared dependencies. Verify Windows DLL dependency
   resolution and both runtimes before treating packaging as complete.
4. **Make JVM initialization real.** The JVM actual creates `java.io.tmpdir/parikt` and sets
   `GPTMPDIR` before `pari_init`; desktop callers initialize through `PariKt.init()`. Verify runtime
   behavior and document optional data-package limits. Preserve AndroidX Startup-only Android
   initialization.
5. **Add a basic terminal consumer module.** Create a small `:terminalApp` JVM application depending
   on `:parikt`'s JVM variant. It should initialize PARI, print version and a startup `2 + 2` check,
   accept one GP command per line, print the returned output, and exit cleanly on `quit`/EOF while
   closing the engine. Keep input/output simple and independent of Android/Compose. Errors from
   native loading or initialization should be visible and actionable.
6. **Verify in increasing scope.** First compile the JVM variant, then run the terminal app on the
   selected host and verify version, `2 + 2`, `factor(123456)`, command listing if supported,
   EOF/quit cleanup, and a clear failure when native files are missing. Confirm switching
   `pariVersion` selects/builds the corresponding host artifacts and that stale outputs are not
   reused. Then add CI/build checks for each claimed OS/architecture before expanding support.
7. **Update docs and handoff as the matrix becomes real.** Document supported JVM hosts, JDK/native
   tool requirements, native artifact generation/loading, terminal run commands, and known
   data-package limitations. Keep each checklist item unchecked until the corresponding host
   execution has been observed.

Checklist:

- [x] Record current host and selected initial matrix: Windows x86_64 and Linux x86_64 under WSL;
  JDK 21.0.7 is present on Windows. (Native C toolchains are not yet available/verified.)
- [x] Define separate Windows and WSL native build workflows and cache layout keyed by PARI version
  and host; PARI uses `pariVersion`, GMP stays pinned internally. (Scripts/tasks added; native build
  execution is still unverified.)
- [x] Build host GMP/PARI and JNI bridge; validate ABI and dynamic-library dependencies. (Linux
  shared libraries and Windows DLL dependency imports were inspected; both builds completed.)
- [x] Implement deterministic JVM loading and useful failure reporting; do not swallow native load
  errors. (Both host runtime loads succeeded; missing-library path remains unverified.)
- [x] Set a writable JVM `GPTMPDIR` before `pari_init`. (The terminal runs initialized PARI on both
  hosts.)
- [x] Add `:terminalApp` JVM CLI with startup self-check, interactive command loop, and clean close.
  (Compile and smoke runs succeeded on both hosts.)
- [x] Compile `:terminalApp:compileKotlin` on Windows/JDK 21 and rebuild `:androidApp:assembleDebug`
  after moving the JNI bridge; both tasks succeeded.
- [x] Build native artifacts and run terminal smoke commands (`2 + 2`, `factor(123456)`, `quit`) on
  Windows and WSL/Linux for PARI 2.17.1.
- [x] Use `System.loadLibrary` for all JVM native loading; terminal consumer supplies the library
  directory through `java.library.path`.
- [x] Re-run the Windows terminal smoke with `System.loadLibrary` and the configured
  `java.library.path`; version, `2 + 2`, `factor(123456)`, and clean close succeeded using the
  existing native artifacts.
- [x] Attach native preparation to every terminal `JavaExec` task, including IDE-created `MainKt`
  launch tasks, so changing `pariVersion` triggers the matching host build before launch. This uses
  Gradle task dependencies; Android's separate configuration-time build exists because Android
  Studio needs the NDK model during sync.
- [ ] Verify an IDE-created JavaExec task builds and launches against a newly selected `pariVersion`.
- [x] Resolve the Kotlin plugin multiple-load warning by sharing AGP and KGP together in the root
  `apply false` declarations and applying versionless IDs in modules. `help --warning-mode all`,
  Android assemble, and JVM terminal compilation pass without the warning.
- [x] Make the Windows JVM native build bootstrap MinGW-w64 like Android bootstraps its NDK:
  use an available system compiler or download a pinned portable xPack compiler with a SHA-256
  check into the WSL native cache.
- [ ] Verify Windows JVM native build and launch with PARI 2.19.0. The portable
  MinGW bootstrap works, and a downstream workaround handles PARI's generated MinGW export-file
  failure. PARI DLL names vary by release/configuration: the bridge's import table determines the
  filename copied beside it, and the JVM discovers the `libpari*.dll`/`pari.dll` basename present.
  A 2.19.0 build exposed `libpari-gmp.dll`; the user confirmed the Windows 2.18.0 terminal now
  launches successfully with its versioned `libpari-2.18.dll`. WSL invocation in the current tool
  environment returns `Wsl/E_ACCESSDENIED`, so verify 2.19.0 on the user's machine.
- [x] Compile JVM source after making the PARI DLL basename version-aware; `:parikt:compileKotlinJvm`
  succeeds. The runtime discovers versioned names such as `libpari-2.18.dll`, as well as
  `libpari-gmp.dll`, `libpari.dll`, and `pari.dll`.
- [x] Smoke-test the terminal against the generated `libpari-2.18.dll` on Windows; the user reports
  the 2.18.0 run now works.
- [x] Use the common `PariKt.getVersionName()` API for the Android demo's visible PARI version and
  the terminal startup version line. `:androidApp:assembleDebug` passed with the cached 2.16.0
  artifacts, and `:terminalApp:compileKotlin` passed.
- [ ] Observe the terminal's `getVersionName()` line in a live Windows terminal run; a piped `quit`
  did not close the Gradle JavaExec process in the current environment.
- [x] Fix the import-table parser to consume all `objdump` output before returning the PARI DLL
  name. An early `awk exit` under `set -o pipefail` could close the pipe and fail with exit 141.
- [x] Opt in to Kotlin's expect/actual classes feature with `-Xexpect-actual-classes`; Android/JVM
  compile tasks pass and the Beta warning is gone.
- [ ] Test missing-library diagnostics and build/runtime selection with a second `pariVersion`.
- [ ] Add and verify macOS and any additional OS/architecture hosts before documenting them as
  supported.
- [x] Resume JS/WasmJS after JVM is usable; implementation and checks are recorded in Phase 6.
- [ ] Plan iOS/Kotlin Native support separately after the current web work.

## Phase 6 — JavaScript and WasmJS execution

Current priority: the user deferred macOS verification and selected JS/WasmJS as the next task.
Keep macOS Android sync/tool discovery and macOS JVM support open; they are not prerequisites
for developing the web engine on Windows/WSL.

Starting point before this implementation: only a Wasm target and nonfunctional interop stub
existed. There was no generated engine, real module import, or Kotlin/JS target. The stub loaded
twice, blocked for readiness, used unsupported external array types, and accepted temp paths
without configuring anything. It has now been replaced. `paridroid.c` remains shared C logic;
`jni_bridge.c` is Android/JVM-only.

Implementation and verification:

Implementation: `native/build-web.sh` now prepares a pinned Emscripten 4.0.21/
Node 22.16.0 toolchain with SHA-256 archive checks on Linux/WSL x86_64, builds static GMP 6.3.0
and the selected PARI source, and links a dedicated web ABI around `paridroid.c`. An explicit
activated SDK can be passed with `--emsdk`; automatic toolchain preparation on other hosts is
not yet implemented. Host prerequisites include Python 3.11+, make, m4, cc, git, curl, tar, and xz.
`--pari-source` is a development source override; normal builds select the source tag using
`pariVersion`. `native/web/parikt.mjs` adapts strings and command arrays and provides an async
factory plus synchronous operations after loading. `native/web/smoke.mjs` exercises the engine
directly. Gradle tasks `:parikt:buildWebNativeDependencies` and `:parikt:verifyWebNative` use
`.native/web-<pariVersion>` outputs and default to WSL on Windows. `prepareWebEnginePackage`
stages the selected payload into `parikt/build/generated/web-engine/`, a stable local npm URL.
The old Wasm stub was replaced by `webMain` actual bindings and a Kotlin/JS target was added.
Node and headless Chromium consumer tests pass for both Kotlin targets. A Gradle override to
PARI 2.19.0 passed the direct smoke, all four consumer tasks, both production distributions, and
UI checks showing 2.19.0. Switching back to the unchanged gradle.properties selection, 2.15.0,
also passed. No npm lock update was needed for these switches. PowerShell requires quoting the
dotted property argument: `'-PpariVersion=2.19.0'`.

`:webApp` provides a shared GP console for both compilers with version/ready status, a startup
`2 + 2` check, command input, examples, and clearable output. Its production bundles were served
over HTTP and exercised in isolated headless Edge: startup, factor(123456), clear, Pi example,
and Pi evaluation passed for JS and WasmJS. Browser tests produced one passing test per target,
not empty test tasks. Generated web engine/npm/Kotlin output directories were moved aside and
successfully recreated using persistent source/toolchain caches; a truly clean machine and
macOS host setup remain unverified. Gradle-managed Node/Binaryen archives have settings-level
Ivy repositories to respect PREFER_SETTINGS. npm is used instead of Yarn; lockfiles contain
relative stable engine paths. npm overrides address duplicate webpack-instance resolution.

Remaining build messages include configuration-time npm-resolution performance warnings and
webpack's asset-size warning for the roughly 6.6–7.1 MiB PARI engine. These are distinct from the
resolved multiple-KGP-instance warning. Their causes/optimization are not claimed fixed.

Following user API feedback, the only public initializer is now `suspend fun PariKt.init()`;
the synchronous `initialize()` helper stays private, and the experimental `initAsync()` name was
removed. This deliberately changes JVM callers to a coroutine/suspending context; terminalApp's
entry point is now `suspend fun main()`. Android Startup retains its internal synchronous
`initWithTemporaryDirectory()` entry point. The web factory import is private with an ordinary internal
delegate. Cancelled loading callers preserve the shared pending load; consumer tests cancel one
initializer and then initialize successfully. Promise awaiting lives in small JS/Wasm-specific actual functions, because coroutines
1.9.0's `await` is not exposed consistently to shared-source IDE analysis. The web stack argument
is restricted to positive signed 32-bit values to match the C bridge, rather than blindly narrowing
a Kotlin Long. The arbitrary initial 4096-byte lower bound was removed. This is a representation
check, not a promise that every positive size initializes successfully. Small-stack probes trapped:
PARI's minimum backing allocation does not enlarge its requested active stack, and normalizing
to MIN_STACK (500032) still failed PARI 2.19 initialization. Both experiments were removed. The
default 4,000,000-byte request passes both tested versions; tiny custom requests may terminate the
engine during initialization and very large requests may exhaust Wasm memory. Safer native
initialization failure recovery remains future work; do not claim all representable sizes work.

The user also asked about a minimum supported Kotlin version. Proposed initial consumer baseline
is the current 2.4.21 build toolchain, not a verified technical minimum. Before advertising older
consumer versions, test actual consumers per target; do not infer JS/Wasm compatibility from JVM
metadata compatibility alone.

- [x] Prove a minimal PARI web build first, before wiring Kotlin targets. Inspect PARI Configure
  cross-compilation probes and select a portable wasm32 kernel; decide whether the first build
  uses static GMP or PARI's portable arithmetic kernel based on actual build results.
- [x] Add a dedicated Emscripten build script keyed by `pariVersion`, toolchain version, build
  options, and bridge/script inputs. Keep web outputs separate from Android/JVM caches. Bootstrap
  a pinned, checksum-verified toolchain and document unavoidable host prerequisites.
- [x] Add a web C bridge around the existing wrapper, exporting init/eval/commands/version/close
  with explicit UTF-8 string and memory ownership rules. Handle the wrapper's setjmp/longjmp,
  filesystem/temp directory, and unsupported OS facilities deliberately. Do not compile JNI.
- [x] Generate an Emscripten ES module plus its Wasm asset, with asynchronous module creation.
  Run it directly in Node.js: version, `2 + 2`, `factor(123456)`, persistent variables, invalid
  input recovery, command listing, and close. Confirm this works before adding Kotlin bindings.
- [x] Make `PariKt.init()` the single public suspending initializer, with a private synchronous
  helper and Android Startup's internal synchronous path. Update terminal main and consumers.
- [ ] Test loading-failure retry using an unavailable Wasm asset; the pending-load reset path
  exists but this failure case has not been exercised. Test broader reinitialization/concurrency
  and GP `quit` lifecycle behavior before promising those semantics.
- [x] Add the Kotlin/JS target and replace the Wasm stub with real imports. Reuse the JavaScript
  adapter; use interop types supported by each Kotlin target and convert arrays/strings at the
  boundary. Validate stack-size conversion rather than passing Kotlin Long blindly across JS.
- [x] Wire Gradle task dependencies and generated JS/Wasm assets for Node and browser consumers.
  PariKt is a library; put executable demos in consumer modules. Verify asset paths and loading
  in production output as well as development builds.
- [x] Add a minimal web GP console and exercise both Kotlin/JS and Kotlin/WasmJS consumers.
  Start with a single engine and sequential calls. Record that synchronous calculations block
  their executing thread; evaluate a Worker API separately for a responsive browser UI.
- [x] Verify switching `pariVersion` between 2.15.0 and 2.19.0 selects the correct engine in both
  production browser consoles and consumer tests; stable npm paths avoid lock changes.
- [x] Document actual commands, packaging, synchronous evaluation, and data/filesystem limits.
- [ ] Verify a truly clean checkout/machine without source/toolchain caches. Generated-output
  regeneration has passed; host prerequisite installation, other browsers, and macOS remain open.
- [ ] Define a publishable Maven/npm asset strategy; current file dependencies support local
  project consumers, not arbitrary external projects consuming published artifacts.

References: [Kotlin/Wasm JS interop](https://kotlinlang.org/docs/wasm-js-interop.html) documents
the restricted external types; [Emscripten modularized output](https://emscripten.org/docs/compiling/Modularized-Output.html)
documents async module factories and ES module output. The implemented build uses a portable
wasm32/GMP kernel. PARI 2.15's disabled-pipe branch needed a narrowly scoped return-type fix in
the build copy for current Clang; static linking also required using PARI's actual private hash
table symbols rather than defining the JNI wrapper's replacement symbols. Other PARI versions
may need further compatibility work; only the versions recorded above have been executed.

## Phase 7 — Shared Compose console

The user requested a similar Compose UI on all currently tested targets, including desktop,
using the existing WasmJS console as the visual reference. Share the dark palette, status/version
line, examples, command field, output, and behavior. Retain Android minSdk 21 and defer macOS
verification. Binary function filtering was discussed only; the user explicitly declined
implementation of that separate idea.

- [x] Add a `:consoleUi` KMP module for shared Compose screen/state, consumed by Android,
  desktop JVM, Kotlin/JS, and Kotlin/WasmJS launchers.
- [x] Select Compose dependencies compatible with API 21 and Kotlin 2.4.21; test actual builds.
  Selected Compose Multiplatform 1.8.2, matching AndroidX UI 1.8.2 and Material3 1.3.2.
  Published AAR manifests confirm UI 1.8.2 minSdk 21 versus UI 1.12.1 minSdk 23; do not rely on
  generic platform compatibility tables alone.
  The 1.8.2 Gradle plugin fails against AGP 9.4.1 with missing `onVariant(Function1)`.
  The shared UI uses the Kotlin Compose compiler plugin with explicit UI dependencies;
  it has no Compose resource-generation/packaging requirement. The Compose Multiplatform
  plugin remains limited to the browser launcher, avoiding its old Android integration.
- [x] Reuse console initialization, version/self-check, command editing/examples/history/clear
  behavior and the Wasm console's visual design. Preserve native engine thread affinity; moving
  evaluation to arbitrary background dispatchers is outside this UI change.
- [x] Replace Android's private screen and the HTML web implementation with thin launchers.
- [x] Add `:desktopApp` with the shared screen and native build/library-path preparation.
  Retain `:terminalApp` as a separate CLI.
- [x] Build Android at minSdk 21, desktop JVM, and both browser compiler targets. Exercise
  real startup and calculations, and verify shared state behavior.
- [x] Document launcher commands and remaining desktop packaging/host limitations.

Initial verification: shared JVM/JS/Wasm compilation, desktop launcher compilation, Android
APK assembly (minSdk 21), and both production browser distributions pass. Two shared-state JVM
tests pass (startup gate/version/self-check, command trimming/history/reuse/clear, retry).
Production JS/Wasm screenshots show PARI 2.15.0 Ready and real `2+2 = 4` using the shared screen.
The Kotlin/JS canvas renderer remains experimental and is explicitly enabled in gradle.properties.
The 1.8.2 browser plugin provides Skiko assets; only JS loads global skiko.js, while Kotlin/Wasm
imports its renderer. Browser bundles retain size/configuration-time npm warnings. Runtime
verification results are recorded below; macOS and a Linux desktop GUI remain unverified.
Android Studio could not resolve `ComposeViewport` in shared webMain although target compilation
passed. The thin viewport calls now live in jsMain/wasmJsMain, where those declarations are
published, behind `launchConsoleUi()`; only the screen and state are shared in consoleUi.
A desktop Compose integration test exercises the real native engine and UI semantics without
opening a window; it uses the same native preparation as the desktop launcher. It passes startup,
factorization, clear, and typed Pi. Compose 1.8.2's headless mouse injector called evaluation on
the test-worker thread after initializing on AWT, producing PARI's `expression nested too deeply`.
Native env/stack diagnostics confirmed distinct threads; temporary diagnostics were removed.
The test now invokes semantic button actions on AWT, matching actual desktop input. Native
thread affinity remains required; this does not add cross-thread library support.
Android API 36 x86_64 emulator runtime checks pass with PARI 2.15.0: startup/version, factor,
clear, and Pi. Production Edge JS/Wasm screenshots also verify factor, clear, Pi and a 390px
layout. An initial emulator lacked storage; a second configured emulator accepted the APK.
API 21 manifest compatibility is verified by assembly, not an API 21 device run (available AVD
is x86, outside supported ABIs). Desktop launcher smoke runs finish successfully for 2.15.0
and 2.19.0; the automated UI calculation test has so far run on Windows with 2.15.0.
Final combined verification passed: `:androidApp:assembleDebug`, `:consoleUi:jvmTest`,
`:desktopApp:test`, `:webApp:jsBrowserDistribution`, and `:webApp:wasmJsBrowserDistribution`.
No function-removal feature was implemented. The core library remains independent of Compose.

## Phase 8 — Kotlin/Native linuxX64

The user approved starting with linuxX64 and a native terminal executable, then considering
mingwX64. Keep the public common API and pariVersion selection; no JVM/JNI in the new backend.
Apple targets remain deferred. Current Kotlin documentation marks macosX64 deprecated since
2.3.20; reassess Intel Mac target availability before planning Apple work.

- [x] Build static GMP/PARI and the existing C wrapper for linuxX64 in a separate versioned cache.
- [x] Add a fixed-width C bridge, explicit borrowed-string copying, and OS-thread ownership checks.
- [x] Generate cinterop bindings and implement the linuxX64 PariNative actual backend.
- [x] Add a native terminal demo with version/self-check and interactive evaluation.
- [x] Test real evaluation, variables, errors/recovery, empty input, commands, lifecycle and threading.
- [x] Verify another pariVersion, source/cache invalidation, existing-target regressions and docs.

Builds run from Windows Gradle, compiling C dependencies through WSL and linking linuxX64 with
Kotlin/Native. Native archives use `.native/native-<version>/linux-x86_64`;
the C build stamp includes the bridge/wrapper/header/script content and compiler identity.
The native CLI is a separate `:nativeTerminalApp` module; `:parikt:verifyLinuxX64` runs the native
test executable through WSL when invoked on Windows. Both runners are verified.
Windows KGP accepts linuxX64 compilation but disables its standard host-only run/test tasks;
the explicit WSL runners cover those tasks. GMP builds only its core library subdirectories
and stages its archive/header, avoiding an incidental makeinfo dependency while preserving
Automake's generated-header prerequisites. Its host is generic x86_64 rather than the build CPU.
Static dependencies, cinterop generation and the linuxX64 backend compile. Kotlin/Native rejects
a suspending executable entry point, so the CLI uses a normal main with runBlocking on its
calling thread; the public library initializer remains suspending on all targets.
The bridge/PARI/GMP objects are bundled into one `libparikt.a` for cinterop: separate archives
were linked in an order that left bridge references unresolved. C archives are embedded in
the interop klib, not loaded through JNI; final native linking is verified.
Host GCC/glibc generated an unavailable `__isoc23_strtol` dependency when linking with
Kotlin/Native's older Linux sysroot. KGP's distribution task prepares its managed target
dependencies first; GMP/PARI/the bridge use its matching Linux GCC/sysroot in
WSL. C library stage keys include script/compiler identity to invalidate incompatible archives.
When the managed compiler lives on a Windows drive, the script automatically caches it inside
WSL before use: configure probes against the Windows filesystem were prohibitively slow.
PARI 2.15.0 native linking and tests now pass. The Gradle CLI runner also passes factorization,
persistent variables, division-by-zero recovery, subsequent arithmetic, version and quit.
`file` identifies an x86_64 ELF executable; `ldd` shows system libraries only, with no external
libpari/libgmp or JVM dependency. These checks ran via Windows Gradle + WSL execution.
The expected disabled host-only Linux runner warnings are suppressed; the explicit WSL test
runner is wired into `:parikt:check` on Windows. Tests deliberately use only the public API,
without requiring consumers to opt in to ExperimentalForeignApi.
PARI 2.19.0 also passes the native test suite and terminal factor/version smoke. An isolated,
initially empty Konan cache successfully downloaded the distribution and target dependencies.
This proved KGP already manages those downloads; an earlier custom cinterop probe was removed.
Read KGP's generated konan.txt only at
task execution, after the distribution task, so fresh project metadata can be regenerated.
Consumer/build documentation is in README. A bridge-source comment edit triggered an automatic
bridge/archive rebuild while reusing GMP/PARI stages, confirming source-cache invalidation.
The final combined build passed native tests/CLI linking, Android assembleDebug (minSdk 21),
desktop/shared-state test checks and JS/Wasm launcher compilation. A second native run with
parikt/build/konan.txt temporarily removed successfully regenerated metadata and passed tests;
subsequent archive/interop/link tasks were up to date. The default remains pariVersion=2.15.0.
Unverified: running Gradle itself on a Linux host, additional Linux distributions, a completely
fresh machine including source/host prerequisites, native publication, and other native targets.
Only the Windows Gradle + WSL build/run path has been exercised in this phase.

## Android build defaults and environment notes

Fresh-clone NDK lookup: configuration-time native bootstrap previously ran before the helper
module's `android { ndkVersion = "28.2.13676358" }` configuration. It now runs after that block,
so the local runner resolves the selected NDK version before invoking the native build script.
The user reported an NDK-not-installed error on macOS despite installing an NDK; this ordering
bug is a possible cause, but the exact macOS error and installed NDK version have not been
provided. Fresh-clone macOS sync remains unverified; do not claim this change resolves that report
until confirmed. The installed SDK must contain NDK 28.2.13676358.

The currently selected PARI version is 2.15.0 in gradle.properties; the native scripts' fallback
default is 2.17.1. Other defaults are GMP 6.3.0 (pinned in `native/build-native.sh`), Android API 21,
NDK 28.2.13676358, and ABIs `arm64-v8a,x86_64`. `pariVersion` is read from root `gradle.properties`;
`nativeAbis`, `nativeRunner`, and `nativeForce` are optional properties.

The user's 2026-10-30 Android Studio sync initially failed with `[CXX1429]` because `Android.mk`
required `.native/pari-2.17.1/arm64-v8a/gmp/lib/libgmp.so`, which was absent. The user confirmed
Gradle sync works after the Wasm stdlib fix. The pre-migration app ran on an x86_64 API 36 emulator.
The migrated build now uses AGP 9.4.1, Gradle 9.6.0, Kotlin 2.4.21, and the AGP built-in Kotlin/app
plus Android-KMP library plugin. The demo uses `compileSdk 36`, `minSdk 21`, and `targetSdk 35`; the
native helper uses `minSdk 21` and NDK 28.2.13676358. `:androidApp:assembleDebug` succeeds and
packages PARI/GMP/JNI libraries for arm64-v8a and x86_64. The migrated app also launched on API 36.1
x86_64, initialized through Startup, and displayed the passing `2 + 2` result. The API-21 AVD
available on the machine is x86, outside the native ABI list. The unrelated
multiple-Kotlin-plugin-load warning was subsequently resolved by sharing AGP and KGP at the root.
