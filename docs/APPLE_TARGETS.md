# Apple library backends and tests

Host macOS support passes compilation, tests, framework linking and independent two-version
applications on Intel and Apple Silicon GitHub runners (run 44347230604, Kotlin 2.4.21,
Xcode 26.4). Full run 44353196417 also passes iOS/tvOS/watchOS simulator tests on both host
architectures and all twelve framework/test-binary links. Physical Apple devices and external
Swift framework consumers have not been exercised. Maven consumer verification is separate.
The earlier Kotlin 2.x matrix does not establish Apple compatibility across compiler versions.

## Targets

| Platform | Device/native | Simulators |
| --- | --- | --- |
| macOS | `macosX64`, `macosArm64` | Not applicable |
| iOS | `iosArm64` | `iosX64`, `iosSimulatorArm64` |
| tvOS | `tvosArm64` | `tvosX64`, `tvosSimulatorArm64` |
| watchOS | `watchosArm64` (arm64_32), `watchosDeviceArm64` | `watchosX64`, `watchosSimulatorArm64` |

The retired `watchosArm32` target is excluded. Intel targets are retained for the user's Intel
Mac; some are deprecated in newer Kotlin versions. Future compilers can remove them.

Apple targets are declared on all hosts for discovery; C interop and binaries require macOS.
On a Mac, the engine and native terminal configure Apple destinations instead of the existing
Linux/Windows destinations, whose C toolchains execute Linux binaries. This prevents Apple
cinterop commonization from trying to execute Linux compilers. Linux and Windows/WSL retain
their existing targets and builders. Android, JVM and web declarations remain unchanged.

Use `-Pparikt.apple.families=macos` (or a comma-separated subset of `macos,ios,tvos,watchos`)
to limit SDK preparation. Cinterop commonization can prepare archives for every configured
Apple target, so enabling all families requires their SDKs even when testing only one target.
The verification runner's `-HostOnly` mode selects only the macOS family unless combined with
`-AllAppleTargets`; it needs no mobile SDKs or simulator runtimes.

## How the engine is built

Install GNU Bison 3 or newer (`brew install bison`) and put its `bin` directory on PATH:
`export PATH="$(brew --prefix bison)/bin:$PATH"`. macOS's bundled Bison 2.3 cannot generate
the parser from PARI's Git sources. The builder checks this before downloading dependencies;
CI installs the current Homebrew version explicitly.

`parikt/native/build-apple.sh` uses the selected Xcode (`xcode-select` or `DEVELOPER_DIR`) and
`xcrun` to select the destination SDK, clang and Apple libtool. Each target has its own
`native-<PARI version>/<target>/lib/libparikt.a` output. The archive contains the C bridge,
static PARI and static GMP; no JNI, JVM or dynamic PARI installation is needed.

The consumer compiler's `konan.properties` supplies the destination minimum OS version, via
`gradle/parikt.apple-arguments.gradle.kts`. The builder fingerprints PARI revision, GMP version,
target triple, deployment minimum, SDK version/path, Xcode/clang and bridge/build inputs.
Device and simulator outputs are distinct even when both use ARM64. Multiple consumers reuse
the same source acquisition cache and compatible staged components. GMP is shared across PARI
versions for a matching compiler/SDK/ABI. Matching output stamps skip recompilation.
Use `-PkotlinNativeForce` to force a Native rebuild.

Apple builds use portable PARI/GMP kernels. Configure compile/link probes still use the actual
target compiler; a small runner supplies the endian/GMP values without trying to execute an
iOS/tvOS/watchOS executable on the build Mac. arm64_32 uses 32-bit long/pointers; all other
targets use LP64. Compile-time ABI assertions guard that distinction. Mobile configurations
disable dynamic loading and subprocess features. Optional PARI data packages are not bundled.

The shared Native Kotlin implementation handles explicit initialization, arithmetic, command
enumeration, lifecycle and same-thread enforcement. Apple uses `NSTemporaryDirectory()` for
sandbox file access. Initialization remains the developer's choice. Use the same OS thread for
init/eval/commands/close; a coroutine dispatcher pool does not guarantee this.

Every Apple target defines a static `PariKt.framework`. Kotlin consumers can instead depend on
the source-installed `:parikt` project. Root/module `parikt.properties` selection, explicit shared
requirements, graph checks and selected-archive identity checks also apply to Apple consumers.
Link only one selected engine/framework into each final application. No Maven publication,
XCFramework distribution, signing workflow or Apple Compose sample has been added.

## Tests and verification

All twelve targets inherit `NativeEngineTest` and `AppleEngineTest`: four Kotlin test methods
cover the shared boundaries (including more than 1,000 commands, quit/error recovery and invalid
initialization), lifecycle/reinit, arithmetic/factorization, wrong-thread rejection, sandbox
temporary-directory selection and GP file write/read. Tests write to the platform temp directory,
not a potentially read-only simulator/device working directory.

Use macOS with a compatible full Xcode installation, required SDKs/simulator runtimes, JDK21,
PowerShell 7 and the ordinary C build tools (`make`, `m4`, Git, curl, tar, Perl, shasum).
Check the selected Kotlin compiler's Xcode requirements first. The user's recorded Intel Mac /
Xcode 26.3 setup has not been validated against the current compiler; do not bypass its checks.
No paid Apple Developer membership or device signing is needed for host/simulator tests.

```powershell
# macOS tests and two real applications, without requiring simulator runtimes:
pwsh -File tests/build_configuration/verify_apple_targets.ps1 -HostOnly

# macOS plus the host-architecture iOS, tvOS and watchOS simulators:
pwsh -File tests/build_configuration/verify_apple_targets.ps1

# Also link frameworks and test binaries for all twelve targets; devices are not executed:
pwsh -File tests/build_configuration/verify_apple_targets.ps1 -AllAppleTargets

# Safe model/graph/conflict checks on Windows or Linux; no Apple compilation:
pwsh -File tests/build_configuration/verify_apple_targets.ps1 -ConfigureOnly
```

The runner creates a Kotlin-only consumer under `build/apple-tests/<run>/`, avoiding Android/JVM
sample prerequisites. Two macOS applications share a Kotlin library and select PARI 2.19.0 and
2.15.0. It checks routing/conflicts, one selected static archive, real version/math results and
warm engine reuse. It retains logs, results, frameworks and test reports. Normal project files
and the regular 2.19.0 default are not edited. Native device tests require a separate signed app
harness and physical devices; simulator success cannot establish device runtime coverage.

Simulator selection uses installed available runtimes and passes their UDIDs through
`parikt.apple.ios.simulatorId`, `parikt.apple.tvos.simulatorId` and
`parikt.apple.watchos.simulatorId`. The runner rejects missing runtimes and skipped/empty test
reports. On Intel it runs X64 simulators; on Apple Silicon it runs SimulatorArm64 targets.
It does not install runtimes, alter Xcode selection or delete the user's simulators.

Native framework tasks are `:parikt:linkDebugFrameworkIosArm64`, and equivalently for each target.
On a Mac with this repository's Android prerequisites also configured, the native console has
`:nativeTerminalApp:runMacos`. Use the isolated runner first when those prerequisites are absent.

References: [Kotlin/Native targets and hosts](https://kotlinlang.org/docs/native-target-support.html),
[Kotlin project configuration](https://kotlinlang.org/docs/gradle-configure-project.html).
