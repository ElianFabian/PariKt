# PariKt

PariKt is a Kotlin Multiplatform library that exposes the PARI/GP engine through a small common Kotlin API.

## Android (current focus)

The Android target uses JNI and the Android NDK to build/package the wrapper and its GMP/PARI/GP dependencies into the Android library artifact. On Windows, the native dependency build runs in WSL; Linux and macOS can run the native script directly.

Set the native versions in the root `gradle.properties`:

```properties
pariVersion=2.17.1
```

Then sync Gradle and build the Android library. If native dependencies are missing, the first sync builds GMP and PARI/GP for `arm64-v8a` and `x86_64` before Android Studio configures the NDK model; later syncs reuse those outputs. GMP is pinned to 6.3.0 by the native build script. The native build needs network access, Git, curl, xz, make, m4, patchelf, and (on Windows) a working WSL installation. Set `nativeRunner=local` on Linux/macOS, or `nativeRunner=wsl` on Windows to override the default runner.

The common API lives in `parikt/src/commonMain/kotlin/com/elianfabian/parikt/PariKt.kt`. On Android, the bundled AndroidX Startup initializer obtains the host app's cache directory and initializes PARI/GP before `Application.onCreate()`. No Android initialization call or context passing is required. After startup, evaluate with `PariKt.eval("2 + 2")` and release native state with `PariKt.close()`. Startup uses the default PARI stack size of 4,000,000 bytes.

To try the Android target on an emulator, install Android SDK platform 36, open the project in Android Studio, select the `androidApp` run configuration, and run it on an API 21+ `arm64-v8a` or `x86_64` emulator. The app and library both support `minSdk` 21. The Compose BOM and Activity Compose are pinned to versions that predate the AndroidX minSdk 23 baseline change. The Compose demo verifies startup with `2 + 2` as soon as it opens, then lets you enter GP commands, run examples such as `factor(123456)`, and tap an earlier result to edit and run it again. It displays the detected PARI version and keeps a scrollable result history. You can also install it from a terminal with `gradlew.bat :androidApp:installDebug` on Windows or `./gradlew :androidApp:installDebug` on Linux/macOS when an emulator is running.

JVM support is the next target after Android. WasmJS currently has an interop stub and is not ready for use.
