# Standalone source consumer

This directory is an independent Gradle build, not a module of the PariKt development build.
It has its own `parikt.properties`. The example uses the surrounding checkout as its pinned
PariKt source; a real application can use a Git submodule/release checkout instead. No Maven
publication is required. The source revision and engine version are independent choices.

From the repository root, on Windows with WSL and the documented SDK/toolchain prerequisites:

```powershell
.\gradlew.bat -p examples/consumer :app:runJvm
.\gradlew.bat -p examples/consumer :app:runDebugExecutableMingwX64
.\gradlew.bat -p examples/consumer :app:linkDebugExecutableLinuxX64
wsl ./examples/consumer/app/build/bin/linuxX64/debugExecutable/app.kexe
.\gradlew.bat -p examples/consumer :app:jsNodeDevelopmentRun
.\gradlew.bat -p examples/consumer :app:wasmJsNodeDevelopmentRun
```

On Linux, use `bash ./gradlew` instead of `.\gradlew.bat`; run the Linux executable directly.
Windows executables require Windows. Running Gradle itself on Linux remains unverified.
Android SDK platform 36 and NDK 28.2.13676358 are still prerequisites, even for the JVM example,
because the current library build configures all targets. Set `ANDROID_HOME` or create a local
`local.properties` in this directory; do not copy machine-local paths into version control.

Each program checks that `getVersionName()` matches **this consumer's** selection, evaluates
arithmetic and factorization, then closes the engine. Change only this directory's
`parikt.properties` to test another release, and rerun the commands. The repository root's
selection is unaffected. The generated `ConsumerSettings.kt` is only a test assertion; consumers
do not need to generate any Kotlin version constant to use PariKt.

`:pariktAndroidNative:assembleDebug` checks the Android bridge AAR with this consumer's selection.
The full Android/Compose application remains in the repository's development build.

The JVM run task prepares the engine and configures `java.library.path`; application packaging
must likewise ship these libraries and configure their lookup path. Native executables embed
the engine. Web executables resolve the consumer-generated local npm engine. All outputs live
in this build's `build` directories, without rewriting the source checkout's engine selection.
The library modules use `build/parikt/modules`, with native outputs in `build/parikt/native`.

Host compiler/source staging caches are shared; builders serialize access with a portable lock.
A forced process kill or machine crash can leave a stale lock; a timeout identifies its directory
for removal after confirming no builder is running. Apple targets and binary-only publication
remain future work.

To run the two-version integration check from the repository root:

```powershell
python tests/build_configuration/verify_consumer.py
```

It checks 2.15.0 and 2.19.0, restores this example's properties file afterward, and records output
in the repository's ignored `build/consumer-verification` directory. Configuration-error checks
are `pwsh -File tests/build_configuration/verify_properties.ps1` (or `powershell -File` with Windows
PowerShell 5.1); the work-cache locking check is
`wsl bash tests/build_configuration/verify_native_lock.sh` on Windows (plain bash on Linux).
After a successful JVM build, `python tests/build_configuration/verify_bridge_cache.py` verifies
that changing JNI source rebuilds at the same PARI version, restores the exact source afterward,
and checks that the next unchanged build reuses the cache.
