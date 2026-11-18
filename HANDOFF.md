# PariKt current handoff

## Maintenance rule

Update this handoff and PARI_MODULE_VERSION_PLAN.md whenever code, build configuration or
project documentation changes. Check off work only after verification. Historical rollout notes
are preserved in [docs/HANDOFF_HISTORY.md](docs/HANDOFF_HISTORY.md); they include superseded facts.

## Current work

Latest incident: source CI 44381227608 / 3fa4ce3 passes. Candidate run 44381272892 fails the
Native 2.4.21 cache assertion on Linux and both Mac hosts after successful publication/build/
runtime checks: first execution populates platform klibs. A separate cold provisioning phase
now precedes strict measured store/reuse. Local correction verification passes (ce10e4b5b279); hosted
verification will follow. Full candidate mode remains the release gate.

First-release execution is authorized: commit/push prepared work, fix hosted CI, finish clean
Maven consumers and signing/publication gates, then tag/publish. No release tag or Sonatype
upload has been created. RELEASE_PLAN.md tracks remaining gates. Existing source installers,
examples, default PARI 2.19.0, module overrides and Android minSdk21 are preserved.

Full source-installation CI is GREEN at a41523a / run 44353196417: Linux JVM/Native, Android
packaging, Node/browser/UI tests, Linux-to-Windows JNI/Native execution and both Mac hosts.
Intel fixture ab4782cb7efe runs macOS/iOS/tvOS/watchOS X64 tests; ARM fixture 4dbc4594ae86 runs
ARM64 host/simulator tests. Both link all twelve frameworks/test binaries and verify two GP
versions, explicit conflicts and reuse. Raw logs: build/ci-local/apple-full-*-a41523a.log.
Physical Apple devices, external Swift framework consumers and other Xcode profiles remain
outside this result. Tested source compiler/Xcode: Kotlin 2.4.21 / Xcode 26.4.

CI fixes are committed through a41523a: SDK-manager lookup; scoping the generated SwiftPM
inventory to the selected provider; excluding synthetic app inventory from explicit requirements;
GNU GMP mirror/fallback; modern Bison; and suppressing the legacy Darwin loader on mobile while
keeping GP dynamic loading disabled. Actual dependency graphs stay strict. Targeted SwiftPM
regressions pass (4396d7b427d5, f6ce59c40401); no conflict bypass on actual compile/runtime graphs.

Independent publishing/ build now tests Kotlin 2.0.0 (previous milestone used 2.0.21), checksummed Gradle 8.8 and built-in Maven/
signing plugins. Canonical library sources produce version-independent JVM/AAR/JS/Wasm/Native
bindings; engines remain separate and on demand. Native klibs contain no GP archive; web imports
the generic @parikt/engine module. Engine-tools contains C sources and ordinary Gradle scripts,
no Kotlin library implementation or compiled engines. The settings bootstrap loads that JAR
from Maven and extracts immutable hash-addressed tools under a file lock/value source.

Verified local Maven candidate results:
- Archive/POM/JVM identity/Android JNI rules: 29 current archives, four Native klibs, no engines.
- JVM: all six 2.0-2.4 release points, two versions/three apps/shared library/conflicts/reuse:
  e80fc6af4124. Maven-only bootstrap: 28558c04de51; module named :parikt: 76f958e35c6c.
- Strict bootstrap configuration-cache store/reuse: 0235cebeeb1c (Kotlin 2.4.21).
- Windows Native: Kotlin 2.0.0/2.4.21, two apps/shared library, one raw selected archive per link,
  actual runtimes, visible conflicts and warm hashes: d134ac6f2de1. Old KGP C SDK bootstrap is wired.
- Android: debug/R8 APKs for both engines, both ABIs, minSdk21, Startup metadata, stripped payload
  hashes, engine-free shared AAR and conflicts: fa55b711b95c. All four APKs run on an owned
  API36.1 x86_64 emulator; proof apps/AVD data/emulator were removed. Physical phone untouched.
- Web: Kotlin 2.4.21 JS/Wasm Node, four production bundles, custom fragments, explicit conflicts
  and all four real headless-browser tests: 0a7f30a4f8aa (Node/bundles also 68cb5b0b1f65).
- Signing: nine signatures verify with an isolated one-day Ed25519 key: 5f65f628b190. Private
  test keys removed; no production keyring or KProxyable secret values accessed.
- Five source property diagnostics pass after sharing parsing and fixing Maven reserved names.

Publication-baseline follow-up: Kotlin 2.0.21 bindings exposed a 2.0.0 Wasm runtime ABI error
(6d48fcbbc93c). Publishing with 2.0.0 fixes it. All 29 non-Apple candidate archives inspect clean.
All six JVM points pass strict cache storage/reuse (8cc5e3b22d5a); Native 2.0.0/2.4.21
runtime/conflict/hash and strict cache checks pass (fd94ebf0da0d). JS/Wasm Node, bundles,
custom emitted-fragment assets, four real browser tests and conflicts pass under Kotlin
2.0.0 (6a85dc2a10fc) and 2.4.21 (052754ba3fad). Android debug/R8/both-ABI/minSdk21/shared-AAR/
conflict packaging passes (a93895e60b53); runtime was not repeated after the baseline change.
Old KGP Karma needs explicit emitted-asset serving; the proof uses public useConfigDirectory.
The JVM probe inspects actual engine directories rather than repeated console headers.
JVM/web guarded resume modes retain isolated Maven snapshots. Intermediate web compiler lines
and Android/web strict cache checks remain unverified. Corrected-baseline hosted candidate
jobs will require strict JVM/Native cache store/reuse, both verified locally.

Source CI at 9b66110 is green (44375696793). Expanded Maven run 44375737186 passes Linux
(six JVM points c57ab3deb785, Native endpoints 86b47dc951ff) and ARM Mac (all twelve publications,
24 klibs, host consumer endpoints a66df5cc5cb8); Intel Mac still runs. Hosted artifacts use
the previous 2.0.21 baseline and need the corrected-baseline rerun.

The parser reserves :parikt/:pariktAndroidNative only in source mode. Maven consumers can use
those module names and still enforce their explicit requirements. Android uses the canonical
C-only helper per selected version, attached only to final applications. Native final links use
consumer SDK/deployment metadata and reject embedded extra GP archives. Web uses per-process
Node routing plus per-application Webpack aliases; late user fragments are preserved during
config generation, with bundling untracked so edits are observed. Engine/compiler caches remain.

Candidate milestone committed/pushed as 9b66110. Expanded manual candidate run 44375737186
is in progress; source CI 44375696793 is green. Next: finish corrected-baseline candidate checks (Linux Native
and Intel/ARM Apple Maven builds/consumers), broaden bootstrap/old-compiler web checks and strict
cache checks, then implement the concrete signed Central bundle/upload path. Candidate jobs have
no upload/signing credentials. GitHub names-only check found no Actions secrets/environments;
user must eventually configure publication credentials. Do not tag/upload incomplete candidates.

All probes now use the embedded Maven bootstrap; earlier Native/Android/web runtime evidence
used the same extracted tools directly. Recheck those bootstrap paths in the expanded workflow/
local smoke runs, now completed as recorded above. The source build remains supported. See publishing/README.md for script roles.

Environment verification follow-up completed on 2026-11-18 for the available Android/browser
sample checks. Root JS/Wasm lockfiles still described the old 2.15.0 regular engine after the
2.19.0 default change; updated both through KGP's upgrade tasks. Both production bundles now
build with normal strict lock checks, and each includes the selected 2.19.0 engine. No lock
mismatch bypass or engine-selection restriction was added.

Actual JS and Wasm browser samples pass startup, plain Enter/multiline evaluation (144/169),
autocomplete selection, factorization, saved-script execution (169/196) and settings restart.
JS history and script creation/reload persistence pass; Wasm reads the same-origin saved script
and executes it. Scripts remain modal with the console visible behind them. Both browser console
error lists are empty. Both library browser boundary suites also pass in headless Brave/Chromium
(two tests per adapter). Current pinned compiler only; no new older-compiler/browser claim.

Disposable API36.1 x86_64 Android sample passes Ready/version/startup math, plain Enter/multiline
evaluation (144), autocomplete selection, factorization, history, modal script creation/run (225),
script persistence after force-stop/relaunch, and settings restart/startup math. XML dumps omit
some Compose popup windows, so screenshots also verify popup/modal states. Early harness retries
include a leftover-draft parse error and a missing popup XML assertion; cleared draft and visual
inspection resolve those harness issues without a production UI change. Existing desktop UI
integration and state/file suites pass. No physical-device, ARM64 or API21-runtime claim.

Windows package rebuild and bundled-runtime engine check pass. Output:
desktopApp/build/windows-package/output/4d1b22a22c8e4ae3b81b57f28cff52c9/.
Interactive packaged UI remains unverified: computer-use launch returned
"Computer Use was not approved to use parikt". Temporary test user.home configuration was
restored; the ZIP is unchanged. No separate Windows machine is available.

Evidence: build/environment-verification/{build-retry,browser-tests,windows-package}.log,
browser-errors.json, js-scripts.jpg, wasm-script.jpg, android/{multiline,script-result}.xml,
android/script-persisted-ready.png and android/settings-after-restart.xml. Disposable AVD/data
are under build/environment-verification/android; only its emulator was used. The connected
Quest device and user's AVDs were untouched. The owned emulator and both local-only HTTP servers
were stopped after verification. No supported API21 or ARM64 image is installed.
Broader IDE variant/native C debugging, fresh OS prerequisites, other hosts and Apple runtime
verification remain open.

Everyday engine simplification (2026-11-05): root parikt.properties now selects PARI 2.19.0.
Removed desktopApp/parikt.properties so all regular apps inherit one version. Multi-version
examples and compatibility fixtures remain opt-in and retain their explicit test versions.
verify_application_versions.ps1 now creates its 2.15.0-root/2.19.0-desktop setup temporarily
and restores root bytes and the desktop file's original contents or absence in finally.
README/contributor setup now describe the single regular engine. Verification passes: all regular
participants inherit 2.19.0, both JVM apps execute arithmetic/factorization and share one provider
(build/pari-219-default.log). All 11 application acceptance cases pass, including reuse/conflicts;
root bytes and the absent desktop override are restored afterward. Logs:
build/application-version-tests/796eadd43e6c41bb917ef4b326031153/. Existing caches are retained;
this change does not delete previously built versions or restrict consumers' version selection.

Kotlin 2.x source-consumer compatibility is implemented and verified on 2026-11-05. User scope:
library consumers, keeping sample applications on the pinned 2.4.21/9.6.0/AGP9.4.1 toolchain.
Exact matrix/implementation/checklist/commands: KOTLIN_COMPATIBILITY_PLAN.md. Tested Kotlin
2.0.0, 2.0.21, 2.1.21, 2.2.21, 2.3.21 and 2.4.21 across JVM, JS/Wasm Node, Windows/Linux Native
and Android debug/R8 release builds. No promise for every historical/future patch or iOS yet.

Changes bridge Gradle 8/9 project dependency paths, optional old/new KGP web tooling and
module-name APIs, and AGP 8 androidTarget versus AGP 9 KMP DSL. Actual KGP version controls
interop opt-ins and Native inputs. Legacy Native reads its managed konanHome, delegates missing
compiler installation to KGP's downloader, and links a tiny program to provision the target SDK;
current KGP retains its download task. JS/Wasm external array/Promise types are platform actuals
behind shared protocol/loading handles. Public PariKt API and shared loading/cancellation logic
are retained. No custom plugin or metadata-check bypass; KProxyable was inspected read-only.

Full matrix: build/kotlin-consumers/aa26c6fac8ca/results.json, all current rows pass. Each compiler
runs applications sharing a library with PARI 2.15.0/2.19.0, verifies JVM single-class identity,
strict cache reuse/conflicts, Native single-archive identity and Windows/WSL execution, web
execution and Android graph/manifest/ABI payloads plus R8 shrinking. Initial missing Native
2.0.0 installation failure is preserved in results-initial.json; resumed Native check passes
and retains PreviousLog. Six-compiler JS/Wasm boundary/lifecycle matrix also passes (24 cases):
fa10a229d220/results.json. Oldest rootless consumer: aa26c6fac8ca/2.0.0/rootless.log.

Actual R8 release APK runtime passes for both engines under Kotlin 2.0.0 and 2.4.21 on API36.1
x86_64. Test debug signing and workspace-owned AVD/data were used; emulator-5582 was stopped.
User's AVD was untouched. Evidence: build/kotlin-consumers/android-runtime/*release-*.{xml,log}.
Pinned regression passes: pinned-regression.log, 175 tasks/1m55s including shared UI/state tests,
Android packaging, web compilation/runtime and both Native boundary suites. Oldest/newest web
consumers/boundaries also pass after consolidating the module-name adapter. A manual temporary
fixture graph change needed a fixture-only npm lock upgrade; production lock enforcement stays.

README/contributor guide document consumer catalog overrides, exact Gradle/AGP profiles,
oldest-profile R8 override and PowerShell runner/resume behavior. All targets, examples, engine
routing/reuse and Android minSdk21 remain. Verified host: Windows/JBR21 plus WSL Linux; API21
runtime, other hosts, old-compiler browser runtime and other patches are separate coverage.
User's Mac for future iOS work: 2019 Intel i5 quad-core 1.4 GHz, 16 GB RAM, macOS15.7.5/Xcode26.3,
free Apple account; not attached here. Apple source support is now implemented above; actual
Mac verification and Apple sample app integration remain separate work.

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

## Native candidate cold-SDK cache correction (2026-11-18)

Run 44381272892 / 3fa4ce3 fails the same Native cache assertion on Linux, ARM Mac and Intel
Mac. All candidate publication/content checks, all six Linux JVM/cache cases, Native 2.0.0
cases and 2.4.21 builds/runtimes succeed. KGP populates klib/platform/<host-target> during
the first 2.4.21 Native build; Gradle correctly invalidates the cached configuration afterward.
The local Windows proof used an already prepared SDK and missed that cold-runner condition.
The Native probe now provisions once with --no-configuration-cache, then requires a strict
stored configuration and immediate reuse on the identical task graph. Archive counts, hashes,
actual runtimes and explicit conflicts remain checked. Local and hosted correction verification
are pending; this does not establish first-to-second-build cache reuse on an unprepared SDK.

Candidate workflow now has explicit native_only manual input (default false) for targeted Native regression feedback; full mode retains all JVM checks, and both modes retain publication/content plus all Native checks. The focused hosted rerun is pending.

Local phase verification caught a harness expectation that link actions repeat after provisioning; up-to-date links correctly skip those actions. Archive-identity diagnostics are now required in the actual provisioning link, while the measured cache-store phase requires successful storage. Final runtime/archive/hash/conflict checks remain. Rerun pending.

Local corrected three-phase Native probe passes both Kotlin 2.0.0/2.4.21 (ce10e4b5b279):
actual provisioning links, strict cache storage/immediate reuse, both runtimes, archive counts/
hashes and conflicts. Hosted cold-SDK verification remains pending. PowerShell syntax, actionlint,
metadata and whitespace validation pass. No production engine or target configuration changed.
