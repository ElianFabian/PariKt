# PariKt handoff and continuation plan

## Handoff maintenance rule

Whenever project code, build configuration, or documentation changes, update this handoff in the
same change: check off completed checklist items, revise any affected facts or decisions, and record
verification results or blockers. Do not mark an item complete without confirming it.

## Verified reference: `paridroidgp`

`paridroidgp` is an Android library module inside a repository that also contains the Paridroid app.
Its Android namespace and the app's `applicationId` are both `fr.ubordeaux.math.paridroid`. That
identity match matters: the native wrapper sets `GPTMPDIR` to
`/data/data/fr.ubordeaux.math.paridroid/cache`, and the native build configures PARI with the same
package-specific data directory. Those paths work in that paired app setup; they should not be
copied as assumptions for an arbitrary app consuming PariKt.

The module uses the standard Android library plugin with Kotlin Android, `externalNativeBuild`/
`ndk-build`, and `Android.mk`. `Android.mk` declares prebuilt `libgmp.so` and `libpari.so` for the
selected ABI, then compiles the JNI bridge together with `paridroid.c` into `libparidroid.so`. The
native script builds GMP and PARI only; the Android Gradle/NDK build compiles the wrapper and JNI
bridge.

Its `build.gradle.kts` behavior is:

1. Read `pariVersion` and `nativeAbis` from Gradle properties. Default ABIs are `arm64-v8a` and
   `x86_64`; NDK version is `28.2.13676358`; minimum Android API is 21.
2. Prefer `.native/android-$pariVersion` when it contains GMP and PARI libraries for all selected
   ABIs. Copy valid legacy `.native/android-pari-$pariVersion` or `.native/pari-$pariVersion`
   outputs there once, preserving the old caches. For PARI 2.17.1 only, it can fall back to valid
   bundled libraries under `src/main/jni`.
3. If dependencies are missing at configuration time, run `native/build-native.sh` immediately using
   WSL on Windows and bash locally elsewhere. This bootstrap path ignores `nativeRunner`, streams
   output, and fails Gradle if the process fails. As a result, Docker does not replace WSL for a
   fresh Windows checkout when dependencies are absent.
4. Register `buildPariNative` and wire it to Android NDK tasks when dependencies are missing or
   `nativeForce` is present. This task supports `local`, `wsl`, and `docker`; its default is `wsl`
   on Windows and `local` elsewhere. The later task runner is separate from the configuration-time
   bootstrap above.
5. Pass the chosen dependency directory to `Android.mk` using `NATIVE_DEPS_DIR`.

`ParidroidGP` calls `loadLibrarySafe` for `gmp`, `pari`, and `paridroid` in its object initializer
and catches `Throwable`. `PariNative` has a second object initializer that loads the same three
libraries and catches `Exception`; it runs when `ParidroidGP` first calls a native method. Thus
loading is duplicated on the normal path, with different failure handling. `ParidroidGP` checks
initialization for eval and command listing. Its close method calls native close and resets its
initialized flag. The comments describe init as one-shot because PARI owns global state; the code
itself permits a later init after close. Preserve this as observed behavior until tested before
changing lifecycle semantics.

## PariKt current state

Native production integration is committed as 44ecbb5; web integration is committed as c81cf14.
Production Android integration is implemented and its 14-case acceptance passes:
build/android-tests/019645ae5a8b/logs. Both ABI split APKs have selected-only native payloads,
source classes/metadata match, and root-default/rootless shared/independent chains pass along
with strict shared/source/helper conflicts, cache reuse and restored defaults. Both rootless
x86_64 apps actually pass Startup/version/evaluation/error recovery on API36.1:
build/platform-android-runtime-production-final.log. The owned emulator used a separate fresh
data image; proof apps were removed and the emulator stopped. No unrelated apps were removed.

The real Compose Android app builds with a temporary 2.19.0 override and correct debug/release
graphs. Its property is removed and 2.15.0 binaries are restored. All-target regression passes:
175 tasks (55 executed/120 up to date), build/platform-android-all-targets-final.log. All 11
real-application cases pass (build/application-version-tests/b871d973ea404ab9b177ab575f571900),
as do all 13 module-only JVM cases without AGP (build/production-module-only/7c655f90bd95/logs),
five property diagnostics and 24 discovery checks. Final web production regression also passes all 14 cases plus portable locks (build/wp/50ce7e896860/logs).

Android settings now emit stable complete scripts before evaluation; optional Android setup uses public model properties when the consuming root shares AGP. Kotlin-only rootless
builds retain no Android prerequisite. Helpers use canonical sources, versioned consumer caches
and distinct CXX/build paths. Startup/minSdk21 are preserved. Bootstrap runs after project
configuration, filters participating Android selections for CLI tasks, and conservatively prepares
all configured helper models for IDE requests. Cold model setup can precede graph conflicts;
Android Studio sync UI itself, ARM64/API21 runtime and fresh host setup remain unverified.

Web uses versioned imports/npm packages and build/parikt/web-engines/<version>; package metadata
staging does not build unused C/Wasm payloads. Public packageJson actions prune inherited engine
packages; selected Node/browser tasks supply payloads. Runtime graphs and bundle hashes verify one
selected engine. JS webpack 5.112.2 is coherent across KGP/npm; Wasm tooling stays separate.
All 14 web acceptance cases and portable locks pass at build/wp/b2d3df3c69e8, and all four browser
pages actually run. Real Compose browser override/default builds and standalone rootless JVM,
Windows/Linux Native and both Node runtimes pass. Both examples folders are preserved.

The module-version implementation checklist is complete in this Android checkpoint. Remaining limits are additional host/IDE/runtime coverage and publication, outside this completed rollout. Both examples folders, all existing targets and minSdk21 are preserved. No temporary override or owned background service remains. See PARI_MODULE_VERSION_PLAN.md for the completed checklist and exact verification scope.

Default/scoped providers now share group com.elianfabian.parikt.engine, stable :parikt leaf names
and Native module name parikt. Scoped providers combine typed JVM/Native templates referencing
the pinned source files and consumer-owned version/target caches. Source/interop manifest IDs,
metadata and IR hashes match across default and scoped providers on Linux/Windows. Shared Native
metadata and interop commonization also pass (build/platform-native-common-metadata.log).

Production routing covers Native/common graphs. Explicit competing provider requests fail before
Gradle can merge versions. Participating Native consumers have verifyPariNativeGraph plus final
link checks scanning every klib for exactly one selected libparikt.a hash. The applied script uses
Gradle Groovy interop to read the public libraries property, avoiding unavailable KGP compile
types; generated templates retain typed KGP access. verifyPariNativeIdentity checks both targets.

All ten verify_native_module_versions.ps1 -Production cases pass: build/np/9940f9b6c63e/logs.
They use the actual installer, consumer-owned output paths and seeded prepared C artifacts/stamps,
covering both runtimes/orders, same-version reuse, rootless shared/independent chains, missing and
strict shared requirements, higher/lower provider requests and restored root defaults. No new
source fetch or C engine build occurs. Windows x86_64/WSL is verified; standalone Linux-host Gradle
execution remains unverified. The original isolated proof mode is preserved.

All 13 module-only JVM cases pass (build/production-module-only/c982d688cd1a/logs), as do all 11
real application cases (build/application-version-tests/dcd57bf50af5471486865d95b9d251b5). Expectations
now use nested provider paths and early conflict diagnostics; the no-default task assertion uses
an exact root prefix. The real Native CLI passes temporary 2.19.0 override runs with factorization
and close on Windows/WSL, then both properties and binaries are restored to 2.15.0. Logs:
build/platform-native-real-cli-*.log and build/platform-native-cli-default-restored.log.

Android assembly/minSdk 21, shared web compilation and actual Native/JS/Wasm tests pass together:
146 tasks, 41 executed/105 up to date (build/platform-native-target-regressions.log). Both READMEs
and version plans describe the newly supported production Native scope. The standalone consumer
passes JVM and actual Windows/Linux Native execution on 2.19.0 (build/platform-native-consumer-regression.log;
Linux also launched directly through WSL). All five property diagnostics and 24 scoped discovery
cases pass: build/platform-native-{property-diagnostics,selection-regressions}.log. No temporary
module override remains. Native is ready to commit; next implement production web package routing.

Production module-only JVM configuration is implemented and verified. Without a root default,
the production installer includes empty :parikt/:pariktAndroidNative selectors and generates JVM
providers for explicit module selections (now extended with Native targets). No local selection becomes a sibling/root default;
default-engine tasks fail clearly instead of guessing. JVM/common configurations route to their
selected provider, including JVM-only KMP metadata. Every participating module needs a selection;
an explicit library selection remains strict. Android/web production configurations now pass their installer acceptance suites above.

All 13 disposable verify_production_module_only.ps1 cases pass using the actual production
settings script: build/production-module-only/9a887c3c6a0f/logs. They cover shared same-version
apps, both orders/cache reuse, common metadata, unrelated modules, missing/invalid selections,
strict shared conflicts, independent 2.19.0/2.15.0 application chains, duplicate rejection,
ambiguous output overrides, default-engine/helper errors, empty-root rejection and restored
valid rootless builds. No source fetch or engine compilation occurred. The metadata probe checks
only common configurations with PariKt dependencies, excluding empty tooling/test configurations.

All 11 existing application cases also pass (build/application-version-tests/02dc66be474c483cbedcc2e684b0ec84),
including shared UI tests and restored property/build files. All five property diagnostics pass;
the missing case now declares a participating root, since unrelated builds need no selection.
The standalone examples/consumer JVM run passes on 2.19.0. Existing Android assembly (minSdk 21),
consoleUi JS/Wasm compilation and Linux/Windows Native checks pass in a 97-task regression build.
Logs: build/module-only-{property-diagnostics,consumer-regression,target-regressions}.log.
README and the module plan describe module-only JVM setup and the remaining Android/web gate.
The module-only suite is verified on Windows x86_64/WSL with prepared 2.15.0/2.19.0 engines;
this cache prerequisite is explicit in the script/README. Windows PowerShell 5.1 also passes all
five updated property diagnostics. All 24 scoped-discovery regression cases also pass:
build/module-only-selection-regressions.log. Disposable fixtures are removed; checked-in demo
property/build files are restored. Both examples folders, all existing targets and minSdk 21 remain.

Step 7's isolated Android proof passes behind -PandroidProof. androidAppA inherits 2.19.0;
androidAppB overrides 2.15.0. Both share androidSharedLogic compiled against the default and
generate arm64-v8a/x86_64 APK splits with minSdk 21. Generated source providers reference shared
sources and the existing Startup manifest. Scoped helpers reuse the production helper script,
with separate helper/CXX/model outputs and shared versioned native caches. Each runtime graph
contains exactly the selected engine/helper; the implementation-only helper need not appear on
compile graphs. Kotlin class and module metadata hashes are identical across both providers.

All eight disposable PowerShell 7 cases pass: build/android-tests/0eb0443f458e/logs. They cover
both orders, same-version reuse, override removal/restoration, strict shared requirements,
duplicate engine/helper rejection and restored builds. Actual assemble-task conflicts fail before
packaging. Every split contains exactly one GMP/PARI/JNI/C++ runtime set; PARI/GMP hashes match
the canonical selected cache binaries after standard NDK stripping. Cache cases fetch no source
and rebuild no GMP/PARI engine. Both helper AARs also assemble with exactly those two ABI sets,
distinct namespaces and minSdk 21 (build/module-android-helper-aars.log).

Both x86_64 apps actually pass on the API-36.1 emulator with automatic Startup, exact versions,
arithmetic, factorization and error recovery (build/module-android-runtime.log). Storage required
sequential installs; only the newly installed proof apps were removed. The test-started emulator
was stopped afterwards. Android Studio sync UI and arm64 runtime remain unverified; NDK model
configuration/build passes for both versions/ABIs. Initial AGP/KGP classloader failure was fixed
by generating an opt-in root script with standard apply-false AGP declarations. Other proof modes
do not load AGP. Activity sources use AndroidSourceSet.kotlin.directories; the Android-only shared
library connects commonTest through withHostTest.

The production demo assembles and filters unsupported transitive 32-bit graphics ABIs. aapt
confirms only arm64-v8a/x86_64 and minSdk 21 (build/module-android-production-regression.log).
Default JVM apps/identity/graphs, Native identity/graphs and all four JS/Wasm Node apps/graphs
pass after the Android changes: build/module-android-{jvm,native,web}-regression.log.
Configuration/discovery regression results are in build/module-android-selections-regression.log.
All 24 selection/discovery cases pass. No temporary proof app or test-started emulator remains.
Both READMEs describe the proof, ABI splits and actual verified scope. Production non-JVM
overrides remain gated pending shared component/output integration.

JVM integration is committed as 5610057; the isolated Native proof is committed as 35ff177.
Step 6's isolated JS/Wasm proof is complete behind -PwebProof in examples/module-versions.
Production web/Android/Native overrides now pass their installer acceptance suites above.

webAppA inherits 2.19.0 and webAppB selects 2.15.0; both call webSharedLogic compiled against
the default. Generated private @JsModule bindings import distinct @parikt/engine-<version>
packages. App packageJson handlers remove inherited default-engine npm dependencies when they
conflict with the app selection. Each app manifest contains one engine dependency; canonical
npm junction paths/hashes verify the selected package. JS workspace webpack is pinned to one
5.112.2 instance, with an explicit manifest task input; Wasm uses KGP's separate tooling.

Package metadata can be staged without native payloads. Only selected run/test/bundle consumers
request payload copies and native builds; npm ordering constraints do not schedule unused engines.
Browser webpack tracks only the selected payload and uses the existing Emscripten Node fallbacks.
JS/Wasm klib unique names remain provider-specific; public ABI signatures and metadata formats
match. The ABI diagnostic locates the installed pinned Native klib tool via standard konan caches
or a recorded path; a missing-record fallback test passes (build/module-web-abi-cache-fallback.log).
Node/browser compilation itself does not require that diagnostic tool.

All nine disposable PowerShell 7 cases plus portable-lock verification pass:
build/web-tests/4970dbf2bca3/logs. They cover unused payload suppression, both orders/cache reuse,
same-version reuse, override removal/restoration, explicit requirements, duplicate rejection and
restored Node/npm/bundles. Cache/reuse cases fetch no sources or compile native engines; shared
Kotlin compilation is reused. Every browser distribution has exactly one matching PARI Wasm hash
and none from another version. All four final browser pages actually run over loopback in IAB
and display their selected versions plus arithmetic/factorization/error-recovery success.
Combined Node/browser commands use production Node runs: mixing development and production syncs
in one invocation hits KGP's shared npm output-directory validation. Portable lockfiles are kept
in kotlin-js-store; property edits regenerate them using the standard JS/Wasm upgrade-lock tasks.
Verification: build/module-web-scoped-production-proof.log and the acceptance logs above.

Default JVM identity/graphs/apps and Native identity/graphs pass after the web extension
(build/module-web-jvm-regression.log, build/module-web-native-regression.log). The Native SDK
cache fallback passes with the recorded path temporarily removed and restored. All four final
browser outputs were rechecked in a fresh IAB tab; temporary tabs/server are cleaned up.
All 24 configuration/discovery cases also pass (build/module-web-selections-regression.log).
Next: finish step 3 production component/output integration for Native, web and Android, carrying
the proven identities and package checks into the shared settings/build scripts. Then finish
module-only non-JVM coverage and step 8 regressions/documentation. Keep examples/consumer
and examples/module-versions; they verify standalone installation and multi-app boundaries.

JVM integration committed as 5610057. Step 5's isolated Native compatibility proof is complete,
opt-in via -PnativeProof in examples/module-versions. The subsequent production Native rollout is recorded above.
The nativeAppA/nativeAppB applications share nativeSharedLogic, compiled against the 2.19.0
root default once per target, while nativeAppB explicitly selects 2.15.0. Both engines run on
Windows x86_64 and WSL Linux with version, arithmetic, factorization and error-recovery checks.

KGP 2.4.21 derives its read-only interop module identity from group/project.name/interop name.
Providers therefore use :pariktEngine_<version>:parikt paths with stable leaf/group names.
Namespace parents have empty scripts; Native templates are combined into generated project
scripts so KGP APIs use the plugin classpath. Both apps share native-app.gradle.kts. The Native
source hierarchy/commonization is explicit, and custom tasks run Linux executables through WSL.
Stable leaf/group names also share Gradle's default capability: requested provider edges must
be checked as well as resolved components, otherwise version coalescing can hide a competing
provider. This guard must be preserved when integrating Native routing into production.

All seven tests/build_configuration/verify_native_module_versions.ps1 cases pass: forward and
reverse orders, matching-provider/cache reuse, explicit shared-library conflicts, higher and
hidden-lower duplicate providers, and restored executables/graphs after injected failures.
Negative cases stop before native preparation. Metadata/IR and manifest dependency identities
match across both source and interop providers on both targets. Every final link contains
exactly one libparikt.a whose hash matches the selected engine. No new source fetch or Native
engine compilation occurs on cache/reuse/restoration cases. Logs: build/native-tests/fbf7cc895dc8/logs.
The harness copies the fixture, uses short paths for Windows GNU ld.gold's archive path limit,
and safely removes the generated fixture. Checks run under PowerShell 7 on Windows/WSL;
standalone Linux-host Gradle execution and Windows PowerShell 5.1 were not tested here.
The final default JVM fixture identity/graphs/apps also pass without deprecation warnings
(build/module-native-jvm-regression-final.log). README and fixture README document the proof.
JVM graph tasks are registered only for JVM consumer plugins; Native consumers expose their
own verifyNativeGraph task. Final task-registration checks pass for JVM graphs and Native
graphs/identity (build/module-native-task-scope-{jvm,native}.log), without deprecation warnings.
Step 6's web proof is now complete; production Native rollout has since passed its separate
step-3 checkpoint. Web/Android integration is now verified above. Existing targets, examples and minSdk 21
are unchanged. The Native proof is a separate checkpoint from the JVM integration.

Production JVM routing/launcher integration is implemented using terminalApp, desktopApp and
consoleUi. The default :parikt provider retains all existing targets; JVM overrides use scoped
source projects and selected JavaExec/Test native outputs. Both consoles share ConsoleSession
and offer headless engine checks. desktopApp selects 2.19.0; the root stays 2.15.0. All 11
tests/build_configuration/verify_application_versions.ps1 cases pass: smoke checks in both
orders, graph/identity, desktop UI/shared-state tests, same-version provider/cache reuse,
override edits/removal, shared-library requirement conflicts, ambiguous directory rejection,
non-JVM gating, invalid local files, duplicate-provider rejection and restored valid builds.
Temporary edits restore bytes in finally; logs are under
build/application-version-tests/8276cdb43e164afbb374d77601afa93b.
Applied routing runs after each project's plugin block to preserve Kotlin DSL catalog
classloader setup. A pre-fix idle daemon required restarting; the full suite then passed on
a warm daemon, including recovery after negative cases. Android assembleDebug (minSdk 21),
consoleUi JS/Wasm compilation and existing Linux/Windows Native checks pass: 97 tasks,
21 executed/76 up to date (build/application-target-regression.log). The ordinary terminal
factorization/quit flow passes (build/application-terminal-interactive.log), as do all five
property diagnostics (build/application-properties-regression.log). All 24 scoped selection
checks pass (build/application-selections-regression.log), and examples/consumer :app:runJvm
passes on 2.19.0 (build/application-consumer-regression.log).
The older property/selection verification runners now redirect to files to avoid output pipes inherited
by idle daemons. Non-JVM overrides remain gated, minSdk 21 is unchanged, and both examples
folders are retained. README documents real-module JVM selection and the platform gates.

PARI_MODULE_VERSION_PLAN.md implementation has started with the isolated examples/module-versions
JVM proof. It uses root defaults/module overrides, generated KMP JVM source components, scoped
project substitution and a common engine capability. Steps 1–2 are complete in the experimental
JVM selection layer. Steps 4 and 5–6's isolated Native/web proofs are complete; steps 3 and 7–8 remain
open in part. Production JVM routing now uses that design,
while web/Android retain the root selection; Native production routing is verified above.
Both applications pass in one invocation in both orders with 2.19.0/2.15.0, exact arithmetic/
factorization checks and one PariKt class resource per JVM. sharedLogic compiles against the
default once and its unchanged jar works with both providers. Class/Kotlin module metadata hashes
match across providers (stable module name parikt); compile/runtime graphs each contain one engine.

The new tests/build_configuration/verify_module_versions.ps1 passes all seven cases in PowerShell
7.6.9 on Windows 11 x86_64 with WSL native builds: both orders/cache reuse, matching requirement,
compile/runtime requirement conflicts, duplicate-provider rejection, and restored graph. Negative
cases fail before native setup and report the required origins/dependency path or capability
conflict. Copied test fixtures are cleaned up; logs are under build/module-version-tests. The
checked-in fixture passes identity, all three module graphs and both app launches again with
--warning-mode all and no deprecation warnings (build/module-versions-final-graphs.log). Root help
and terminal Kotlin compilation pass (build/module-versions-baseline.log). Script execution on
Linux/Windows PowerShell 5.1 and full cross-target regressions were not performed for this proof.

Step 2: shared strict parsing is now used by production and the experimental JVM
integration. Deferred settings discovery replaces the prototype's three-module list and reads
actual projectDir mappings. The JVM diagnostic task is pariktSelections; missing selections are
required only for modules with direct/transitive PariKt dependencies. :parikt convenience tasks
use only the root default and fail actionably when it is absent. All 24 cases in the new
verify_module_selections.ps1 pass in PowerShell 7.6.9 and Windows PowerShell 5.1, without engine
compilation/deprecation warnings. The existing five production diagnostic cases pass in both
shells. Logs are in build/module-selection-tests, with shell transcripts in
build/module-selections-{verification,ps51}.log. Root help/terminal compilation also pass with
--warning-mode all (build/module-selections-baseline.log). The seven original JVM runtime/graph/
conflict cases pass after generalization. The eighth case also passes: remove the root file,
explicitly select matching 2.15.0 in appA/appB/sharedLogic, compile/resolve both apps to one reused
source component and run both with version/arithmetic/factorization assertions. The module-only
native cache is reused. All eight cases pass under PowerShell 7; see
build/module-selections-jvm-regression.log and build/module-version-tests/*.log. Temporary
fixtures are cleaned up. Next is step 3's output isolation/routing work; keep the platform gates.
User clarified reuse is mandatory: a module requesting an already downloaded PARI version must
reuse it, and compatible engine builds must share one provider/cache entry across modules.
Do not interpret module-local selection as a forced download/rebuild. The JVM fixture already
deduplicates same-version source components and engine paths within a build. The cache-reuse
portion of step 3 is now implemented: all four builders call pari-source.sh for one shared Git
object store and pristine commit snapshots in ~/.cache/parikt/sources. Legacy downloads seed
the store from immutable tag objects, not patched working files, and remain intact. Git stores
are fully initialized before atomic publication. Separate mutable workspaces have recipe/target
identities and reset to pristine sources when those identities change. Web's PARI stage also
includes its build input hash. Different targets/ABIs/toolchains still need different binaries.

JVM complete engines are shared under ~/.cache/parikt/engines/jvm/<fingerprint>, keyed by resolved
commit and compatible build inputs. Force replaces the same entry; generation markers refresh
other consumers' staged copies. Release/master/latest labels resolving to one commit reuse one
entry. Linux's optional-data directory remains an actual build option in its cache identity.
Other backends retain existing compiled stage caches; their production module routing is now verified above.
Portable locking now combines exclusive file claims with legacy directory guards, supports
multiple held locks, checks ownership/subshell identity on cleanup, and needs no extra package.

Verification: all nine source-cache cases pass in PowerShell 7 and Windows PowerShell 5.1,
including three actual concurrent requests with one acquisition, recipe reset, failure/retry
and ownership cleanup. The contention suite passed three repetitions per shell; the expanded
nested-lock test passes. All eleven module/runtime/conflict cases pass with the final code:
build/cache-reuse-jvm-final.log. A later appC reuses the existing engine without download or
compilation; force has one producer and following consumers reuse it. All four independent-
workspace compiled-cache cases pass: build/cache-reuse-concurrent-jvm-final.log (concurrent
producer, force/refresh, metadata repair, release/master/latest alias reuse).

The JVM harness had stalled waiting for captured output after its Gradle child exited. It now
reads direct UTF-8 file logs after client exit, using unique per-run log directories so inherited
daemon handles cannot block subsequent runs. build/module-version-tests/latest-run.txt identifies
the latest retained case logs. Generated fixtures are removed after safe path checks.
The final cross-target run passed 145 tasks (24 executed/121 up to date): Android minSdk-21
assembly for both ABIs, Native Linux/Windows verification, direct Windows Native test execution,
web engine smoke, and Kotlin JS/WasmJS Node tests. See build/cache-reuse-target-regressions.log.
An unrelated Renderer.kt change appeared during the pause and is preserved.
The ordinary production consumer (2.19.0) and terminal launcher (2.15.0) also pass again using
the final compiled cache; see build/cache-reuse-independent-consumer-final.log and
build/cache-reuse-terminal-final.log. The interrupted harness and its temporary fixture have
been cleaned up. Its earlier automatic-review usage-limit interruption is resolved.
The prototype has no configuration-cache support. Production JVM routing now supports module
overrides; other platforms retain the root selection. Both older property/selection runners
now read direct file logs after client exit to avoid inherited daemon pipe hangs.
See examples/module-versions/README.md and the plan's proof results for layout and commands.
Rollout remains gated on Native klib/link identity, scoped npm packaging and Android NDK model
checks. Multiple versions within one application remain outside the scope. Existing target
declarations/minSdk 21 are unchanged. No custom Gradle plugin was introduced.

Consumer-selected PARI source integration is implemented. PARI_VERSION_PLAN.md contains the
completed checklist and verification. The consuming root owns parikt.properties (pari.version);
the checkout's demo selection is never a fallback. gradle/parikt.settings.gradle.kts includes
:parikt/:pariktAndroidNative and their private pariktLibs catalog. Pin the PariKt Git revision
separately from PARI. No custom Gradle plugin or binary-only Maven installation is implemented.
Earlier pariVersion/gradle.properties instructions below describe the previous setup.

External consumers own module outputs under build/parikt/modules, engine caches under
build/parikt/native, and web staging under build/parikt/web-engine. Repository development keeps
parikt/.native caches. Web packaging/invalidation now belongs to library integration rather than
the demo. Root and standalone npm lockfiles use the new portable relative engine path; obsolete
entries are removed. All shell builders require --pari-version; bash checkouts use LF endings.

The native builders own cache validation with source/toolchain stamps; their Exec tasks use
Gradle doNotTrackState. Ordinary Kotlin/cinterop/webpack tasks still track actual engine inputs.
A cache hit is reported by the script, not necessarily a Gradle UP-TO-DATE label. JVM stamps now
hash bridge/header/JNI sources and compiler identity; declared task inputs also include JNI
headers and explicit native PATH. JVM master/latest refresh their source revision and can reuse
a compiled engine when that resolved revision and the other inputs are unchanged.
Shared staging directories require serialization: build-lock.sh protects all four builders using
portable exclusive file claims plus legacy directory guards, without an extra host package. Competing-process/subshell cleanup tests
and actual concurrent builds pass. A hard kill can leave a stale lock; the timeout identifies it
for removal after checking no builder is running.

Independent consumer checks pass for 2.15.0 and 2.19.0: JVM Windows, Native Windows/Linux (WSL),
JS Node and WasmJS Node assert getVersionName(), arithmetic and factorization. Android bridge AARs
assemble for both versions and both ABIs. Switching 2.19 -> 2.15 -> 2.19 reuses caches while the
repository selection stays 2.15. A temporary JNI comment forces a rebuild at unchanged PARI
2.19; restoring the exact source restores its fingerprint and the next run hits the cache.
The JNI source and fixture properties are restored.

Final combined regression passes (172 tasks, 22 executed/150 up to date): Android assembleDebug
(minSdk 21), shared Compose state/desktop UI tests, Native Windows/Linux tests and both production
browser distributions. The ordinary JVM terminal launcher also passes. Checks live in
tests/build_configuration; logs are in ignored build/. No emulator run was repeated for this
change. Apple targets, fresh-machine validation and binary-only Maven publication remain outside
the verified scope.

Follow-up: the configuration-error check is now tests/build_configuration/verify_properties.ps1,
replacing the Python script at the user's request. It retains all five cases and cleans up
temporary fixtures after validating their absolute paths. All five cases pass under PowerShell 7
and Windows PowerShell 5.1; documentation points to the new script.
Equivalence review found Windows PowerShell's native stderr formatting/log encoding differed
from Python. The script now captures merged plain UTF-8 process output, runs from the repository
root, and checks the child process exit code directly. All five real Gradle cases pass again on
PowerShell 7 and Windows PowerShell 5.1. Eight injected-result checks also pass (four per shell):
expected failure, unexpected success, wrong diagnostic and premature native setup. These verify
fixture contents/options, failure exit status, UTF-8 logs without PowerShell error-record wrappers,
and cleanup on success/failure, from a different working directory and paths with spaces and &.
The temporary audit harness is in ignored build/audit-properties-equivalence.py. Host verification
for this script is Windows; PowerShell-on-Linux/macOS execution has not been verified.

Commit review also includes the WebEngine.kt opt-in annotation qualification cleanup. Both
:parikt:compileKotlinJs and :parikt:compileKotlinWasmJs pass with that cleanup. The examples/consumer
directory is intentionally a separate Gradle build; open it as a separate Android Studio project
or run the root wrapper with -p examples/consumer, rather than expecting a root-project module.

PariKt is a KMP library in `:parikt`. Android and JVM are implemented in the documented host
matrix. Kotlin/JS and Kotlin/WasmJS now have working Node and Chromium-browser consumer bindings
around an Emscripten PARI engine. Kotlin/Native linuxX64 uses a statically linked C interop
backend; mingwX64 reuses the same native backend and terminal demo on Windows. Other
Kotlin/Native targets are not configured.

Common API and expect declaration: `src/commonMain/kotlin/com/elianfabian/parikt/`. Android JNI
Kotlin: `src/androidMain/kotlin/`. The NDK makefiles remain under `src/androidMain/jni/`; shared
wrapper and native dependency build script are under `native/paridroid/` and
`native/build-native.sh`.

PariKt now uses `com.android.kotlin.multiplatform.library` with the KMP `android {}` target DSL.
Because that plugin does not support `externalNativeBuild`, a separate `:pariktAndroidNative` module
applies `com.android.library` and owns the existing `ndk-build` project and native dependency
bootstrap. PariKt consumes that bridge module as an `androidMain` dependency; this preserves NDK
packaging without relying on AGP's legacy KMP Android integration. The Android app relies on AGP
built-in Kotlin. The selected AGP version is 9.4.1 with Gradle 9.6.0; Kotlin is set to 2.4.21.
`:androidApp:assembleDebug` succeeds with this migration, and the APK contains `libgmp.so`,
`libpari.so`, and `libparidroid.so` for arm64-v8a and x86_64. PariKt has no bundled-prebuilt
fallback.

The `:androidApp` module is a Kotlin/Jetpack Compose GP console for emulator checks. It uses
Material 3, Compose BOM 2025.06.01, and Activity Compose 1.10.1 so both the demo app and PariKt can
target minSdk 21. Its application ID differs from the PariKt namespace, exercising AndroidX
Startup's merged provider and app-specific cache directory. On launch it runs a visible `2 + 2`
self-check and displays a decoded PARI version with ready/error status. Users can enter commands,
run example chips, review scrollable output history, tap a previous command to edit/rerun, and clear
results. The API-21 AVD on this machine is 32-bit x86, outside PariKt's native ABI list; runtime
verification uses an x86_64 API 36 emulator.

The first `:androidApp:assembleDebug` log exposed SDK-level requirements, a missing `paridroid.h`
include path, and Java/Kotlin JVM targets 11 vs 21. The app uses compileSdk 36 and JVM 17 for both
compilers. AndroidX raised the default minSdk for new releases from 21 to 23; to retain the user's
minSdk 21 preference, the demo pins Compose BOM 2025.06.01 and Activity Compose 1.10.1. With these
versions the app and library both declare minSdk 21, and `:androidApp:assembleDebug` succeeds.
`Android.mk` adds the wrapper header directory. Earlier Kotlin-only root plugin centralization
failed; the current root shares both AGP and Kotlin plugins and resolves the multiple-load warning
(see below). The first emulator launch revealed the
initializer metadata was absent from the merged manifest because PariKt's manifest used the legacy
`src/main` path. It has been moved to `src/androidMain/AndroidManifest.xml`. On an x86_64 API 36
emulator using the API-21-compatible dependency versions, Startup initialization succeeded, `2 + 2`
returned 4, `factor(123456)` returned the expected factors, version code 135425 was displayed, and
the close button reported successful shutdown. The installed API-21 AVD is x86, which PariKt does
not currently package; direct API-21 runtime testing therefore remains open. Reinitialization after
close has not been tested.

The initial AGP 9.4.1 sync log showed deprecated opt-out properties and legacy variant API calls.
The opt-outs were removed; the dependency flag was replaced with the recommended
`android.dependency.useConstraints=false`. The app's Kotlin Android plugin was removed in favor of
built-in Kotlin. The KMP target was migrated to the Android-KMP library plugin, while
`externalNativeBuild` and PARI/GMP bootstrap moved to `:pariktAndroidNative`, a standalone Android
library dependency. Version pins are AGP 9.4.1, Gradle 9.6.0, and Kotlin 2.4.21. The Android-KMP
plugin avoids build variants, so its local dependency selection explicitly prefers the helper
library's debug variant and falls back to release. `:androidApp:assembleDebug` passes and its output
contains all three PariKt native libraries for both configured ABIs. The pasted AGP opt-out and
legacy-variant warnings no longer appeared in the build output. The official KMP compatibility table
currently lists Kotlin 2.4.20–2.4.21 through AGP 9.3.1, so AGP 9.4.1 is newer than that listed range
even though this project's sync, assemble, and emulator smoke run pass. Recheck the matrix when
Kotlin publishes an update. The multiple-Kotlin-plugin-load warning is now resolved: root
`build.gradle.kts` declares all three AGP plugins and all three Kotlin plugins with `apply false`;
the four modules apply versionless plugin IDs. Both plugin families must be available from the
shared parent classloader. Earlier attempts shared only Kotlin, leaving AGP's types in child
classloaders; the resulting `BaseVariant`/`BaseExtension` lookup errors were incorrectly treated
as evidence of an AGP API incompatibility. Sharing both plugin families passes `help --warning-mode all`,
`:androidApp:assembleDebug`, and `:terminalApp:compileKotlin` with no multiple-load warning.
AGP 9.4.1, Kotlin 2.4.21, built-in Android Kotlin, and the Android-KMP target are preserved.
The migrated app was
installed and launched on the API 36.1 x86_64 emulator; AndroidX Startup loaded GMP/PARI/JNI, and
the UI displayed PARI 2.17.1 plus the successful `2 + 2 returned 4` check. The emulator was shut
down afterward.

Kotlin standard-library artifacts are supplied by the Kotlin Multiplatform plugin for each target.
Do not add a separate Wasm stdlib dependency; the explicit
`org.jetbrains.kotlin:kotlin-stdlib-wasm:2.2.10` coordinate failed resolution during sync. The
explicit stdlib dependencies and unused catalog aliases have been removed. The coroutines dependency
remains on the JVM and Wasm source sets.

PariKt's Android `PariNative` has a single object initializer that loads `gmp`, `pari`, and
`paridroid`; its `loadLibraries()` expect/actual method is a no-op after object initialization. The
loader rethrows `UnsatisfiedLinkError` with the library name and ABI/dependency guidance. Runtime
library loading and GP evaluation succeeded on the x86_64 emulator. Do not carry over `paridroidgp`
's duplicate loader.

The JNI C bridge now explicitly includes `<jni.h>`. The reference gets that include through its
generated `fr_ubordeaux_math_paridroid_PariNative.h`; PariKt does not have an equivalent generated
header. Native compilation succeeded for `arm64-v8a` and `x86_64`.

Android callers are initialized automatically by `PariKtInitializer`, registered in
`src/androidMain/AndroidManifest.xml` through AndroidX Startup Runtime 1.2.0. It receives the host
app's application `Context`, passes the app cache path through JNI, and sets `GPTMPDIR` before
`pari_init`. The initializer runs before the host app's `Application.onCreate()`. This is the only
Android initialization path; it uses a 4,000,000-byte stack. The merged provider and GP evaluation
were verified in the demo host app.

`setTemporaryDirectory` is currently an expect/actual method: Android delegates to the C setter, JVM
creates a writable host temp directory and sets the native `GPTMPDIR`. JS/WasmJS use Emscripten's
in-memory `/tmp`, configured by the web C bridge before `pari_init`; the web setter accepts only
that virtual path. Kotlin/Native now has a shared nativeMain actual that uses the C bridge's
OS-default temporary directory (TMPDIR on Linux; TEMP/TMP on Windows).
The C setter uses `setenv` on POSIX/Emscripten and `_putenv_s` on Windows.

The native build's `--datadir` is separate from `GPTMPDIR`: PARI uses `datadir` for optional
architecture-independent data packages including `elldata`, `galdata`, `galpol`, and `seadata`
([PARI GP defaults](https://pari.math.u-bordeaux.fr/dochtml/ref/GP_defaults.html)). PariKt's native
build currently installs only headers and `libpari`, not those data packages. Basic arithmetic does
not depend on them, but calls that use those optional datasets may. The configured path inherited
from Paridroid should not be mistaken for an app cache path or proof that those files are shipped.

## Completed groundwork

- [x] Read the `paridroidgp` Gradle build, native build script, NDK makefiles, JNI bridge, Kotlin
  wrapper, and paired app package configuration.
- [x] Compare those files with PariKt's Android/KMP Gradle, JNI, native wrapper, and version
  configuration.
- [x] Record verified reference behavior and open implementation questions in this handoff.

## Continuation checklist

### Phase 1 — Compare and align Android build behavior

- [x] Add a configuration-time native dependency bootstrap before Android Studio's NDK model
  generation, using the selected version/ABIs and configured `nativeRunner`.
- [x] User confirmed Gradle sync works after removing the unresolved Wasm stdlib dependency.
- [x] Remove the unresolved explicit Wasm stdlib dependency; Kotlin Multiplatform adds the correct
  target stdlib automatically.
- [x] Validate Gradle configuration, Android Kotlin compilation, and NDK configure/build for the app
  on `arm64-v8a` and `x86_64`.
- [ ] Check task ordering against `paridroidgp`: GMP/PARI artifacts must exist before `ndk-build`
  consumes them. Confirm that tasks skip valid builds and respond correctly to `pariVersion`, ABI
  changes, and `nativeForce`.
- [ ] Decide whether PariKt needs `paridroidgp`'s bundled-prebuilt fallback. If added, use prebuilts
  only when their PARI version and every selected ABI match; otherwise build into
  `.native/android-$pariVersion`.
- [x] Name generated Android ABI artifacts `.native/android-$pariVersion`, parallel to JVM's
  `.native/jvm-$pariVersion`. Migrate complete legacy caches by copying them to the new name and
  retain the old directories. Verified the existing 2.16.0 cache migrated for `arm64-v8a` and
  `x86_64`, and `:androidApp:assembleDebug` completed with Android native dependencies up to date.
- [ ] Verify migration behavior when the legacy cache is incomplete for selected ABIs; such a cache
  should not be copied as valid and Gradle should rebuild into the new directory.
- [x] Remove Docker from PariKt's `gradle.properties` runner comment; PariKt currently supports only
  `local` and `wsl`.
- [x] Compare JNI bridge and `Android.mk` source/include paths with `paridroidgp`; add PariKt's
  missing explicit `jni.h` include.
- [x] Confirm the AAR contains `libgmp.so`, `libpari.so`, and `libparidroid.so` for both `arm64-v8a`
  and `x86_64`.
- [x] Improve PariKt's single loader to report missing/incompatible `.so` files with the library
  name and ABI/dependency guidance; emulator loading succeeded on x86_64.
- [x] Migrate away from AGP 9 deprecated variant calls: KMP Android target uses the Android-KMP
  plugin, NDK/JNI integration lives in `:pariktAndroidNative`, and the app uses built-in Kotlin.
- [x] Sync and build the AGP 9.4.1 migration; confirm the pasted deprecated-property and
  legacy-variant warnings are gone. Confirm the helper AAR's native libraries are transitively
  packaged for arm64-v8a and x86_64.
- [x] Run the migrated app on an API 36.1 x86_64 emulator; Startup initialized the native libraries
  and the UI confirmed `2 + 2 = 4`.

### Phase 2 — Resolve Android paths for a reusable library

- [x] Confirm `--datadir` is PARI's location for optional data packages, separate from `GPTMPDIR`.
- [ ] Decide which optional PARI data packages PariKt will ship/support. If any are included,
  package them and configure a valid lookup path; test data-dependent GP commands.
- [x] Add an AndroidX Startup initializer that passes the host app's cache directory through JNI
  before `pari_init`, without putting Android `Context` in `commonMain`.
- [x] Make AndroidX Startup the only Android initialization path; remove the context-taking public
  facade.
- [x] Verify Android initialization from the demo app with a different application ID; GP evaluation
  succeeds after Startup initializes with its cache directory.
- [x] Test the library from a host app whose application ID differs from `com.elianfabian.parikt`
  (`com.elianfabian.parikt.demo`).

### Phase 3 — Runtime behavior and API checks

- [x] Run the emulator smoke app on x86_64 API 36: Startup, `2 + 2`, `factor(123456)`, version code,
  and close all succeeded.
- [x] Add a minimal `:androidApp` Jetpack Compose host app for emulator smoke checks.
- [x] Replace the one-shot demo with a visible-ready-state GP console, example commands, editable
  command input, and tap-to-reuse result history; rebuilt and verified `factor(123456)` through its
  UI on the x86_64 emulator.
- [x] Fix the Compose SDK requirements, wrapper header include path, and app JVM target mismatch;
  `:androidApp:assembleDebug` succeeded afterward.
- [x] Move AndroidX Startup registration to `src/androidMain/AndroidManifest.xml` after the merged
  manifest omitted it; verified the initializer is now registered and runs.
- [x] Restore the demo app's minSdk 21 with Compose BOM 2025.06.01 and Activity Compose 1.10.1;
  `:androidApp:assembleDebug` passes with minSdk 21.
- [ ] Check behavior when reinitializing after `PariKt.close()`.
- [ ] Check native load failures, empty GP command handling, and concurrent calls. Change behavior
  based on reproducible results or a clear API requirement.
- [ ] Test another `pariVersion` through Gradle. Keep GMP pinned at 6.3.0 unless build evidence
  requires an update.
- [x] Replace the Wasm temp-directory stub with the web C bridge's explicit in-memory `/tmp`
  configuration before PARI initialization; verified by successful Node engine initialization.

### Phase 4 — Clone readiness and docs

- [ ] Confirm a clean clone can sync and build with documented prerequisites and no machine-local
  files.
- [x] Verify `.gitignore` and Git-tracked app/manifest files; document Android build/install
  commands and provide the Compose consumer app.
- [x] Mark Android target build, AAR contents, and initial runtime smoke checks complete;
  reinitialization and broader API checks remain open above.

### Phase 5 — JVM target and terminal smoke app

Current status: the KMP `jvm("jvm")` target and `jvmMain` actual exist. JVM bindings load libraries
in dependency order with `System.loadLibrary`, report load failures, and set `GPTMPDIR` to a created
`java.io.tmpdir/parikt` directory before PARI initialization. The matching native library directory
must be provided through `java.library.path`; `:terminalApp:run` sets this from its selected host
artifact directory. JNI init/set-temp calls match the bridge's `nativeInit` and
`nativeSetTemporaryDirectory` exports. A basic `:terminalApp` consumer is available.
`native/build-jvm.sh` and `:parikt:buildJvmNativeDependencies` define separate Linux x86_64 and
Windows x86_64 build paths; Windows is cross-compiled from WSL with MinGW-w64. The JNI C bridge is
shared by Android/JVM builds under `native/paridroid/`. Both host native builds and terminal smoke
runs have succeeded for PARI 2.17.1. Android's NDK-specific build remains separate.

Environment checked during JVM bring-up: Windows x86_64 with JetBrains Runtime/OpenJDK 21.0.7 and
Windows JNI headers. WSL is Ubuntu x86_64. The Windows Gradle wrapper was corrected to return
Gradle's exit status. `:terminalApp:compileKotlin` and `:androidApp:assembleDebug` completed
successfully; the latter compiled the relocated shared JNI bridge for Android. For JVM verification,
temporary toolchains were staged under `/tmp` rather than installed system-wide. The Windows
cross-build and Linux build both produced PARI 2.17.1 native libraries, and the terminal app ran
`2 + 2` and `factor(123456)` successfully on Windows and WSL/Linux. This does not verify a clean
machine's package installation flow, another PARI version, or missing-library diagnostics. README
lists the expected WSL packages.

Implementation plan, in dependency order:

1. **Support the two current development environments.** The first JVM matrix is Windows x86_64 and
   Linux x86_64 under WSL. Each host gets its own GMP/PARI/JNI artifacts; Linux `.so` files, Windows
   `.dll` files, and Android `.so` files cannot be substituted. macOS is a later matrix expansion.
   PARI recommends WSL for Windows users. Its developer guide documents MinGW cross-compilation from
   Linux, which is the basis for PariKt's Windows path.
2. **Build and cache host libraries.** `native/build-jvm.sh` selects PARI source from `pariVersion`,
   keeps GMP pinned internally, builds Linux shared libraries or cross-compiles Windows libraries
   with MinGW-w64, and caches source/staging under the WSL user's cache. Final artifacts are keyed
   in ignored `.native/jvm-<version>/<host>` directories. Android's NDK-specific
   `native/build-native.sh` remains separate. Validate MinGW shared-library output and runtime
   dependencies before treating Windows support as complete.
3. **Define and implement the desktop JNI packaging/loading contract.** The JNI bridge retains
   package `com.elianfabian.parikt.PariNative`; it moved to `native/paridroid/jni_bridge.c` for
   Android/JVM reuse. `:terminalApp:run` uses the ignored version/host directory (overridable with
   `jvmNativeDir`), and JVM actual loads host libraries in dependency order with actionable errors.
   Linux build embeds `$ORIGIN` rpath for bundled shared dependencies. Verify Windows DLL dependency
   resolution and both runtimes before treating packaging as complete.
4. **Make JVM initialization real.** The JVM actual creates `java.io.tmpdir/parikt` and sets
   `GPTMPDIR` before `pari_init`; desktop callers initialize through `PariKt.init()`. Verify runtime
   behavior and document optional data-package limits. Preserve AndroidX Startup-only Android
   initialization.
5. **Add a basic terminal consumer module.** Create a small `:terminalApp` JVM application depending
   on `:parikt`'s JVM variant. It should initialize PARI, print version and a startup `2 + 2` check,
   accept one GP command per line, print the returned output, and exit cleanly on `quit`/EOF while
   closing the engine. Keep input/output simple and independent of Android/Compose. Errors from
   native loading or initialization should be visible and actionable.
6. **Verify in increasing scope.** First compile the JVM variant, then run the terminal app on the
   selected host and verify version, `2 + 2`, `factor(123456)`, command listing if supported,
   EOF/quit cleanup, and a clear failure when native files are missing. Confirm switching
   `pariVersion` selects/builds the corresponding host artifacts and that stale outputs are not
   reused. Then add CI/build checks for each claimed OS/architecture before expanding support.
7. **Update docs and handoff as the matrix becomes real.** Document supported JVM hosts, JDK/native
   tool requirements, native artifact generation/loading, terminal run commands, and known
   data-package limitations. Keep each checklist item unchecked until the corresponding host
   execution has been observed.

Checklist:

- [x] Record current host and selected initial matrix: Windows x86_64 and Linux x86_64 under WSL;
  JDK 21.0.7 is present on Windows. (Native C toolchains are not yet available/verified.)
- [x] Define separate Windows and WSL native build workflows and cache layout keyed by PARI version
  and host; PARI uses `pariVersion`, GMP stays pinned internally. (Scripts/tasks added; native build
  execution is still unverified.)
- [x] Build host GMP/PARI and JNI bridge; validate ABI and dynamic-library dependencies. (Linux
  shared libraries and Windows DLL dependency imports were inspected; both builds completed.)
- [x] Implement deterministic JVM loading and useful failure reporting; do not swallow native load
  errors. (Both host runtime loads succeeded; missing-library path remains unverified.)
- [x] Set a writable JVM `GPTMPDIR` before `pari_init`. (The terminal runs initialized PARI on both
  hosts.)
- [x] Add `:terminalApp` JVM CLI with startup self-check, interactive command loop, and clean close.
  (Compile and smoke runs succeeded on both hosts.)
- [x] Compile `:terminalApp:compileKotlin` on Windows/JDK 21 and rebuild `:androidApp:assembleDebug`
  after moving the JNI bridge; both tasks succeeded.
- [x] Build native artifacts and run terminal smoke commands (`2 + 2`, `factor(123456)`, `quit`) on
  Windows and WSL/Linux for PARI 2.17.1.
- [x] Use `System.loadLibrary` for all JVM native loading; terminal consumer supplies the library
  directory through `java.library.path`.
- [x] Re-run the Windows terminal smoke with `System.loadLibrary` and the configured
  `java.library.path`; version, `2 + 2`, `factor(123456)`, and clean close succeeded using the
  existing native artifacts.
- [x] Attach native preparation to every terminal `JavaExec` task, including IDE-created `MainKt`
  launch tasks, so changing `pariVersion` triggers the matching host build before launch. This uses
  Gradle task dependencies; Android's separate configuration-time build exists because Android
  Studio needs the NDK model during sync.
- [ ] Verify an IDE-created JavaExec task builds and launches against a newly selected `pariVersion`.
- [x] Resolve the Kotlin plugin multiple-load warning by sharing AGP and KGP together in the root
  `apply false` declarations and applying versionless IDs in modules. `help --warning-mode all`,
  Android assemble, and JVM terminal compilation pass without the warning.
- [x] Make the Windows JVM native build bootstrap MinGW-w64 like Android bootstraps its NDK:
  use an available system compiler or download a pinned portable xPack compiler with a SHA-256
  check into the WSL native cache.
- [ ] Verify Windows JVM native build and launch with PARI 2.19.0. The portable
  MinGW bootstrap works, and a downstream workaround handles PARI's generated MinGW export-file
  failure. PARI DLL names vary by release/configuration: the bridge's import table determines the
  filename copied beside it, and the JVM discovers the `libpari*.dll`/`pari.dll` basename present.
  A 2.19.0 build exposed `libpari-gmp.dll`; the user confirmed the Windows 2.18.0 terminal now
  launches successfully with its versioned `libpari-2.18.dll`. WSL invocation in the current tool
  environment returns `Wsl/E_ACCESSDENIED`, so verify 2.19.0 on the user's machine.
- [x] Compile JVM source after making the PARI DLL basename version-aware; `:parikt:compileKotlinJvm`
  succeeds. The runtime discovers versioned names such as `libpari-2.18.dll`, as well as
  `libpari-gmp.dll`, `libpari.dll`, and `pari.dll`.
- [x] Smoke-test the terminal against the generated `libpari-2.18.dll` on Windows; the user reports
  the 2.18.0 run now works.
- [x] Use the common `PariKt.getVersionName()` API for the Android demo's visible PARI version and
  the terminal startup version line. `:androidApp:assembleDebug` passed with the cached 2.16.0
  artifacts, and `:terminalApp:compileKotlin` passed.
- [ ] Observe the terminal's `getVersionName()` line in a live Windows terminal run; a piped `quit`
  did not close the Gradle JavaExec process in the current environment.
- [x] Fix the import-table parser to consume all `objdump` output before returning the PARI DLL
  name. An early `awk exit` under `set -o pipefail` could close the pipe and fail with exit 141.
- [x] Opt in to Kotlin's expect/actual classes feature with `-Xexpect-actual-classes`; Android/JVM
  compile tasks pass and the Beta warning is gone.
- [ ] Test missing-library diagnostics and build/runtime selection with a second `pariVersion`.
- [ ] Add and verify macOS and any additional OS/architecture hosts before documenting them as
  supported.
- [x] Resume JS/WasmJS after JVM is usable; implementation and checks are recorded in Phase 6.
- [ ] Plan iOS/Kotlin Native support separately after the current web work.

## Phase 6 — JavaScript and WasmJS execution

Current priority: the user deferred macOS verification and selected JS/WasmJS as the next task.
Keep macOS Android sync/tool discovery and macOS JVM support open; they are not prerequisites
for developing the web engine on Windows/WSL.

Starting point before this implementation: only a Wasm target and nonfunctional interop stub
existed. There was no generated engine, real module import, or Kotlin/JS target. The stub loaded
twice, blocked for readiness, used unsupported external array types, and accepted temp paths
without configuring anything. It has now been replaced. `paridroid.c` remains shared C logic;
`jni_bridge.c` is Android/JVM-only.

Implementation and verification:

Implementation: `native/build-web.sh` now prepares a pinned Emscripten 4.0.21/
Node 22.16.0 toolchain with SHA-256 archive checks on Linux/WSL x86_64, builds static GMP 6.3.0
and the selected PARI source, and links a dedicated web ABI around `paridroid.c`. An explicit
activated SDK can be passed with `--emsdk`; automatic toolchain preparation on other hosts is
not yet implemented. Host prerequisites include Python 3.11+, make, m4, cc, git, curl, tar, and xz.
`--pari-source` is a development source override; normal builds select the source tag using
`pariVersion`. `native/web/parikt.mjs` adapts strings and command arrays and provides an async
factory plus synchronous operations after loading. `native/web/smoke.mjs` exercises the engine
directly. Gradle tasks `:parikt:buildWebNativeDependencies` and `:parikt:verifyWebNative` use
`.native/web-<pariVersion>` outputs and default to WSL on Windows. `prepareWebEnginePackage`
stages the selected payload into `parikt/build/generated/web-engine/`, a stable local npm URL.
The old Wasm stub was replaced by `webMain` actual bindings and a Kotlin/JS target was added.
Node and headless Chromium consumer tests pass for both Kotlin targets. A Gradle override to
PARI 2.19.0 passed the direct smoke, all four consumer tasks, both production distributions, and
UI checks showing 2.19.0. Switching back to the unchanged gradle.properties selection, 2.15.0,
also passed. No npm lock update was needed for these switches. PowerShell requires quoting the
dotted property argument: `'-PpariVersion=2.19.0'`.

`:webApp` provides a shared GP console for both compilers with version/ready status, a startup
`2 + 2` check, command input, examples, and clearable output. Its production bundles were served
over HTTP and exercised in isolated headless Edge: startup, factor(123456), clear, Pi example,
and Pi evaluation passed for JS and WasmJS. Browser tests produced one passing test per target,
not empty test tasks. Generated web engine/npm/Kotlin output directories were moved aside and
successfully recreated using persistent source/toolchain caches; a truly clean machine and
macOS host setup remain unverified. Gradle-managed Node/Binaryen archives have settings-level
Ivy repositories to respect PREFER_SETTINGS. npm is used instead of Yarn; lockfiles contain
relative stable engine paths. npm overrides address duplicate webpack-instance resolution.

Remaining build messages include configuration-time npm-resolution performance warnings and
webpack's asset-size warning for the roughly 6.6–7.1 MiB PARI engine. These are distinct from the
resolved multiple-KGP-instance warning. Their causes/optimization are not claimed fixed.

Following user API feedback, the only public initializer is now `suspend fun PariKt.init()`;
the synchronous `initialize()` helper stays private, and the experimental `initAsync()` name was
removed. This deliberately changes JVM callers to a coroutine/suspending context; terminalApp's
entry point is now `suspend fun main()`. Android Startup retains its internal synchronous
`initWithTemporaryDirectory()` entry point. The web factory import is private with an ordinary internal
delegate. Cancelled loading callers preserve the shared pending load; consumer tests cancel one
initializer and then initialize successfully. Promise awaiting lives in small JS/Wasm-specific actual functions, because coroutines
1.9.0's `await` is not exposed consistently to shared-source IDE analysis. The web stack argument
is restricted to positive signed 32-bit values to match the C bridge, rather than blindly narrowing
a Kotlin Long. The arbitrary initial 4096-byte lower bound was removed. This is a representation
check, not a promise that every positive size initializes successfully. Small-stack probes trapped:
PARI's minimum backing allocation does not enlarge its requested active stack, and normalizing
to MIN_STACK (500032) still failed PARI 2.19 initialization. Both experiments were removed. The
default 4,000,000-byte request passes both tested versions; tiny custom requests may terminate the
engine during initialization and very large requests may exhaust Wasm memory. Safer native
initialization failure recovery remains future work; do not claim all representable sizes work.

The user also asked about a minimum supported Kotlin version. Proposed initial consumer baseline
is the current 2.4.21 build toolchain, not a verified technical minimum. Before advertising older
consumer versions, test actual consumers per target; do not infer JS/Wasm compatibility from JVM
metadata compatibility alone.

- [x] Prove a minimal PARI web build first, before wiring Kotlin targets. Inspect PARI Configure
  cross-compilation probes and select a portable wasm32 kernel; decide whether the first build
  uses static GMP or PARI's portable arithmetic kernel based on actual build results.
- [x] Add a dedicated Emscripten build script keyed by `pariVersion`, toolchain version, build
  options, and bridge/script inputs. Keep web outputs separate from Android/JVM caches. Bootstrap
  a pinned, checksum-verified toolchain and document unavoidable host prerequisites.
- [x] Add a web C bridge around the existing wrapper, exporting init/eval/commands/version/close
  with explicit UTF-8 string and memory ownership rules. Handle the wrapper's setjmp/longjmp,
  filesystem/temp directory, and unsupported OS facilities deliberately. Do not compile JNI.
- [x] Generate an Emscripten ES module plus its Wasm asset, with asynchronous module creation.
  Run it directly in Node.js: version, `2 + 2`, `factor(123456)`, persistent variables, invalid
  input recovery, command listing, and close. Confirm this works before adding Kotlin bindings.
- [x] Make `PariKt.init()` the single public suspending initializer, with a private synchronous
  helper and Android Startup's internal synchronous path. Update terminal main and consumers.
- [ ] Test loading-failure retry using an unavailable Wasm asset; the pending-load reset path
  exists but this failure case has not been exercised. Test broader reinitialization/concurrency
  and GP `quit` lifecycle behavior before promising those semantics.
- [x] Add the Kotlin/JS target and replace the Wasm stub with real imports. Reuse the JavaScript
  adapter; use interop types supported by each Kotlin target and convert arrays/strings at the
  boundary. Validate stack-size conversion rather than passing Kotlin Long blindly across JS.
- [x] Wire Gradle task dependencies and generated JS/Wasm assets for Node and browser consumers.
  PariKt is a library; put executable demos in consumer modules. Verify asset paths and loading
  in production output as well as development builds.
- [x] Add a minimal web GP console and exercise both Kotlin/JS and Kotlin/WasmJS consumers.
  Start with a single engine and sequential calls. Record that synchronous calculations block
  their executing thread; evaluate a Worker API separately for a responsive browser UI.
- [x] Verify switching `pariVersion` between 2.15.0 and 2.19.0 selects the correct engine in both
  production browser consoles and consumer tests; stable npm paths avoid lock changes.
- [x] Document actual commands, packaging, synchronous evaluation, and data/filesystem limits.
- [ ] Verify a truly clean checkout/machine without source/toolchain caches. Generated-output
  regeneration has passed; host prerequisite installation, other browsers, and macOS remain open.
- [ ] Define a publishable Maven/npm asset strategy; current file dependencies support local
  project consumers, not arbitrary external projects consuming published artifacts.

References: [Kotlin/Wasm JS interop](https://kotlinlang.org/docs/wasm-js-interop.html) documents
the restricted external types; [Emscripten modularized output](https://emscripten.org/docs/compiling/Modularized-Output.html)
documents async module factories and ES module output. The implemented build uses a portable
wasm32/GMP kernel. PARI 2.15's disabled-pipe branch needed a narrowly scoped return-type fix in
the build copy for current Clang; static linking also required using PARI's actual private hash
table symbols rather than defining the JNI wrapper's replacement symbols. Other PARI versions
may need further compatibility work; only the versions recorded above have been executed.

## Phase 7 — Shared Compose console

The user requested a similar Compose UI on all currently tested targets, including desktop,
using the existing WasmJS console as the visual reference. Share the dark palette, status/version
line, examples, command field, output, and behavior. Retain Android minSdk 21 and defer macOS
verification. Binary function filtering was discussed only; the user explicitly declined
implementation of that separate idea.

- [x] Add a `:consoleUi` KMP module for shared Compose screen/state, consumed by Android,
  desktop JVM, Kotlin/JS, and Kotlin/WasmJS launchers.
- [x] Select Compose dependencies compatible with API 21 and Kotlin 2.4.21; test actual builds.
  Selected Compose Multiplatform 1.8.2, matching AndroidX UI 1.8.2 and Material3 1.3.2.
  Published AAR manifests confirm UI 1.8.2 minSdk 21 versus UI 1.12.1 minSdk 23; do not rely on
  generic platform compatibility tables alone.
  The 1.8.2 Gradle plugin fails against AGP 9.4.1 with missing `onVariant(Function1)`.
  The shared UI uses the Kotlin Compose compiler plugin with explicit UI dependencies;
  it has no Compose resource-generation/packaging requirement. The Compose Multiplatform
  plugin remains limited to the browser launcher, avoiding its old Android integration.
- [x] Reuse console initialization, version/self-check, command editing/examples/history/clear
  behavior and the Wasm console's visual design. Preserve native engine thread affinity; moving
  evaluation to arbitrary background dispatchers is outside this UI change.
- [x] Replace Android's private screen and the HTML web implementation with thin launchers.
- [x] Add `:desktopApp` with the shared screen and native build/library-path preparation.
  Retain `:terminalApp` as a separate CLI.
- [x] Build Android at minSdk 21, desktop JVM, and both browser compiler targets. Exercise
  real startup and calculations, and verify shared state behavior.
- [x] Document launcher commands and remaining desktop packaging/host limitations.

Initial verification: shared JVM/JS/Wasm compilation, desktop launcher compilation, Android
APK assembly (minSdk 21), and both production browser distributions pass. Two shared-state JVM
tests pass (startup gate/version/self-check, command trimming/history/reuse/clear, retry).
Production JS/Wasm screenshots show PARI 2.15.0 Ready and real `2+2 = 4` using the shared screen.
The Kotlin/JS canvas renderer remains experimental and is explicitly enabled in gradle.properties.
The 1.8.2 browser plugin provides Skiko assets; only JS loads global skiko.js, while Kotlin/Wasm
imports its renderer. Browser bundles retain size/configuration-time npm warnings. Runtime
verification results are recorded below; macOS and a Linux desktop GUI remain unverified.
Android Studio could not resolve `ComposeViewport` in shared webMain although target compilation
passed. The thin viewport calls now live in jsMain/wasmJsMain, where those declarations are
published, behind `launchConsoleUi()`; only the screen and state are shared in consoleUi.
A desktop Compose integration test exercises the real native engine and UI semantics without
opening a window; it uses the same native preparation as the desktop launcher. It passes startup,
factorization, clear, and typed Pi. Compose 1.8.2's headless mouse injector called evaluation on
the test-worker thread after initializing on AWT, producing PARI's `expression nested too deeply`.
Native env/stack diagnostics confirmed distinct threads; temporary diagnostics were removed.
The test now invokes semantic button actions on AWT, matching actual desktop input. Native
thread affinity remains required; this does not add cross-thread library support.
Android API 36 x86_64 emulator runtime checks pass with PARI 2.15.0: startup/version, factor,
clear, and Pi. Production Edge JS/Wasm screenshots also verify factor, clear, Pi and a 390px
layout. An initial emulator lacked storage; a second configured emulator accepted the APK.
API 21 manifest compatibility is verified by assembly, not an API 21 device run (available AVD
is x86, outside supported ABIs). Desktop launcher smoke runs finish successfully for 2.15.0
and 2.19.0; the automated UI calculation test has so far run on Windows with 2.15.0.
Final combined verification passed: `:androidApp:assembleDebug`, `:consoleUi:jvmTest`,
`:desktopApp:test`, `:webApp:jsBrowserDistribution`, and `:webApp:wasmJsBrowserDistribution`.
No function-removal feature was implemented. The core library remains independent of Compose.

## Phase 8 — Kotlin/Native linuxX64

The user approved starting with linuxX64 and a native terminal executable, then considering
mingwX64. Keep the public common API and pariVersion selection; no JVM/JNI in the new backend.
Apple targets remain deferred. Current Kotlin documentation marks macosX64 deprecated since
2.3.20; reassess Intel Mac target availability before planning Apple work.

- [x] Build static GMP/PARI and the existing C wrapper for linuxX64 in a separate versioned cache.
- [x] Add a fixed-width C bridge, explicit borrowed-string copying, and OS-thread ownership checks.
- [x] Generate cinterop bindings and implement the linuxX64 PariNative actual backend.
- [x] Add a native terminal demo with version/self-check and interactive evaluation.
- [x] Test real evaluation, variables, errors/recovery, empty input, commands, lifecycle and threading.
- [x] Verify another pariVersion, source/cache invalidation, existing-target regressions and docs.

Builds run from Windows Gradle, compiling C dependencies through WSL and linking linuxX64 with
Kotlin/Native. Native archives use `.native/native-<version>/linux-x86_64`;
the C build stamp includes the bridge/wrapper/header/script content and compiler identity.
The native CLI is a separate `:nativeTerminalApp` module; `:parikt:verifyLinuxX64` runs the native
test executable through WSL when invoked on Windows. Both runners are verified.
Windows KGP accepts linuxX64 compilation but disables its standard host-only run/test tasks;
the explicit WSL runners cover those tasks. GMP builds only its core library subdirectories
and stages its archive/header, avoiding an incidental makeinfo dependency while preserving
Automake's generated-header prerequisites. Its host is generic x86_64 rather than the build CPU.
Static dependencies, cinterop generation and the linuxX64 backend compile. Kotlin/Native rejects
a suspending executable entry point, so the CLI uses a normal main with runBlocking on its
calling thread; the public library initializer remains suspending on all targets.
The bridge/PARI/GMP objects are bundled into one `libparikt.a` for cinterop: separate archives
were linked in an order that left bridge references unresolved. C archives are embedded in
the interop klib, not loaded through JNI; final native linking is verified.
Host GCC/glibc generated an unavailable `__isoc23_strtol` dependency when linking with
Kotlin/Native's older Linux sysroot. KGP's distribution task prepares its managed target
dependencies first; GMP/PARI/the bridge use its matching Linux GCC/sysroot in
WSL. C library stage keys include script/compiler identity to invalidate incompatible archives.
When the managed compiler lives on a Windows drive, the script automatically caches it inside
WSL before use: configure probes against the Windows filesystem were prohibitively slow.
PARI 2.15.0 native linking and tests now pass. The Gradle CLI runner also passes factorization,
persistent variables, division-by-zero recovery, subsequent arithmetic, version and quit.
`file` identifies an x86_64 ELF executable; `ldd` shows system libraries only, with no external
libpari/libgmp or JVM dependency. These checks ran via Windows Gradle + WSL execution.
The expected disabled host-only Linux runner warnings are suppressed; the explicit WSL test
runner is wired into `:parikt:check` on Windows. Tests deliberately use only the public API,
without requiring consumers to opt in to ExperimentalForeignApi.
PARI 2.19.0 also passes the native test suite and terminal factor/version smoke. An isolated,
initially empty Konan cache successfully downloaded the distribution and target dependencies.
This proved KGP already manages those downloads; an earlier custom cinterop probe was removed.
Read KGP's generated konan.txt only at
task execution, after the distribution task, so fresh project metadata can be regenerated.
Consumer/build documentation is in README. A bridge-source comment edit triggered an automatic
bridge/archive rebuild while reusing GMP/PARI stages, confirming source-cache invalidation.
The final combined build passed native tests/CLI linking, Android assembleDebug (minSdk 21),
desktop/shared-state test checks and JS/Wasm launcher compilation. A second native run with
parikt/build/konan.txt temporarily removed successfully regenerated metadata and passed tests;
subsequent archive/interop/link tasks were up to date. The default remains pariVersion=2.15.0.
Unverified: running Gradle itself on a Linux host, additional Linux distributions, a completely
fresh machine including source/host prerequisites, native publication, and other native targets.
Only the Windows Gradle + WSL build/run path has been exercised in this phase.

## Phase 9 — Kotlin/Native Windows

The user requested committing all completed Linux work, then continuing with Windows.
Linux work was committed as bbb5211. Add mingwX64 and reuse the backend, tests and terminal;
retain version selection and Linux behavior. Apple targets remain deferred.

- [x] Build static Windows GMP/PARI with a pinned MinGW compiler and MSVCRT-compatible flags.
- [x] Share the native backend through nativeMain with commonized C interop bindings.
- [x] Add Windows OS-thread ownership and default temporary-directory handling.
- [x] Build/run the Windows native terminal and verify its DLL imports.
- [x] Test PARI 2.15.0/2.19.0, error recovery, lifecycle and thread rejection on Windows.
- [x] Verify Linux and existing-target regressions, document commands and remaining limits.

Windows native compilation, terminal execution and the shared test suite pass for PARI 2.15.0.
The native C builder now handles linux-x86_64 and windows-x86_64 separately. Windows uses the
same pinned xPack GCC 15.2.0-2 as the JVM builder, explicitly selecting MSVCRT via
`-mcrtdll=msvcrt-os` to match Kotlin/Native. PARI cross-probe corrections follow the verified
JVM Windows configuration. The shared native backend gets its temporary directory from C;
Windows uses TEMP/TMP, and ownership checks use GetCurrentThreadId (no pthread DLL required).
The first Windows C compilation succeeded. PARI installs its Windows static archive in bin
rather than lib; the packaging step now normalizes that location before assembling libparikt.a.
Windows Kotlin/cinterop compilation passed, but final linking exposed modern MinGW's stat64i32
wrapper absent from Kotlin/Native's older runtime. The C build now uses Kotlin/Native's exact
Windows sysroot headers (cached in WSL) with the pinned cross compiler, instead of adding a
handwritten stat ABI shim or mixing MinGW runtime versions.
The shared integration test now writes/reads a GP file with spaces in its name and removes it
afterwards, exercising file-status/opening paths in addition to arithmetic and error recovery.
The Windows executable imports only ADVAPI32.dll, KERNEL32.dll and msvcrt.dll; no PARI/GMP,
MinGW runtime DLL, JRE or java.library.path is required to run it. A direct .exe launch also
passes. Linux tests and CLI linking pass after the shared-source move, and
compileNativeMainKotlinMetadata/commonizeCInterop pass, verifying shared binding visibility.
Windows tests, terminal version/factor smoke and import-table checks also pass for PARI 2.19.0.
Both versions import the same three system DLLs. README now documents Windows/Linux native
commands, runtime/build requirements, shared sources and remaining platform limits.
The final default-version checks pass both native test suites and CLI links, Native metadata,
Android assembleDebug, desktop/shared-state tests and JS/Wasm compilation. Both native test
suites, CLI links and shared metadata also pass with PARI 2.19.0 and the final shared sources.
Both native test suites, shared metadata and CLI links passed again after restoring build
outputs to the unchanged pariVersion=2.15.0 default. Remaining limits:
Windows runtime checks used this Windows 11 x86_64 host; a completely fresh machine, other
Windows versions, running Gradle itself on Linux, Apple targets and publication remain unverified.

Native console IDE input follow-up: the user reported `runDebugExecutableMingwX64` exiting at
the first prompt, while `runMingwX64` worked in PowerShell. Task inspection confirmed KGP's
generated debug/release run tasks had null stdin, while custom tasks used Gradle's StdInStream.
All nativeTerminalApp Exec tasks whose names start with run now forward System.in, covering
generated IDE tasks and custom launchers. The exact generated task now passes both piped input
and a delayed-input check: it remains alive at gp>, accepts factor/variable commands after the
prompt, and closes on quit. The IDE UI itself was not automated; reload Gradle changes before
rerunning its configuration. README includes that instruction.

## Android build defaults and environment notes

Fresh-clone NDK lookup: configuration-time native bootstrap previously ran before the helper
module's `android { ndkVersion = "28.2.13676358" }` configuration. It now runs after that block,
so the local runner resolves the selected NDK version before invoking the native build script.
The user reported an NDK-not-installed error on macOS despite installing an NDK; this ordering
bug is a possible cause, but the exact macOS error and installed NDK version have not been
provided. Fresh-clone macOS sync remains unverified; do not claim this change resolves that report
until confirmed. The installed SDK must contain NDK 28.2.13676358.

The development/demo PARI selection is 2.15.0 in parikt.properties (pari.version); all native
scripts require --pari-version and have no engine-version fallback. Other defaults are GMP 6.3.0
(pinned in `native/build-native.sh`), Android API 21,
NDK 28.2.13676358, and ABIs `arm64-v8a,x86_64`. `pari.version` is read from the consuming root
`parikt.properties`; `nativeAbis`, `nativeRunner`, and `nativeForce` are optional properties.

The user's 2026-10-30 Android Studio sync initially failed with `[CXX1429]` because `Android.mk`
required `.native/pari-2.17.1/arm64-v8a/gmp/lib/libgmp.so`, which was absent. The user confirmed
Gradle sync works after the Wasm stdlib fix. The pre-migration app ran on an x86_64 API 36 emulator.
The migrated build now uses AGP 9.4.1, Gradle 9.6.0, Kotlin 2.4.21, and the AGP built-in Kotlin/app
plus Android-KMP library plugin. The demo uses `compileSdk 36`, `minSdk 21`, and `targetSdk 35`; the
native helper uses `minSdk 21` and NDK 28.2.13676358. `:androidApp:assembleDebug` succeeds and
packages PARI/GMP/JNI libraries for arm64-v8a and x86_64. The migrated app also launched on API 36.1
x86_64, initialized through Startup, and displayed the passing `2 + 2` result. The API-21 AVD
available on the machine is x86, outside the native ABI list. The unrelated
multiple-Kotlin-plugin-load warning was subsequently resolved by sharing AGP and KGP at the root.

Configuration-cache compatibility follow-up is implemented. Strict 23-case acceptance passes:
build/configuration-cache-tests/403abc387ae7/logs. Fresh rootless builds reuse on the second run;
root/local additions, edits, removal, strict conflicts, invalid selections and restoration pass.
The real platform task graph (184 tasks) stores/reuses with zero problems, including JVM/Native
identity, both JVM versions, Android debug APK, Native links and JS/Wasm tests/graph diagnostics.

Generated provider/helper scripts now have final stable contents during settings. Optional Android
setup uses public model properties inside those scripts rather than rewriting plugin blocks in
beforeProject. Kotlin-only module-only roots still require no AGP/SDK. Commands and JNI paths are
configured values; graph diagnostics snapshot paths/file collections. A plain script-defined
CommandLineArgumentProvider reads KGP metadata only after its preparation task. PackageJson actions
retain selected strings and the configuration-populated participant set, not live Projects.
Cold Android preparation uses Gradle's tracked exec provider and preserves failure diagnostics.

Actual strict cache store/reuse also passes standalone JVM/Windows Native/JS/Wasm programs and
Linux execution, interactive JVM/Windows Native CLI exit, JVM UI/shared tests and both real Compose
browser distributions. Missing consumer konan.txt is regenerated before both initial and reused
Native commands. A real Android 2.19.0 module override stores/reuses; the temporary property is
removed and default 2.15.0 APK restored. Logs: build/configuration-cache-*.log.
Existing ten-case Native and fourteen-case Android production suites pass at
build/np/fcd993683b91/logs and build/android-tests/763156329ed9/logs. Final fourteen-case web
production regression plus portable locks pass at build/wp/8f7e32404d8c/logs. README documents opt-in usage and the new
PowerShell acceptance command. No custom plugin, target/minSdk change or global cache enablement.


Completion: all 23 strict configuration-cache cases pass, including cached no-default selector failures. Platform storage/reuse covers 184 tasks with zero problems. Android local override/default APK restoration, JVM UI tests, browser distributions, interactive terminal stdin and missing Native metadata recovery all pass. Documentation/checklist are updated; both examples folders, every existing target and minSdk21 are preserved. Configuration caching remains opt-in. The configuration-cache follow-up is complete in this checkpoint, with detailed validation recorded above. Additional host/IDE/runtime coverage and publication remain separate future work.
