# Apple library targets

Scope: Apple Kotlin/Native library backends and tests, reusing the shared Native bridge.
User scope: iOS, macOS, tvOS and watchOS, including Intel and Apple Silicon simulators.
Include watchOS arm64_32 separately from LP64 device/simulator ABIs. Retired ARM32 is excluded.
Apple compilation/linking/runtime cannot be verified on this Windows host. Preserve other
targets, examples, module version selection and Android minSdk 21. No custom Gradle plugin.

- [x] Add Xcode SDK-specific static GMP/PARI/bridge builds with version/SDK/toolchain fingerprints.
- [x] Configure Apple targets, cinterop, framework outputs and one-engine graph/archive checks.
- [x] Use an Apple sandbox temporary directory; inherit shared Native boundary/lifecycle tests
  and add Apple-specific file/temp-directory tests.
- [x] Add a PowerShell Mac verification runner for library tests, framework links and two apps
  sharing a Kotlin library with different engine selections.
- [x] Validate script syntax, Gradle configuration and existing-platform regressions here.
- [x] Run host macOS compilation, tests, frameworks and two-version apps on Intel and ARM64
  GitHub runners (run 44347230604 / 12bb55f).
- [ ] Run mobile simulators and link every Apple device/simulator framework/test binary.
  Full verification dispatched as run 44350989857; pending results.
  ARM run exposed legacy Darwin loader APIs unavailable on iOS. The private mobile source copy
  now suppresses that fallback while HAS_DLOPEN remains disabled; hosted rerun required.

References: https://kotlinlang.org/docs/native-target-support.html and the installed KGP APIs.

## Implementation and available verification (2026-11-18)

All twelve device/native/simulator targets are declared. `docs/APPLE_TARGETS.md` describes ABI,
SDK selection, sandbox temp paths, static frameworks, host limitations and Mac commands.
`parikt.apple.families` can restrict SDK preparation; `-HostOnly` uses the macOS family.
Linux/Windows hosts retain both existing Native targets. macOS configures Apple destinations
instead of invoking Linux-only C toolchains during cinterop commonization. Native terminal
Mac targets follow that host choice. Existing Android minSdk 21 and both examples remain.

Windows configuration-only fixture passes graph selection for two apps sharing a library,
explicit conflicts and Apple task discovery: build/apple-tests/aff96afe5b71/. All twelve cinterop,
framework and test-link task declarations were checked against that retained task listing.
macOS-only family configuration passes: build/apple-research/macos-family.log. All twelve actual
deployment argument resolutions pass against installed compiler metadata: deployment.log.

Regression evidence under build/apple-research/:
- native-regression.log: Windows/Linux Native boundary/lifecycle tests, JVM engine and desktop
  UI/state/file tests pass (30 tasks).
- final-regression.log: both existing Native app links pass one selected archive/graph checks;
  Android debug packaging and JS/Wasm app compilation pass (103 tasks).
- kotlin-regression.log and build/kotlin-consumers/b005b6a61e3f/: Kotlin 2.0.0/2.4.21 JVM
  consumer runtime, strict cache reuse and conflicts pass after the Apple additions.
- Bash syntax and existing native-lock concurrency tests pass under WSL; the Apple builder
  rejects Linux execution before attempting SDK compilation. PowerShell syntax passes.

Hosted run 44347230604 passes macosX64 and macosArm64 library tests, host framework links,
explicit conflicts, two apps sharing a library with 2.19.0/2.15.0 and warm reuse. Intel fixture:
d7fdaaf68d9e; ARM fixture: 0461777298b7. Xcode 26.4 and Kotlin 2.4.21 were used. Logs are
retained in build/ci-local/apple-*-12bb55f.log and workflow artifacts. This does not prove
mobile simulators/devices, all cross-architecture framework outputs or other Xcode/Kotlin
versions. Full simulator/link verification is now running separately.
