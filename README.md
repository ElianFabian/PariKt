# PariKt

PariKt is a Kotlin Multiplatform library that exposes the PARI/GP engine through a small common
Kotlin API.

## Android (current focus)

The Android target uses the Android-KMP library plugin. JNI and the Android NDK build live in the
companion `:pariktAndroidNative` Android library, which PariKt consumes from its `androidMain`
source set. On Windows, the native dependency build runs in WSL; Linux and macOS can run the native
script directly.

Set the native versions in the root `gradle.properties`:

```properties
pariVersion=2.17.1
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
`exit`, or send EOF, to close the engine. The `run` task builds host-specific native libraries first
and stores them under `parikt/.native/jvm-<pariVersion>/<host>/`.

The first supported build hosts are Windows x86_64 and Linux x86_64 under WSL. They require separate
libraries: Windows builds produce DLLs (cross-compiled from WSL with MinGW-w64); Linux builds
produce `.so` files. Android `.so` files cannot be used by desktop JVMs. On WSL/Ubuntu, install the
native prerequisites with
`sudo apt install build-essential git curl xz-utils m4 openjdk-21-jdk-headless gcc-mingw-w64-x86-64 binutils-mingw-w64-x86-64`.
The Linux build uses the Linux JDK's JNI headers; Windows cross-compilation uses the Windows JDK
headers supplied by the Windows Gradle run. JVM code loads these libraries with
`System.loadLibrary`; consumers must put their native directory on `java.library.path`. The terminal
run task does this automatically.

Run on Windows from PowerShell with WSL and the MinGW-w64 packages installed:

```powershell
gradlew.bat :terminalApp:run
```

Run the Linux build from a WSL checkout with a Linux JDK installed:

```bash
./gradlew :terminalApp:run
```

You can override the output/library directory with `-PjvmNativeDir=<directory>` or force a native
rebuild with `-PjvmNativeForce`. Windows and WSL/Linux builds and terminal smoke runs have been
verified for PARI 2.17.1. A clean-machine setup, another PARI version, missing-library diagnostics,
and packaged distribution still need validation; this is not yet a published cross-platform
distribution.

WasmJS currently has an interop stub and is not ready for use.
