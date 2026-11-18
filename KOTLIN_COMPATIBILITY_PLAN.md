# Kotlin 2.x library consumer compatibility

## Scope and status

Completed for the tested source-consumer release points on 2026-11-05. The user chose library
consumers; sample applications retain the repository's Kotlin 2.4.21 / Gradle 9.6.0 / AGP 9.4.1
baseline. Each consumer compiles PariKt with its own Kotlin compiler. Exact tests cover Kotlin
2.0.0 plus the latest stable patches in the 2.0–2.4 lines; other historical patches and future
releases are not individually verified.

Existing targets, both examples, Android minSdk 21, root/module version selection, one engine
per final application and explicit-conflict rejection are preserved. No custom Gradle plugin or
metadata-version-check bypass was added. iOS remains a separate backend implementation.

## Verified matrix

| Kotlin | Gradle | AGP | JVM/cache/conflict | JS + Wasm Node | Windows + Linux Native | Android debug + R8 release |
| --- | --- | --- | --- | --- | --- | --- |
| 2.0.0 | 8.5 | 8.3.2 + R8 8.5.10 override | Pass | Pass | Pass after compiler-install fix | Pass |
| 2.0.21 | 8.8 | 8.5.2 | Pass | Pass | Pass | Pass |
| 2.1.21 | 8.9 | 8.7.2 | Pass | Pass | Pass | Pass |
| 2.2.21 | 8.11.1 | 8.10.1 | Pass | Pass | Pass | Pass |
| 2.3.21 | 8.14 | 8.13.2 | Pass | Pass | Pass | Pass |
| 2.4.21 | 9.6.0 | 9.4.1 | Pass | Pass | Pass | Pass |

Every profile builds two applications sharing a Kotlin library and selects PARI 2.15.0/2.19.0
independently. JVM checks arithmetic/factorization/version, one class implementation, strict
configuration-cache storage/reuse and explicit conflict rejection before native preparation.
Native links check exactly one selected static archive, then execute both engines on Windows
and Linux through WSL. Web runs both engines on JS and Wasm. Android builds debug and shrunk
release APKs, checks debug/release provider/helper graphs and inspects debug APK minSdk21,
Startup metadata and ARM64/x86_64 native payloads.

Four actual Android R8 release APKs also run: both engines under Kotlin 2.0.0 and 2.4.21, on an
isolated API36.1 x86_64 emulator. Visible PASS text and logs confirm native initialization and
math after shrinking. Standard debug signing was used only for test installation. Workspace-
owned AVD/config/userdata were used; the owned emulator was stopped and user's AVD was untouched.

Additional library tests pass on all six compilers: two lifecycle/boundary tests per JS/Wasm
adapter (24 test cases), covering command enumeration beyond 1,000 functions, cancellation,
initialization/close and recoverable errors. Kotlin-only JVM/WebTests fixtures also pass on
Gradle 8.8 for the five representative patches; 2.0.0 uses Gradle 8.5. A separate 2.0.0 rootless
JVM consumer check passes.

## Implementation checklist

- [x] Confirm library scope and inspect sibling KProxyable read-only for executable matrix/API
  adaptation patterns; retain isolated PariKt fixtures instead of modifying checkout versions.
- [x] Add PowerShell matrix with pinned/checksummed wrappers, per-compiler Android profiles,
  retained logs/results and resumable individual checks preserving initial evidence.
- [x] Bridge Gradle 8/9 dependency-path access and configure only the installed KGP's optional
  tool types; use old/new module-name APIs through one adapter.
- [x] Use actual consumer KGP version for Native build inputs and interop opt-ins; keep source
  public API independent of sample application toolchain pins.
- [x] Support old Native compiler installation/SDK preparation alongside the current KGP
  download task. Read its managed home; provision via its own downloader when needed, then link
  a tiny program to prepare the C SDK. Preserve existing C interop/static-archive identity.
- [x] Support AGP 8 legacy androidTarget and AGP 9 Android KMP DSL, conditionally set helper
  enableKotlin, preserve minSdk21, verify both engine graphs and shrunk releases.
- [x] Isolate JS/Wasm array and Promise generic differences in platform actuals using an opaque
  loading handle, retaining shared single-load/cancellation semantics and relative npm paths.
- [x] Verify selection/conflicts/one-engine behavior, JVM cache reuse, all existing target
  runtimes available here, pinned-toolchain regressions, onboarding and handoff.

## Running the checks

Requires PowerShell 7, JDK21 and the existing backend prerequisites. Windows Native/Linux
execution uses WSL; Android compilation needs SDK36 and the pinned NDK. The script seeds
prepared Android C outputs when available; normal builders still validate fingerprints.

```powershell
.\tests\build_configuration\verify_kotlin_consumers.ps1 -Checks Jvm,Web,WebTests,Native,Android
# Focus a compiler or resume a failed group in a retained run:
.\tests\build_configuration\verify_kotlin_consumers.ps1 -KotlinVersion '2.0.0' -Checks Native -ResumeDirectory 'C:\path\to\PariKt\build\kotlin-consumers\RUN'
```

Default checks JVM only. Resume is restricted to retained runs under build/kotlin-consumers,
preserves their toolchain profiles, initial results and old logs, and replaces requested rows.
Keep Web and WebTests on the same app-inclusive npm graph. A manual graph change can require
an explicit lock upgrade in that disposable fixture; production lock enforcement is unchanged.

## Evidence and limits

- Full matrix: build/kotlin-consumers/aa26c6fac8ca/results.json (all current rows pass).
  results-initial.json preserves the initial missing-compiler Native failure; retry logs and
  PreviousLog record the successful correction. Both Web modes also pass together on oldest/
  newest compilers after the module-name adapter was consolidated.
- Six-compiler web boundary matrix: build/kotlin-consumers/fa10a229d220/results.json.
- Oldest compiler rootless proof: aa26c6fac8ca/2.0.0/rootless.log.
- Android release runtime: build/kotlin-consumers/android-runtime/*release-{ui.xml,runtime.log}.
- Pinned regression: build/kotlin-consumers/pinned-regression.log, 175 tasks in 1m55s, covering
  UI/state/persistence tests, Android debug APK, JS/Wasm app compilation and runtime tests,
  Windows Native and actual WSL Linux Native boundary tests.

The verified host is Windows x64 with JBR21 and WSL Linux. Other host/toolchain combinations,
older-compiler browser runtime and API21-device runtime are not individually established.
Native compiler installation was exercised when 2.0.0 was absent; other build prerequisites
and prepared caches were available, so this is not a fresh-OS test.

User's Mac for future iOS work: 2019 Intel i5 quad-core 1.4 GHz, 16 GB RAM, macOS 15.7.5,
Xcode 26.3, free Apple account. It is not attached to this workspace. Kotlin compatibility
alone did not add an Apple backend. The later Apple implementation and pending Mac verification
are documented separately in APPLE_TARGET_PLAN.md and docs/APPLE_TARGETS.md; this matrix does
not make an untested Apple compiler/Xcode/Compose promise.

Reference toolchain matrices:
https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html
https://developer.android.com/build/kotlin-support

## Independent Maven consumers (2026-11-18)

These checks consume Kotlin 2.0.0-compiled candidates from an isolated Maven repository and
load engine tools from the Maven bootstrap. They are separate from the source-consumer matrix.
Every web proof uses two applications sharing a compiled Kotlin library, both GP versions,
custom Webpack fragments and explicit requirement conflicts.

| Consumer Kotlin | JVM + strict cache | JS/Wasm Node + production bundles | Real JS/Wasm browsers | Optimizer profile |
| --- | --- | --- | --- | --- |
| 2.0.0 | Pass | Pass | Pass | Default |
| 2.0.21 | Pass | Pass | Not rerun | Default |
| 2.1.21 | Pass | Pass | Pass | Explicit Binaryen 130 |
| 2.2.21 | Pass | Pass | Not rerun | Default |
| 2.3.21 | Pass | Pass | Not rerun | Default |
| 2.4.21 | Pass | Pass | Pass | Default |

Kotlin 2.1.21's default Binaryen 120 crashes during production optimization. With identical
optimizer flags, 130 succeeds; both optimized Node application runtimes also pass. See
[publishing/README.md](publishing/README.md) for the explicit consumer configuration and probe
option. This qualification is part of the tested profile, not an automatic library override.
No historical/future patch or browser coverage beyond the table is inferred.

Web fixtures under build/maven-web-consumer: 6a85dc2a10fc (2.0.0), 229717114318 (2.1.21/130),
cd4b432c66ce (2.0.21), a85eefba8c71 (2.2.21), 31b8400b1667 (2.3.21), 052754ba3fad (2.4.21). Optimized 2.1 Node
runtime log: build/ci-local/kotlin-2.1-binaryen130-bundles.log. JVM six-point strict-cache fixture:
build/maven-consumer/8cc5e3b22d5a; Linux six-point hosted strict-cache fixture: 9abf69dd62e0.

Native consumers under 2.0.0/2.4.21 pass on Windows, Linux, ARM Mac and Intel Mac. The corrected
cold-SDK probe provisions first, then requires strict cache storage/immediate reuse, actual
runtimes, exact archive identities/hashes and conflicts. Hosted Native-focused run 44388331545
and source CI 44388300267 pass at 789b0d4. This does not promise immediate reuse of configuration
recorded before a fresh compiler SDK populates its platform klibs. The Native-focused run
omits the JVM matrix; full candidate mode remains the release gate.
