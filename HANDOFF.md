# PariKt handoff and continuation plan

## Handoff maintenance rule

Whenever project code, build configuration, or documentation changes, update this handoff in the same change: check off completed checklist items, revise any affected facts or decisions, and record verification results or blockers. Do not mark an item complete without confirming it.

## Verified reference: `paridroidgp`

`paridroidgp` is an Android library module inside a repository that also contains the Paridroid app. Its Android namespace and the app's `applicationId` are both `fr.ubordeaux.math.paridroid`. That identity match matters: the native wrapper sets `GPTMPDIR` to `/data/data/fr.ubordeaux.math.paridroid/cache`, and the native build configures PARI with the same package-specific data directory. Those paths work in that paired app setup; they should not be copied as assumptions for an arbitrary app consuming PariKt.

The module uses the standard Android library plugin with Kotlin Android, `externalNativeBuild`/`ndk-build`, and `Android.mk`. `Android.mk` declares prebuilt `libgmp.so` and `libpari.so` for the selected ABI, then compiles the JNI bridge together with `paridroid.c` into `libparidroid.so`. The native script builds GMP and PARI only; the Android Gradle/NDK build compiles the wrapper and JNI bridge.

Its `build.gradle.kts` behavior is:

1. Read `pariVersion` and `nativeAbis` from Gradle properties. Default ABIs are `arm64-v8a` and `x86_64`; NDK version is `28.2.13676358`; minimum Android API is 21.
2. Prefer `.native/pari-$pariVersion` when it contains GMP and PARI libraries for all selected ABIs. For PARI 2.17.1 only, it can fall back to valid bundled libraries under `src/main/jni`.
3. If dependencies are missing at configuration time, run `native/build-native.sh` immediately using WSL on Windows and bash locally elsewhere. This bootstrap path ignores `nativeRunner`, streams output, and fails Gradle if the process fails. As a result, Docker does not replace WSL for a fresh Windows checkout when dependencies are absent.
4. Register `buildPariNative` and wire it to Android NDK tasks when dependencies are missing or `nativeForce` is present. This task supports `local`, `wsl`, and `docker`; its default is `wsl` on Windows and `local` elsewhere. The later task runner is separate from the configuration-time bootstrap above.
5. Pass the chosen dependency directory to `Android.mk` using `NATIVE_DEPS_DIR`.

`ParidroidGP` calls `loadLibrarySafe` for `gmp`, `pari`, and `paridroid` in its object initializer and catches `Throwable`. `PariNative` has a second object initializer that loads the same three libraries and catches `Exception`; it runs when `ParidroidGP` first calls a native method. Thus loading is duplicated on the normal path, with different failure handling. `ParidroidGP` checks initialization for eval and command listing. Its close method calls native close and resets its initialized flag. The comments describe init as one-shot because PARI owns global state; the code itself permits a later init after close. Preserve this as observed behavior until tested before changing lifecycle semantics.

## PariKt current state

PariKt is a KMP library in `:parikt`. Android is the current focus and JVM is next. WasmJS is only an interop stub; iOS and other Kotlin/Native targets are not configured.

Common API and expect declaration: `src/commonMain/kotlin/com/elianfabian/parikt/`. Android JNI Kotlin: `src/androidMain/kotlin/`. JNI bridge and NDK makefiles: `src/androidMain/jni/`. Shared wrapper and native dependency build script: `native/paridroid/` and `native/build-native.sh`.

PariKt currently uses `com.android.library`, KMP `androidTarget()`, and `externalNativeBuild`/`ndk-build`. It checks for the selected version/ABI GMP and PARI libraries during Gradle configuration and runs `build-native.sh` through `nativeRunner` when they are missing, before Android Studio can request the NDK model. The user confirmed Gradle sync now works after removing the unresolved explicit Wasm stdlib dependency. The demo app now builds and has been run successfully on an x86_64 emulator. PariKt has no bundled-prebuilt fallback. The regular Gradle dependency task remains wired to selected NDK Gradle tasks for normal/forced builds.

The `:androidApp` module is a Kotlin/Jetpack Compose GP console for emulator checks. It uses Material 3, Compose BOM 2025.06.01, and Activity Compose 1.10.1 so both the demo app and PariKt can target minSdk 21. Its application ID differs from the PariKt namespace, exercising AndroidX Startup's merged provider and app-specific cache directory. On launch it runs a visible `2 + 2` self-check and displays a decoded PARI version with ready/error status. Users can enter commands, run example chips, review scrollable output history, tap a previous command to edit/rerun, and clear results. The API-21 AVD on this machine is 32-bit x86, outside PariKt's native ABI list; runtime verification uses an x86_64 API 36 emulator.

The first `:androidApp:assembleDebug` log exposed SDK-level requirements, a missing `paridroid.h` include path, and Java/Kotlin JVM targets 11 vs 21. The app uses compileSdk 36 and JVM 17 for both compilers. AndroidX raised the default minSdk for new releases from 21 to 23; to retain the user's minSdk 21 preference, the demo pins Compose BOM 2025.06.01 and Activity Compose 1.10.1. With these versions the app and library both declare minSdk 21, and `:androidApp:assembleDebug` succeeds. `Android.mk` adds the wrapper header directory. A root `apply false` Kotlin plugin centralization attempt caused `org.jetbrains.kotlin.android` to fail against the current AGP setup, so it was removed; the non-fatal multiple-plugin warning remains. The first emulator launch revealed the initializer metadata was absent from the merged manifest because PariKt's manifest used the legacy `src/main` path. It has been moved to `src/androidMain/AndroidManifest.xml`. On an x86_64 API 36 emulator using the API-21-compatible dependency versions, Startup initialization succeeded, `2 + 2` returned 4, `factor(123456)` returned the expected factors, version code 135425 was displayed, and the close button reported successful shutdown. The installed API-21 AVD is x86, which PariKt does not currently package; direct API-21 runtime testing therefore remains open. Reinitialization after close has not been tested.

Kotlin standard-library artifacts are supplied by the Kotlin Multiplatform plugin for each target. Do not add a separate Wasm stdlib dependency; the explicit `org.jetbrains.kotlin:kotlin-stdlib-wasm:2.2.10` coordinate failed resolution during sync. The explicit stdlib dependencies and unused catalog aliases have been removed. The coroutines dependency remains on the JVM and Wasm source sets.

PariKt's Android `PariNative` has a single object initializer that loads `gmp`, `pari`, and `paridroid`; its `loadLibraries()` expect/actual method is a no-op after object initialization. The loader rethrows `UnsatisfiedLinkError` with the library name and ABI/dependency guidance. Runtime library loading and GP evaluation succeeded on the x86_64 emulator. Do not carry over `paridroidgp`'s duplicate loader.

The JNI C bridge now explicitly includes `<jni.h>`. The reference gets that include through its generated `fr_ubordeaux_math_paridroid_PariNative.h`; PariKt does not have an equivalent generated header. Native compilation succeeded for `arm64-v8a` and `x86_64`.

Android callers are initialized automatically by `PariKtInitializer`, registered in `src/androidMain/AndroidManifest.xml` through AndroidX Startup Runtime 1.2.0. It receives the host app's application `Context`, passes the app cache path through JNI, and sets `GPTMPDIR` before `pari_init`. The initializer runs before the host app's `Application.onCreate()`. This is the only Android initialization path; it uses a 4,000,000-byte stack. The merged provider and GP evaluation were verified in the demo host app.

`setTemporaryDirectory` is currently an expect/actual method: Android delegates to the C setter, while JVM and Wasm actuals are no-ops that return `true`. Only Android initialization calls it today. There is no Kotlin/Native actual because those targets are not configured. Do not treat this as cross-target temp-directory support; define each target's strategy before exposing it there. The C setter uses `setenv` on POSIX and `_putenv_s` on Windows.

The native build's `--datadir` is separate from `GPTMPDIR`: PARI uses `datadir` for optional architecture-independent data packages including `elldata`, `galdata`, `galpol`, and `seadata` ([PARI GP defaults](https://pari.math.u-bordeaux.fr/dochtml/ref/GP_defaults.html)). PariKt's native build currently installs only headers and `libpari`, not those data packages. Basic arithmetic does not depend on them, but calls that use those optional datasets may. The configured path inherited from Paridroid should not be mistaken for an app cache path or proof that those files are shipped.

## Completed groundwork

- [x] Read the `paridroidgp` Gradle build, native build script, NDK makefiles, JNI bridge, Kotlin wrapper, and paired app package configuration.
- [x] Compare those files with PariKt's Android/KMP Gradle, JNI, native wrapper, and version configuration.
- [x] Record verified reference behavior and open implementation questions in this handoff.

## Continuation checklist

### Phase 1 — Compare and align Android build behavior

- [x] Add a configuration-time native dependency bootstrap before Android Studio's NDK model generation, using the selected version/ABIs and configured `nativeRunner`.
- [x] User confirmed Gradle sync works after removing the unresolved Wasm stdlib dependency.
- [x] Remove the unresolved explicit Wasm stdlib dependency; Kotlin Multiplatform adds the correct target stdlib automatically.
- [x] Validate Gradle configuration, Android Kotlin compilation, and NDK configure/build for the app on `arm64-v8a` and `x86_64`.
- [ ] Check task ordering against `paridroidgp`: GMP/PARI artifacts must exist before `ndk-build` consumes them. Confirm that tasks skip valid builds and respond correctly to `pariVersion`, ABI changes, and `nativeForce`.
- [ ] Decide whether PariKt needs `paridroidgp`'s bundled-prebuilt fallback. If added, use prebuilts only when their PARI version and every selected ABI match; otherwise build into `.native/pari-$pariVersion`.
- [x] Remove Docker from PariKt's `gradle.properties` runner comment; PariKt currently supports only `local` and `wsl`.
- [x] Compare JNI bridge and `Android.mk` source/include paths with `paridroidgp`; add PariKt's missing explicit `jni.h` include.
- [x] Confirm the AAR contains `libgmp.so`, `libpari.so`, and `libparidroid.so` for both `arm64-v8a` and `x86_64`.
- [x] Improve PariKt's single loader to report missing/incompatible `.so` files with the library name and ABI/dependency guidance; emulator loading succeeded on x86_64.

### Phase 2 — Resolve Android paths for a reusable library

- [x] Confirm `--datadir` is PARI's location for optional data packages, separate from `GPTMPDIR`.
- [ ] Decide which optional PARI data packages PariKt will ship/support. If any are included, package them and configure a valid lookup path; test data-dependent GP commands.
- [x] Add an AndroidX Startup initializer that passes the host app's cache directory through JNI before `pari_init`, without putting Android `Context` in `commonMain`.
- [x] Make AndroidX Startup the only Android initialization path; remove the context-taking public facade.
- [x] Verify Android initialization from the demo app with a different application ID; GP evaluation succeeds after Startup initializes with its cache directory.
- [x] Test the library from a host app whose application ID differs from `com.elianfabian.parikt` (`com.elianfabian.parikt.demo`).

### Phase 3 — Runtime behavior and API checks

- [x] Run the emulator smoke app on x86_64 API 36: Startup, `2 + 2`, `factor(123456)`, version code, and close all succeeded.
- [x] Add a minimal `:androidApp` Jetpack Compose host app for emulator smoke checks.
- [x] Replace the one-shot demo with a visible-ready-state GP console, example commands, editable command input, and tap-to-reuse result history; rebuilt and verified `factor(123456)` through its UI on the x86_64 emulator.
- [x] Fix the Compose SDK requirements, wrapper header include path, and app JVM target mismatch; `:androidApp:assembleDebug` succeeded afterward.
- [x] Move AndroidX Startup registration to `src/androidMain/AndroidManifest.xml` after the merged manifest omitted it; verified the initializer is now registered and runs.
- [x] Restore the demo app's minSdk 21 with Compose BOM 2025.06.01 and Activity Compose 1.10.1; `:androidApp:assembleDebug` passes with minSdk 21.
- [ ] Check behavior when reinitializing after `PariKt.close()`.
- [ ] Check native load failures, empty GP command handling, and concurrent calls. Change behavior based on reproducible results or a clear API requirement.
- [ ] Test another `pariVersion` through Gradle. Keep GMP pinned at 6.3.0 unless build evidence requires an update.
- [ ] Before implementing JVM, Kotlin/Native, or Wasm execution, replace the current `setTemporaryDirectory` no-op actuals with a deliberate target-specific temp-path strategy (or keep the API Android-only).

### Phase 4 — Clone readiness and docs

- [ ] Confirm a clean clone can sync and build with documented prerequisites and no machine-local files.
- [x] Verify `.gitignore` and Git-tracked app/manifest files; document Android build/install commands and provide the Compose consumer app.
- [x] Mark Android target build, AAR contents, and initial runtime smoke checks complete; reinitialization and broader API checks remain open above.

### Phase 5 — JVM target

- [ ] After Android works end-to-end, define JVM native library distribution (host OS/architecture matrix), caching, and loading strategy.
- [ ] Implement and verify the JVM target.
- [ ] Return to WasmJS and iOS after JVM is usable.

## Android build defaults and environment notes

Defaults in PariKt are PARI 2.17.1, GMP 6.3.0 (pinned in `native/build-native.sh`), Android API 21, NDK 28.2.13676358, and ABIs `arm64-v8a,x86_64`. `pariVersion` is read from root `gradle.properties`; `nativeAbis`, `nativeRunner`, and `nativeForce` are optional properties.

The user's 2026-10-30 Android Studio sync initially failed with `[CXX1429]` because `Android.mk` required `.native/pari-2.17.1/arm64-v8a/gmp/lib/libgmp.so`, which was absent. The user confirmed sync works after the Wasm stdlib fix. `:androidApp:assembleDebug` succeeds with API-21-compatible Compose dependencies, and the Compose app has been exercised on an x86_64 API 36 emulator. The demo uses `compileSdk 36`, `minSdk 21`, and `targetSdk 35`; the library uses `compileSdk 35`, `minSdk 21`, and NDK 28.2.13676358. The API-21 AVD available on the machine is x86, which is outside the native ABI list, so no API-21 runtime launch was possible. A non-fatal warning remains about Kotlin plugins loaded in multiple subprojects. The Gradle 9.6/AGP 9.4.1 deprecation warnings also remain.
