# PariKt current handoff

## Maintenance rule

Update this handoff and PARI_MODULE_VERSION_PLAN.md whenever code, build configuration or
project documentation changes. Check off work only after verification. Historical rollout notes
are preserved in [docs/HANDOFF_HISTORY.md](docs/HANDOFF_HISTORY.md); they include superseded facts.

## Current work

Empty local source-tree cleanup on 2026-11-05: verified zero files (including hidden files) and
removed empty parikt/src/{iosMain,main,linuxX64Main,linuxX64Test} directory trees bottom-up.
No source files or Gradle declarations changed. Linux source sets remain configured and use
nativeMain/nativeTest; iOS has no configured target. Git does not track these empty directories,
and the IDE/tooling may recreate configured empty Linux source roots. No build rerun was needed
for this filesystem-only cleanup. Both examples and existing targets remain intact.

Windows x64 portable executable built on 2026-11-05. desktopApp/package_windows.ps1 uses JDK
jpackage app-image to include its Java runtime, runtime JARs and the selected PARI DLLs. The new
stageWindowsApp / windowsPackageMetadata tasks derive native directory and version from the
existing verifyPariEngine configuration, retaining module overrides without cache-path guessing.
No custom plugin or installer toolchain was introduced. JAVA_HOME / -JdkHome selects packaging
JDK; verified with JBR 21.0.7. Each invocation preserves previous outputs in a fresh GUID folder.

Artifact: desktopApp/build/windows-package/output/bb2d5051f3c54d01b6ce491bd8df93ba/ contains
PariKt/PariKt.exe and PariKt-windows-x64.zip. Keep the whole extracted PariKt folder together;
the EXE requires app/ and runtime/. No recipient JDK/Gradle/WSL is required. The package contains
exactly one PARI DLL (2.19.0, current desktop module override), JNI bridge and shared UI runtime.
PariKt-check.exe is a console diagnostic launcher; normal PariKt.exe is a GUI launcher.

Packaged console and GUI launchers both pass --check-engine using their bundled runtime and
$APPDIR/native path. ZIP extraction/relocation into a path containing spaces also passes the
real version/arithmetic/factorization/one-implementation check. Logs:
build/sample-redesign/windows-package-final.log and the output folder's engine-check logs.
Strict configuration-cache storage and warm reuse pass for stageWindowsApp (warm build: four
seconds); evidence: windows-package-cc-warm.log. The package has not been checked on a separate Windows machine
or launched interactively in this checkpoint, and is unsigned. README/contributor guide explain
building, portable-folder usage and diagnostic launchers. All targets/examples/minSdk 21 remain.

Sample fidelity correction completed after user feedback on 2026-11-05. Re-read the sibling's
PariScreen, PariViewModel and PariActivity: restored plain multiline Enter, icon controls,
vertical non-focusable completion popup, compact name/version title, default Material theme,
neutral compact result cards with swipe removal, and the scripts modal with an inline editor
and saved list. The console remains visible behind the modal; create/edit/rename/delete/load/run
and persistence remain available. The mobile field requests ImeAction.Default for a newline key,
rather than the reference's Done key; no keyboard action submits the command. The check button
runs input. This addresses multiline entry on mobile as well as hardware keyboards.

Verified by the expanded real PARI 2.19.0 Compose test: actual Enter inserts two newlines into a
GP block without evaluating it, mobile IME semantics request Default, check-button evaluation
returns 144, modal close/reopen/edit/rename/load/run/delete work, swipe removal works, and
settings/restart still pass. The nine state/file tests also pass. Android debug APK assembly and
JS/WasmJS application compilation pass: build/sample-redesign/fidelity-final.log (101 tasks,
13 seconds). Inspected console, multiline and modal screenshots under
desktopApp/build/reports/sample-ui/. README and contributor guide match the corrected behavior.
Device/browser runtime coverage of this redesign remains pending; existing targets/examples,
persistence and Android minSdk 21 are preserved.

The PariDroid-style sample adaptation is implemented and verified on 2026-11-05. The user chose
console layout/history/autocomplete plus settings and custom scripts, shared across Android,
desktop JVM and both web targets. consoleUi now provides compact input/control buttons,
newest-first removable result cards, cursor-aware suggestions, draft-preserving history,
copy/clear output, examples, validated stack settings/restart, and script create/edit/rename/
delete/load/run. The initial system-theme choice was superseded by the fidelity correction above.
The sibling paridroid-2026 repository was a
read-only reference and has not been edited.

Settings/scripts use a shared versioned, length-prefixed format with Android SharedPreferences,
JVM ~/.parikt/console.preferences (temporary file + replace), and per-origin browser localStorage
adapters. Load/save failures are visible; writes succeed before in-memory preferences change.
History/output remain session-only. Settings restart PARI on its initialization UI thread,
clearing variables/functions while retaining output/scripts. Saved-script Run evaluates before
navigating away so disposal cannot cancel it. Scripts use the existing GP evaluator, so a saved
definition such as sq(x)=x^2 can be run and then invoked from the console with sq(12).

Verification: eight common state tests, one JVM file persistence test and one live PARI 2.19.0
desktop Compose test pass. The integration test covers actual version/startup, factorization,
history, clearing, completion, Pi, saved function execution and applying 8 MB initial / 16 MB
maximum stack settings with successful restart. Test preferences are in memory, so developer
data is untouched. Android debug APK assembly and JS/WasmJS app compilation pass in the same
101-task build (22 seconds). Evidence: build/sample-redesign/verify-final.log and phone-size
screenshots under desktopApp/build/reports/sample-ui/. README and docs/CONTRIBUTOR_GUIDE.md
describe usage, architecture, persistence and checks. The existing Compose clipboard API emits
a deprecation warning on the pinned 1.8.2 release; compilation succeeds.

No Android-device or browser runtime check of the redesigned UI has been performed yet; device
results below predate it. All existing targets, both examples, explicit initialization, engine
thread confinement and Android minSdk 21 remain intact. Core engines and version-routing code
were not changed. Checklist: the sample follow-up at the top of PARI_MODULE_VERSION_PLAN.md.

Physical ARM64 demo smoke verification is user-confirmed: on 2026-11-05 the user reported that
the Android app runs successfully on their physical Google Pixel 8. This extends coverage beyond
the x86_64 emulator. The device's Android/API level, exact APK/PARI selection and individual
assertions were not recorded, so this does not claim the full two-version boundary suite or
API21 coverage. No code change was required for this reported run.

Android Studio run/debug verification passed on 2026-11-05 at 1368be8, with the repository's
PARI 2.15.0 demo on Pixel 8 Pro API36.1 x86_64 (emulator-5554). The IDE built assembleDebug,
installed and launched MainActivity; the console displayed Ready / 2 + 2 = 4 and factorization.
Automatic Java/native debugging attached (LLDB x86_64 plus the Java VM); a temporary Kotlin
breakpoint at MainActivity.kt:11 was reached, the stack/variables were visible, F8 stepped and
F9 resumed to Ready. The temporary line breakpoint was removed and both debug components stopped.
This checks Kotlin debugging with the automatic native debugger attached, not native C breakpoint
or stepping behavior. The user cold-booted/retried after an initial emulator installation failed
with INSTALL_FAILED_INSUFFICIENT_STORAGE. Installation subsequently succeeded without a code fix.

Build-variant switching remains incomplete: the panel lists the default and scoped helpers,
but the app's active-variant selector did not expose choices through the automation. A fresh
GUI Gradle sync passed in 29.057 seconds; no release -> debug switch was observed. Delayed failed
extra IDE launcher dialogs also appeared (IDE .port lock-file access); those launcher processes
have exited and the working project IDE remains open. Do not infer a project variant defect
from this inconclusive UI attempt. Logs and observed-step notes: build/ide-runtime-verification/.
During the IDE checkpoint no ARM64 device was connected; installed images are x86/x86_64 and
the API21 image is x86. The later user-confirmed Pixel 8 smoke run is recorded above; full ARM64
boundary-suite and API21 runtime checks remain pending. The SDK command-line package manager is not
installed; remote availability of other images was not established. The emulator-5580 process
started for verification has exited; the user's emulator-5554 and its installed demo are retained.
No production code, engine selections, targets, examples or Android minSdk changed.

Cold-cache Linux JVM verification passed on 2026-11-05 using the verifier at 0e02eae.
The default verify_cold_jvm.ps1 invocation downloaded Gradle 9.6.0 through the wrapper and used
empty dependency, PARI source, compiled-engine, native-work and Konan caches. The isolated
Kotlin-only consumer fetched PARI 2.19.0 from upstream with legacy source imports blocked,
downloaded GMP 6.3.0, and compiled the Linux x86_64 JNI engine. Compile/runtime graph selection,
version, arithmetic, factorization, command listing and recoverable GP error checks all passed.

The application build completed in 1m 50s with seven tasks executed and strict configuration-cache
storage. The warm build passed in four seconds, reusing the configuration cache and compiled
engine, with matching engine stamps/generation and no download or native rebuild. A separate real
ResumeLogDirectory invocation also passed runtime and warm reuse, preserving original cold.log
and saving the retry log. Evidence: build/cold-jvm/e6a5d568b76b/ (cold.log, warm.log,
cold-retry-*.log, environment.log, cold.stamp and cold.generation); launcher summaries are in
build/cold-jvm/2026-11-05-{latest,resume}.log. Durable Linux state is retained at
/home/pc/.cache/parikt-verification/cold-jvm.PgcA7R.

Host: WSL Linux x86_64, Ubuntu OpenJDK 21.0.12.1 and GCC 15.2.0. Installed JDK/C tools remain
prerequisites: this proves cold caches for Linux JVM PARI 2.19.0, not fresh OS/prerequisite
installation or other targets/hosts. Existing caches, both examples and Android minSdk 21 are
unchanged. No production code or host networking settings needed changing for this retry.
The earlier WSL TLS failure and interrupted wrapper download did not recur; historical failed
evidence remains in build/cold-jvm/23c9ec2861f8 and build/cold-jvm-harness. Earlier offline harness
checks remain flow/isolation tests only; the new run supplies the actual network/native/runtime
proof. No verification is still running.

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

Linux JVM cold-cache bootstrap and the user-confirmed physical Pixel 8 demo smoke pass as recorded
above. Full ARM64 boundary-suite coverage, API21 runtime, fresh OS setup including prerequisite
installation, and Apple/other build hosts remain unverified. Basic
Android Studio GUI sync and scoped demo run/Kotlin debugging pass as recorded above. IDE variant
switching and native C breakpoint/stepping workflows remain outside that verified scope.
The available API21 emulator is x86, outside the supported native ABI list. SDK compilation and an
API36.1 x86_64 run do not prove those cases. Publication is outside the completed source integration.
Browser pages were previously executed; this structural checkpoint verifies their rebuilt
bundles/graphs and Node execution. It does not repeat browser UI or emulator runtime execution.
