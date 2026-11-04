# PariKt current handoff

## Maintenance rule

Update this handoff and PARI_MODULE_VERSION_PLAN.md whenever code, build configuration or
project documentation changes. Check off work only after verification. Historical rollout notes
are preserved in [docs/HANDOFF_HISTORY.md](docs/HANDOFF_HISTORY.md); they include superseded facts.

## Current work

Android Studio GUI sync is now verified at eb94ea7 on Windows x86_64/WSL with prepared caches.
Android Studio Rabbit 1 (2026.2.1) opened the repository; manually invoking Sync Project with
Gradle Files completed successfully in 29.525 seconds, followed by IDE model import completion.
The sync banner cleared and the Gradle project tree includes the default/scoped providers and
application modules. No production code change was needed. Evidence: build/ide-verification/
android-studio-sync.log. Android Studio remains open on the project. This verifies one existing
checkout/cache setup, not fresh installation, variant switching, every editor, or run/debug UI.

Android explicit initialization is complete: Startup now records only the cache path, and
JNI loading/engine initialization wait for the application's PariKt.init(). The shared Compose
demo already calls init through ConsoleSession. Proof apps/runtime checks now cover deferred
startup, invalid/custom sizes, close and reinitialization. Existing Android consumers must add
explicit init if they relied on eager Startup initialization; no context argument is required.

NDK SDK/archive pins now live together in gradle/libs.versions.toml (ndk/ndkArchive), consumed by
Gradle, PowerShell and Linux/WSL bootstrap. Update both catalog entries for an NDK upgrade.
All private JNI entry points use native* names; internal adapters consistently expose init,
eval, getSupportedCommands, close and getVersionCode. C exports and JNI reflection tests match.

Final verification: both Android versions pass actual x86_64 API36.1 deferred startup, explicit
init with 8,000,000 bytes, invalid sizes, reinit and JNI boundary checks. Log order proves JNI
loading follows the app's init marker. The Compose demo displays Ready / 2 + 2 = 4. All 14
Android production cases and both selected-only ABI payloads pass at build/android-tests/
3de42b05cb1a. Both NDK engines rebuild through normal fingerprints, reusing canonical sources.
Root backend tests/Android assembly pass 161 tasks (160 executed) then strict cache reuse;
standalone 2.19.0 passes 66 tasks (65 executed). JVM and Linux/Windows Native identity checks
pass for default/scoped providers. Four harness checks pass under PowerShell 7 and 5.1.
Logs: build/android-explicit-init/{final-platform-*,final-consumer-219,identities,packaging-final,
runtime-final,demo-runtime-final,harness*}.log. Proof/demo apps were removed, the owned emulator
stopped and its isolated data removed. ARM64/API21 runtime coverage remains unverified.

The new [contributor guide](docs/CONTRIBUTOR_GUIDE.md) explains the module/source-set map, backend
call/build paths, version routing/generated providers, cache layers and verification scripts.
Documentation references and commands were checked against the current source; no runtime code
changed for this guide. README links it as the entry point for new contributors.
All local documentation links and script inventory references pass checks. The guide's ten
engine-preparation task paths resolve in a Gradle dry run (build/documentation-checks/
preparation-task-paths.log). The README's obsolete mandatory-Android statement is corrected.

The six correctness fixes are committed in 920cd84. Structural cleanup is complete according
to [STRUCTURAL_CLEANUP_PLAN.md](STRUCTURAL_CLEANUP_PLAN.md). Default and scoped providers now use
one canonical parikt/build.gradle.kts; settings copies that build definition into scoped project
directories before Gradle evaluates them. Extra properties supply each scoped version. Canonical
source directories, consumer-owned caches, stable provider identities and output paths remain.
Four duplicate gradle/parikt.*-engine.gradle.kts templates are removed. Android uses the existing
optional public model setup when the consuming root shares AGP; Kotlin-only roots need no AGP.

Nine PowerShell suites now dot-source tests/build_configuration/harness.ps1 for Gradle process
startup, merged disk logs, output assertions and fixture copy/cleanup. Each suite retains its
arguments, specialized assertions and restoration rules. The harness does not change caller
preferences or global environment. Dedicated probes cover spaces/!/ &/% in arguments and paths,
expected failures, fixture filtering and cleanup boundaries. Non-ANSI Unicode launcher arguments
are not supported by the tested Windows/JBR launch path; UTF-8 file encoding did not resolve it.

Shared-provider JVM/Native class/metadata identities pass. Production regressions pass 10 Native,
14 web plus portable locks, 14 Android (selected-only payloads on both ABIs), 11 real-app and
13 module-only cases. All 25 strict cache cases including platform store/reuse pass, as do
24 discovery checks, five property diagnostics and three harness checks under PowerShell 7/5.1.
Root backend tests/Android assembly store and reuse pass (161 tasks each); the standalone 2.19.0
consumer passes 66 tasks. Most compilation/tests retain valid up-to-date results. Android runtime
was last exercised at 920cd84; this cleanup verifies builds, payload hashes and graph identities.
All 11 independent JVM compatibility cases pass, including forced-build/shared-engine/source
reuse. Logs: build/structural-cleanup/*.log. Strict cache logs: build/configuration-cache-tests/
90db0e20626b; Native: build/np/e76e4d3f9420; web: build/wp/2e8b5aa1368e; Android:
build/android-tests/1a49c580614a; module-only: build/production-module-only/61268c4a4058.
The shared provider and harness cleanup is complete; remaining work is environment coverage.

## Architecture and invariants

- Root parikt.properties supplies defaults; module-local files supply explicit requirements.
  Module-only builds have no implicit root default. Conflicting explicit requirements fail.
- Each final application receives exactly one engine. Applications in the same build can select
  different versions while sharing Kotlin libraries. One provider is reused for a selected version.
- :parikt is the default provider or a no-default selector. Scoped providers have stable :parikt
  leaf names and group com.elianfabian.parikt.engine. Kotlin module identity remains parikt.
- No custom Gradle plugin. All existing Android/JVM/Linux/Windows Native/JS/Wasm targets remain.
  Android minSdk stays 21, with arm64-v8a/x86_64 native libraries. Both examples folders remain:
  examples/consumer demonstrates installation; examples/module-versions contains compatibility proofs.
- One canonical PARI source cache is reused across versions/backends. Compiler/ABI/bridge/config
  fingerprints govern compiled caches; do not weaken fingerprints to reuse incompatible output.
- Configuration caching remains opt-in. Task actions retain configured values, file collections
  and deferred Native argument data, not live Project objects. parikt.participants is populated
  during configuration and serialized as data for package actions.
- Common/shared-C boundaries guard empty input and repeated close. GP quit is a recoverable error;
  use PariKt.close(). JNI checks native-long bounds and dynamically allocates command lists.
  Shared tests and bounded JVM subprocesses cover both configured PARI versions.

## Toolchain and local verification

Pinned versions: Gradle 9.6.0, Kotlin 2.4.21, AGP 9.4.1, SDK 36, NDK 28.2.13676358.
Windows uses PowerShell and WSL builders. Reuse prepared caches; do not stop unrelated daemons/apps.
Run root Gradle builds serially. Overlapping root builds previously locked NDK outputs and damaged
web incremental test caches; only affected generated directories were reset before successful reruns.
Independent disposable consumer fixtures have separate output directories.

Useful checks from the repository root (PowerShell):

```powershell
.\gradlew.bat :parikt:jvmTest :parikt:verifyLinuxX64 :parikt:verifyMingwX64 :parikt:jsNodeTest :parikt:wasmJsNodeTest :androidApp:assembleDebug --no-parallel --configuration-cache --configuration-cache-problems=fail
pwsh -File tests/build_configuration/verify_harness.ps1
pwsh -File tests/build_configuration/verify_configuration_cache.ps1 -Platforms
pwsh -File tests/build_configuration/verify_application_versions.ps1
pwsh -File tests/build_configuration/verify_production_module_only.ps1
pwsh -File tests/build_configuration/verify_native_module_versions.ps1 -Production
pwsh -File tests/build_configuration/verify_web_module_versions.ps1 -Production
pwsh -File tests/build_configuration/verify_android_module_versions.ps1 -Production
```

Acceptance scripts retain logs and validate fixture paths before recursive cleanup. The real-app
suite temporarily edits local requirements/build text and restores exact original bytes. Do not
run it while another root build is active. Android runtime verification installs/uninstalls only
its new proof apps and requires a booted disposable device without existing proof packages.

## Previously verified checkpoint (920cd84)

Both PARI 2.15.0/2.19.0 pass JVM, Linux/Windows Native and JS/Wasm Node boundary contracts. Six JVM
subprocess tests run per configured version, including direct JNI defenses. Root platform checks
pass 156 tasks; the standalone 2.19.0 consumer passes 66 tasks. All 25 strict cache cases plus
platform store/reuse pass, including unrelated JavaExec/Test path preservation with no native build.
Production suites pass 10 Native, 14 web plus portable locks, 14 Android and 11 real-app cases.
Both Android ABI splits contain exactly one selected engine; both x86_64 apps pass Startup and
extended JNI boundaries on API36.1. Proof apps/emulator data were removed and the owned emulator
stopped. Logs: build/project-review/fix-*. Details: PROJECT_REVIEW.md and the archived handoff.

## Remaining environment coverage

ARM64/API21 runtime, fresh-machine setup and Apple/other build hosts remain unverified. Basic
Android Studio GUI sync passes as recorded above; broader IDE run/debug/variant workflows remain
outside that verified scope.
The available API21 emulator is x86, outside the supported native ABI list. SDK compilation and an
API36.1 x86_64 run do not prove those cases. Publication is outside the completed source integration.
Browser pages were previously executed; this structural checkpoint verifies their rebuilt
bundles/graphs and Node execution. It does not repeat browser UI or emulator runtime execution.
