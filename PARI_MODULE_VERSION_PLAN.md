# Project defaults and module-specific PARI versions

## Additional environment verification

- [ ] Cold Linux JVM consumer download/build/runtime with empty caches, followed by warm reuse.
  Attempted via verify_cold_jvm.ps1/.sh with installed Gradle/JDK/C tools. Empty-cache plugin
  acquisition, Kotlin compilation and JVM graph checks pass, but GMP acquisition fails with
  WSL curl 56 / TLS EOF. GNU mirror probes also time out in WSL while Windows downloads succeed;
  production downloader is unchanged. Runtime and warm reuse remain unverified. Wrapper-download
  mode was stopped for slow transfer and remains unverified. Logs: build/cold-jvm/23c9ec2861f8.
  Verifier state now survives WSL restarts in ~/.cache/parikt-verification. Offline simulated
  failure/resume/generation checks pass (build/cold-jvm-harness); these are not runtime evidence.

- [x] Open eb94ea7 in Android Studio and trigger Gradle sync through the GUI.
  Rabbit 1 (2026.2.1), Windows x86_64/WSL, prepared caches: sync succeeds in 29.525 seconds and
  IDE model import completes. Default/scoped providers and application modules appear in the
  Gradle tree. Evidence: build/ide-verification/android-studio-sync.log. No code fix required.
- [ ] ARM64/API21 runtime, fresh-machine setup and other build hosts.
- [ ] Broader IDE variant switching and run/debug workflows.

Earlier rollout sections below retain their historical verification limits; this result extends
only basic GUI sync coverage for the current existing-checkout environment.

## Android explicit initialization follow-up

- [x] Separate Startup cache-path configuration from JNI loading and explicit PariKt.init().
- [x] Update proof apps to exercise deferred startup, invalid/custom sizes and reinitialization.
- [x] Update onboarding/API documentation and verify Android runtime on both selected versions.
- [x] Run Android packaging/identity and shared backend/configuration-cache regressions.
- [x] Centralize NDK pins and align JNI entry-point/adapter names; rebuild and retest bindings.

Both versions pass explicit-init/reinit and deferred JNI loading on x86_64 API36.1; the Compose
demo passes its Ready/arithmetic check. Fourteen Android production cases, both ABI payloads,
root/consumer backend tests, strict configuration-cache storage/reuse and JVM/Native identities
pass. NDK pins are centralized in the catalog; private JNI names and all platform adapters are
aligned. Normal engine fingerprints rebuilt affected C libraries and reused canonical sources.
Four harness checks pass on PowerShell 7/5.1. Logs: build/android-explicit-init/; Android cases:
build/android-tests/3de42b05cb1a. The owned emulator/apps/data are cleaned up. All existing targets,
examples and minSdk21 remain; ARM64/API21 runtime and other environment limits remain unverified.

Production JVM, Native, Android and JS/Wasm support scoped versions and module-only configuration. Native checkpoint: 44ecbb5. Production web acceptance passes all 14 cases plus portable locks and actual execution of all four browser pages (build/wp/b2d3df3c69e8). Production Android acceptance passes 14 cases, both ABI split payloads and actual x86_64 Startup/runtime checks. The implementation checklist is complete within the verified host/runtime scope below. Shared source acquisition and complete JVM engine caching are verified. Both examples folders and all existing targets are preserved.

## Configuration contract

Keep the existing properties format:

```properties
# <consumer root>/parikt.properties: project default
pari.version=2.19.0
```

```properties
# <olderApp project directory>/parikt.properties: module override
pari.version=2.15.0
```

The PariKt source revision stays independent. Consumers do not configure bridge revisions,
compiler versions or GMP, and do not need a custom Gradle plugin or a different Kotlin API.

Selection rules:

1. A module-local file wins over the root default. Resolve its location from the Gradle project's
   actual projectDir, including custom directories and nested paths such as :apps:olderApp.
2. Without a local file, inherit the root default. Do not search other parent directories or
   inherit another module's override.
3. A present empty/invalid file is an error, not a reason to fall back. Preserve unknown-key and
   obsolete -PpariVersion diagnostics. Include the project path and file in errors.
4. Allow root-only configuration, root plus overrides, and module-only configuration without a
   root file when every module needing its own PariKt compilation has an explicit selection.
   Never choose a sibling application's version implicitly.
5. One application module's selection applies to all its targets and build variants. Separate
   applications/executables may choose different versions in the same Gradle invocation.
6. A shared library without a local override compiles against the inherited default but does not
   impose that default as a strict engine requirement on consuming applications. All selected
   implementations must come from the same pinned PariKt sources and have compatible Kotlin APIs.
7. A library's explicit local file declares an engine requirement. A consuming app with a
   conflicting selection must fail with both versions, origins and the dependency path.
8. Each final application resolves exactly one engine implementation. Do not package duplicate
   PariKt classes or silently choose the highest engine version to conceal a conflict.
9. Selection is not a request to download or rebuild again. Modules selecting the same PARI
   source revision reuse its acquisition cache, and compatible engine builds reuse one provider
   and compiled-engine cache. Adding a later module with that selection must not download or
   compile a second equivalent engine.

## Reuse contract

Keep one canonical downloaded source entry per resolved PARI revision in the shared cache.
Reuse it across modules and target builders; target-specific mutable source/build workspaces
may be derived from it because builders patch/configure sources differently. Do not point
different builders at one mutable checkout. pari-source.sh now supplies all four builders from
a shared Git object store and commit-keyed snapshots; legacy release checkouts seed that store
without a network fetch. Existing legacy caches are retained during migration.

Compiled engines are shared when PARI revision, GMP, target/ABI/API, bridge sources, compiler/
sysroot and build options match. Different Android ABIs, JVM hosts or toolchains need distinct
binaries even for the same PARI version. Give incompatible builds separate outputs, rather than
giving every consuming module another copy of an equivalent engine cache. Final app packages
still contain their required engine files; these packaging copies are not new downloads/builds.

An explicit module version requirement uses existing compatible caches. A deliberate force-rebuild
option rebuilds the matching engine entry and all matching consumers reuse the resulting entry;
it does not create a module-specific duplicate. Cached immutable release sources should not be
downloaded again merely because compilation is forced. Moving master/latest selections require
source refresh/resolved-revision handling. Retain cache validation and locks so concurrent requests
for the same missing source/engine have one producer and waiting consumers reuse its result.

| Scenario | Expected result |
| --- | --- |
| Root 2.19.0; app has no local file | app uses 2.19.0 |
| Root 2.19.0; olderApp overrides 2.15.0 | olderApp uses 2.15.0; other apps retain 2.19.0 |
| No root file; both apps explicitly configured | works if participating library compilations also have a selection |
| Participating module has neither selection | actionable missing-selection error |
| Invalid or empty module override | error identifying that file |
| App selects 2.15.0; dependency explicitly requires 2.19.0 | dependency-conflict error |

## Initial project findings before implementation

- gradle/parikt.settings.gradle.kts sets one gradle.extra["parikt.version"] and includes one
  :parikt and :pariktAndroidNative. Their build files and output paths assume those singletons.
- consoleUi depends on :parikt, so routing only direct application dependencies would miss shared
  library dependencies. Production JVM substitution now handles both direct and transitive edges;
  JavaExec/Test use the selected provider rather than looking up the root native task directly.
- Native's pari.def embeds libparikt.a in interop klibs. Replacing a runtime path alone cannot
  change that engine. Kotlin module/interop identities must survive transitive dependency routing.
- WebEngine.kt imports @parikt/engine; all apps currently share build/parikt/web-engine. The global
  webpack hook would attach every engine instance to every consumer if copied unchanged.
- Android bootstraps native dependencies before NDK model generation. Additional engine instances
  need distinct helper/CXX outputs and must retain Startup, sync behavior and minSdk 21.

## Preferred direction and proof requirements

Prototype one internal source component per distinct PARI selection, sharing the pinned PariKt
sources. Applications with the same selection reuse a component. Preserve the application-facing
:parikt dependency/selector and route both direct and transitive references for each consuming
configuration to the appropriate component. Investigate Gradle project dependency substitution
and capabilities for routing and duplicate-provider rejection.

Give components unique project/build directories and Android helpers. Generate only necessary
build scaffolding/bindings in the consumer's ignored build directory; reference shared sources
explicitly. Install catalogs/repositories/shared tool configuration once per build. Keep internal
component names out of consumer configuration.

This approach is provisional: Gradle substitution alone does not prove KMP metadata or Native
klib interchangeability. If those proofs fail, document the failure and revise toward a
version-neutral wrapper with separate engine providers before a broad refactor. Do not present
JVM success as proof that the same mechanism works on Native and web.

## Implementation checklist

### Production platform integration rollout

Native checkpoint: 44ecbb5. Production web integration now passes 14 acceptance cases, portable npm locks and actual browser execution of JS/Wasm applications on both versions (build/wp/b2d3df3c69e8). Versioned generated imports/packages, metadata-only staging, selected payload tasks and npm pruning use public Gradle/KGP APIs. Both real Compose distributions build with a temporary 2.19.0 override and selected-only raw payloads; default 2.15.0 distributions and minSdk-21 Android assembly are restored. The standalone module-only consumer passes JVM, Windows/Linux Native and both Node targets; its original root default/locks are restored and JVM/Node runs pass again. The 13-case module-only JVM suite also compiles an actual Java source through KMP classpaths (build/production-module-only/1e62ad4b850f/logs). Ten Native cases with implementation-hidden dependencies pass (build/np/519825b59afb/logs).

The platform compatibility proofs are complete. Integrate their verified behavior in this order,
keeping the working root-default and module-only JVM paths covered at each checkpoint.
Native production integration passes all ten acceptance cases: build/np/9940f9b6c63e/logs.
Stable group/leaf/module identities make default/scoped source and interop metadata/IR identical.
Final links scan all klibs for exactly one selected archive. Early explicit-request checks reject
provider conflicts before version coalescing. Root-default/rootless shared/independent chains,
both runtime targets/orders, same-version reuse, strict conflicts and restored defaults pass.
Consumer-owned caches are seeded with prepared C artifacts/stamps and validated by the builders.

All 13 module-only JVM and 11 real-application regressions pass with the new internal paths.
The real Native CLI passes 2.19.0 override runs on Windows/WSL and is restored to 2.15.0 binaries.
Android minSdk-21 assembly, shared web compilation and actual Native/JS/Wasm tests pass together:
146 tasks (build/platform-native-target-regressions.log). Shared Native metadata/commonization
passes for both providers (build/platform-native-common-metadata.log). Applied Native link checks
use Gradle Groovy interop for the public libraries property; generated templates retain typed KGP APIs.
The preserved standalone consumer passes JVM and actual Windows/Linux Native execution on 2.19.0.
All five production property diagnostics and 24 scoped selection checks also pass. Verification
logs use the build/platform-native-* prefix; no temporary module override remains.

- [x] Share stable provider/interop identities and enable production Native substitution,
  explicit-request conflict checks and exactly-one-archive validation at final links.
- [x] Integrate version-scoped web imports/packages, metadata-only staging and selected
  payload/bundle task wiring; verify Node/browser outputs and npm dependency pruning.
- [x] Integrate scoped Android helpers/source providers/CXX paths, Startup and ABI packaging,
  with explicit module-only support and documented model-bootstrap timing.
- [x] Exercise production root-default and module-only consumers across all supported platforms,
  including both task orders, same-version reuse, strict conflicts and restored builds.
- [x] Complete integrated regressions and documentation; preserve both examples folders, all
  existing targets and Android minSdk 21. Record unverified IDE/host/runtime limits separately.

### Production module-only rollout

Complete for the current production JVM contract. All 13 disposable production acceptance cases
pass: build/production-module-only/9a887c3c6a0f/logs. They exercise the actual production settings
script, same-version shared libraries, independent versions, common metadata, both task orders,
compatible cache reuse, missing/invalid/conflicting selections, duplicate providers, ambiguous
outputs, default tasks, empty-root rejection and restored valid builds. No source fetch or engine
compilation occurs. All 11 existing application checks, five property diagnostics, standalone
consumer JVM execution and Android/Native/web compilation regressions pass. Non-JVM production
routing/module-only coverage remains part of step 3; no local file supplies a root default.
Module-only acceptance is verified on Windows x86_64/WSL with prepared compatible engines; the
cache prerequisite is documented. Property diagnostics pass in both PowerShell 7 and 5.1.
All 24 scoped selection/discovery checks also pass (build/module-only-selection-regressions.log).

- [x] Make the root default optional; include empty public selectors without configuring a
  singleton engine/helper when no root default exists.
- [x] Route explicit JVM selections to generated providers and preserve strict transitive
  requirements, one provider per final application and compatible engine-cache reuse.
- [x] Diagnose missing participating selections and default-engine convenience tasks clearly;
  retain an explicit gate for rootless non-JVM targets until their production routing is enabled.
- [x] Verify production rootless consumers, same/different selections, cache reuse, missing and
  conflicting requirements, property restoration and existing root-default builds; update docs.

### 1. Prove two JVM applications with a shared library

- [x] Create a standalone fixture containing appA, appB and sharedLogic in one Gradle build.
  sharedLogic calls the existing public PariKt API; both applications depend on it.
- [x] Prototype scoped source components and dependency routing: appA selects 2.19.0 and appB
  selects 2.15.0. Run both in one invocation without editing properties between their tasks.
- [x] Inspect compile/runtime graphs for exactly one PariKt implementation per app, including
  transitive references. Check Kotlin module identity and duplicate-class behavior explicitly.
- [x] Add a conflicting explicit sharedLogic requirement and prove the build reports it.
- [x] Record the proven component/selector layout before changing the working integration broadly.

### 2. Implement property discovery and scoped selections

This stage was proven in the experimental JVM selection layer. Production now shares its strict
parser/discovery and supports JVM routing and module-only JVM configuration without a root default.
Native production routing/module-only support is now verified too. Android production routing/module-only support now passes too.

- [x] Discover declared consumer project directories after includes are known; exclude generated
  engine/helper projects. Preserve existing settings-script placement where possible.
- [x] Share one strict parser for root/local files, recording version, origin and explicit versus
  inherited status. Use Gradle file providers so edits/additions/removal affect subsequent builds.
- [x] Replace global selection assumptions with per-component inputs. Support missing root files
  when local selections are sufficient, without using the first registered override as a default.
- [x] Define :parikt convenience-task behavior when no default exists; report an actionable error
  for a default-engine task instead of guessing. Unrelated modules need no local configuration.
- [x] Add a diagnostic task listing effective module selections/origins without native compilation.

### 3. Isolate outputs and enforce one engine per application

- [x] Parameterize shared source paths, helper dependencies, build directories and CXX/model paths.
  Remove fixed singleton output locations from generated source components.
  Production JVM/Native have scoped providers, stable klib identities and explicit request checks;
  Android source/helpers/CXX models are isolated per version and verified in production.
- [x] Preserve target/version/source/toolchain fingerprints and work-cache locks. Share compatible
  dependency caches, without sharing incompatible mutable compilation outputs.
- [x] Unify PARI source acquisition across backend work caches using a canonical immutable source
  cache per resolved revision, with separate derived mutable build workspaces as needed.
- [x] Prove that a later module requesting an already available version reuses its source download,
  source component and compatible compiled engine, without another download or compilation.
  Also verify concurrent same-version requests and force-rebuild reuse by all matching consumers.
  Verified in the JVM fixture and independent Windows JVM workspaces; other platform routing
  and complete global compiled-engine caches remain outside this completed cache proof.
- [x] Scope force/output-directory overrides; reject ambiguous paths shared by different engines.
  JVM global directory overrides are rejected with multiple selections; force rebuilds use the
  selected provider/cache. Native/web/Android force flags and output paths are version-scoped through their selected providers; there is no shared cross-version output directory.
- [x] Validate explicit transitive requirements and reject duplicate providers before final linking,
  packaging or launch. Diagnostics must identify the dependency chain and configuration files.
  Production requirements and competing source/helper providers are verified on all supported platforms.
- [x] Account for Android model bootstrap timing: do not promise conflict detection before all
  downloads until proven. Avoid preparing unused engines where requested models permit it.

### 4. Finish JVM launchers and tests

- [x] Wire JavaExec, test tasks and desktop launchers to their selected engine provider/output.
  Replace hardcoded :parikt native-task lookup where an override can apply.
- [x] Verify root inheritance, local override, same-version reuse and override edits/removal.
- [x] Run both apps in separate JVM processes in both task orders; keep the terminal/desktop
  demos working and verify strict dependency-conflict diagnostics.

### 5. Prove Kotlin/Native linking and shared-library compatibility

Verified (2026-11-03): -PnativeProof adds nativeAppA/nativeAppB/nativeSharedLogic to the existing
fixture without changing its default JVM layout. Both linuxX64/mingwX64 apps share a library
compiled against the 2.19.0 default; nativeAppB selects 2.15.0. All seven PowerShell acceptance
cases pass on Windows x86_64 with WSL Linux: forward/reverse launches, same-version reuse,
explicit shared requirements, higher/lower provider rejection and restored executable behavior.
Logs: build/native-tests/fbf7cc895dc8/logs. The default JVM fixture also passes afterward.
JVM graph tasks are scoped to JVM plugins; Native consumers expose verifyNativeGraph. Final
JVM graph and Native graph/identity checks pass (build/module-native-task-scope-{jvm,native}.log).

Stable leaf paths (:pariktEngine_<version>:parikt) preserve KGP's read-only interop identity;
Native extension templates are combined into generated project scripts. Manifest dependency
identities and Kotlin metadata/IR hashes match for both source and interop klibs on each target.
Pre-link scans require exactly one embedded libparikt.a matching the selected archive hash.
Because stable leaf/group names also share Gradle's default capability, resolved-edge validation
rejects explicit competing provider requests even if Gradle would coalesce them. Both higher
and hidden-lower injections fail before native preparation. Preserve this check in rollout.

The seven-case disposable harness is tests/build_configuration/verify_native_module_versions.ps1.
It uses short build/native-tests/<id>/f paths for Windows GNU ld.gold, direct log capture, and
safe fixture cleanup. Cache/reuse/restoration cases perform no source fetch or Native engine
compilation. Windows PowerShell 5.1 and standalone Linux-host Gradle execution are unverified.
Production Native integration now preserves these proof requirements and passes its separate
ten-case installer suite, including module-only configurations. Android production integration now passes its 14-case suite.

- [x] Extend the fixture to mingwX64 and linuxX64. Inspect Kotlin/interop klib identities rather
  than assuming different Gradle project names produce interchangeable native dependencies.
- [x] Confirm sharedLogic can compile against the default API and be used by the overriding app.
  Inspect final link inputs for exactly one selected libparikt.a and no incompatible duplicate.
- [x] Run both selected versions on Windows and through WSL Linux; assert version, arithmetic,
  factorization and error recovery without rewriting configuration between application tasks.
- [x] If embedded archives prevent compatible substitution, resolve the design gate before
  enabling Native overrides; evaluate version-neutral bindings with app-selected final linking.
  Compatible source/interop identities allow one selected embedded archive in the proof; no
  separate C ABI is needed for these two versions. Production integration passes the separate installer acceptance suites above.

### 6. Isolate JS/Wasm engine imports and assets

Verified (2026-11-03): -PwebProof adds webAppA/webAppB/webSharedLogic without changing the
fixture's default JVM layout. The apps select 2.19.0/2.15.0 while sharing a library compiled
against the default. All nine PowerShell 7 acceptance cases plus portable-lock verification pass
(build/web-tests/4970dbf2bca3/logs): unused payload suppression, both orders/cache reuse,
same-version reuse, override edits/removal/restoration, explicit shared requirements, duplicate
providers and restored Node/npm/bundles. Negative cases stop before native preparation.

Distinct npm names and generated private imports prevent hoisting from choosing another engine.
App packageJson handlers retain one selected engine dependency, dropping an inherited library's
default npm requirement. Canonical resolution paths/hashes verify both JS/Wasm workspaces. Metadata
can be installed without native payloads; only selected run/test/bundle tasks request payloads.
JS pins a coherent webpack 5.112.2 workspace; Wasm uses KGP's separate managed tooling.

Klib unique names differ between providers, but public ABI signatures and metadata formats match;
the shared compiled library links and runs against both providers on both targets. ABI inspection
uses the installed pinned Native klib tool and supports the standard konan cache without a recorded
build path (build/module-web-abi-cache-fallback.log). Ordinary web builds do not need that tool.
Both browser distributions contain exactly one matching PARI Wasm hash and no other selected
version. All four final pages actually load over loopback in IAB and report version, arithmetic,
factorization and error-recovery success. Combined Node/browser checks use production Node runs
to share KGP's production sync output. Development Node runs work in separate invocations.

Portable JS/Wasm locks are recorded in kotlin-js-store. Selection edits regenerate fixture locks
with kotlinUpgradePackageLock/kotlinWasmUpgradePackageLock. Source installation still gates
production web overrides on step 3's full component/output integration. Verified host: Windows
x86_64 + WSL, PowerShell 7; standalone Linux-host Gradle execution has not been verified here.
Default JVM identity/graphs/apps and Native identity/graphs regressions pass with web disabled.
The recorded-path-free Native SDK fallback and all four final browser pages also pass; the
24-case configuration/discovery regression also passes (build/module-web-selections-regression.log).

- [x] Give each engine a distinct staging path. Scope package-generation/webpack dependencies
  to the relevant consumer instead of installing an unconditional all-engines hook.
- [x] Prove npm workspace hoisting cannot select another application's engine. Use unique internal
  npm package identities and generated per-component import bindings if required; keep the public
  PariKt.init()/eval() API unchanged.
- [x] Check Kotlin JS/Wasm metadata identity and portable lockfiles as well as npm resolution.
- [x] Run both apps using JS and WasmJS Node targets, build both browser distributions and verify
  actual browser loading/version output. Test both build orders and unchanged-cache reuse.

The existing Emscripten factory already creates independent Wasm memories per factory call.
The one-version-per-app rule here defines scope; it is not a claim that web engines can never
be isolated. Multiple versions inside one application are a separate, unplanned feature.

### 7. Support Android application overrides

The isolated compatibility proof behind -PandroidProof is preserved. Production Android integration now passes its separate 14-case suite and actual x86_64 Startup/runtime checks. The original eight isolated PowerShell 7 cases pass:
build/android-tests/0eb0443f458e/logs. They cover both task orders, shared Kotlin reuse, byte-identical
classes/module metadata, same-version reuse, override removal/restoration, explicit requirements,
duplicate engines/helpers and restored builds. Conflicts fail actual assembly before packaging.
Every split has one complete native set; PARI/GMP hashes match stripped canonical selected cache
binaries. Both helper AARs have independent namespaces/outputs, two ABI sets and minSdk 21.

The opt-in root shares AGP/KGP classpaths without loading AGP in other modes. The helper reuses
the existing build script and canonical caches, with independent CXX/model directories. The
Activity uses AGP's Kotlin source-directory API. Both apps pass on x86_64 API 36.1 through
automatic Startup, exact versions, arithmetic/factorization and recovery. Emulator storage required
sequential installs; unrelated apps were untouched. arm64 runtime and Android Studio sync UI
remain unverified; NDK models configure/build for both versions and ABIs. Production demo assembly,
supported ABI filtering/minSdk, default JVM, Native identity/graphs and JS/Wasm Node checks pass.
All 24 configuration/discovery regression cases also pass; both examples folders are preserved.

- [x] Add two Android apps with distinct application IDs and shared Kotlin code. One inherits
  the root selection; the other overrides it. Retain minSdk 21 and automatic Startup initialization.
- [x] Resolve one matching native bridge AAR per app, with independent helper/CXX directories.
  Verify NDK model generation with both selections; IDE sync UI coverage is recorded above.
- [x] Assemble both APKs in one invocation and inspect each ABI for exactly one GMP/PARI/JNI set.
  Restrict packaging to arm64-v8a/x86_64 and demonstrate per-ABI APK splits; transitive 32-bit
  graphics libraries must not make an APK advertise unsupported PARI architectures.
- [x] Run both apps on the supported emulator and assert their respective versions and evaluation.
  Reject conflicts before packaging; do not conceal them using native-library pickFirst rules.

### 8. Regression coverage, examples and documentation

- [x] Add PowerShell configuration/graph checks for inheritance, module-only configuration,
  invalid overrides, nested paths, custom projectDir and explicit transitive conflicts.
- [x] Preserve existing configuration checks, adapting the missing-root case to the new rules.
  Demonstrate simultaneous selections in the fixture, not just sequential global-file changes.
- [x] Run existing Android, Compose, JVM, Native and JS/Wasm checks. Root-only consumers retain
  their existing dependency declarations and behavior; matching selections reuse caches.
- [x] Document setup, strict library requirements, shared-library behavior, diagnostics, source
  installation limits and actual verified hosts. Update HANDOFF.md after each implementation step.

## Step 1 proof results (2026-11-03)

The standalone examples/module-versions build uses the pinned repository sources, a root default
of 2.19.0 and appB's module-local 2.15.0 override. Settings generates one internal KMP JVM source
project per distinct selection under ignored build/parikt/engineProjects. An empty :parikt
selector retains the consumer-facing dependency declaration. Per-consumer configuration
substitution routes direct and transitive selector references; the two providers also declare a
common engine capability without a highest-version conflict-resolution rule.

Both providers use the stable Kotlin module name parikt. Every JVM class and Kotlin module
metadata entry has the same SHA-256 hash across the two jars. sharedLogic compiles once against
the default provider and its unchanged jar runs with both final application selections. Each
app's compile/runtime graph contains one engine, five PariKt class entries, and one
META-INF/parikt.kotlin_module. Runtime class-resource enumeration also finds exactly one PariKt.

tests/build_configuration/verify_module_versions.ps1 passes seven cases under PowerShell 7.6.9
on Windows 11 x86_64, with native engines built through WSL: forward and reverse app order,
compatible explicit sharedLogic requirement, conflicting compile requirement, conflicting runtime
requirement, injected duplicate provider rejection, and restored valid graph. Both apps assert
getVersionName(), arithmetic and exact factorization in each order, without editing selections
between their tasks. The reverse invocation reuses both native caches and sharedLogic compilation.
Conflict errors contain both versions, both properties paths, configuration and dependency path.
All negative cases fail before invoking a native builder. Temporary copied fixtures are removed;
logs remain under build/module-version-tests. The final checked-in fixture also passes identity,
all three module graphs and both apps with --warning-mode all and no deprecation warnings.
Root help and :terminalApp:compileKotlin pass (build/module-versions-baseline.log).

See examples/module-versions/README.md for commands and layout details. The original three-module
discovery list has since been replaced by the step-2 implementation below. Android minSdk 21 and
all existing target declarations remain unchanged. Full cross-target regressions and Linux-host
execution were not rerun for this work. The runtime proof script runs under PowerShell 7;
Windows PowerShell 5.1 coverage below applies to the configuration/discovery scripts.

## Step 2 selection/discovery results (2026-11-03)

gradle/parikt.properties.settings.gradle.kts is the single provider-based parser used by the
production installer and experimental JVM settings. It records version/origin and
explicit/inherited status, rejects invalid present files (including malformed Java-properties
Unicode escapes), and preserves unknown-key and obsolete-property diagnostics. Production and the
experimental JVM layer now both permit module-only configuration within their supported scopes.

gradle/parikt.selections.settings.gradle.kts discovers descriptors at settingsEvaluated, observing
all ordinary includes and final projectDir mappings. Integration-owned selector/engine/helper
projects are excluded. After project configuration, declared project-dependency edges determine
which modules directly or transitively use PariKt; only these require a selection. No dependency
resolution or native build is needed. pariktSelections lists effective selections, origins,
explicitness and participation. Unrelated modules may remain unselected. Component generation
and version inputs in the JVM fixture use the discovered map, never a first-override default.

The selector's five convenience tasks (buildJvmNativeDependencies, jvmJar, build, assemble, check)
forward only to a root-default engine, and fail actionably when the root file is absent—even
when every module agrees on one version. No native builder runs for diagnostics or errors.

tests/build_configuration/verify_module_selections.ps1 passes all 24 cases under PowerShell 7.6.9
and Windows PowerShell 5.1: defaults, root-only/matching-version component reuse, additions/edits/
removal, invalid/empty/unknown/malformed files, legacy property, missing app/shared-library
compilation selections, module-only configuration, no-default convenience errors, unrelated
modules, nested/custom directories, non-inheritance from parents/siblings, script placement before/
after includes, and helper exclusion. Logs are in build/module-selection-tests; shell transcripts
are build/module-selections-verification.log and build/module-selections-ps51.log. All diagnostic
runs assert no native builder, JVM compilation or deprecation warning. Temporary fixtures are
cleaned up. The existing five production diagnostic cases also pass in both PowerShell editions.
Root help/:terminalApp:compileKotlin pass with --warning-mode all (build/module-selections-baseline.log).

All eight JVM runtime/graph/conflict cases pass under PowerShell 7 after discovery generalization
(build/module-selections-jvm-regression.log). The additional module-only case removes the root
file, explicitly selects 2.15.0 in appA/appB/sharedLogic, compiles/resolves both applications to
the single reused component, and launches both with successful version/arithmetic/factorization
assertions. The shared library's explicit requirement agrees with both applications. Its native
cache is reused; :parikt convenience tasks still require a root default, as tested separately.
Runtime logs remain under build/module-version-tests; copied fixtures are removed.
Production output isolation/routing, JVM launchers and Native/web/Android compatibility proofs are complete. Configuration-cache compatibility is covered by the separate follow-up acceptance below.
Production Android routing is implemented and verified. No custom Gradle plugin was added.

## Step 3 cache implementation and verification

pari-source.sh serializes acquisition into ~/.cache/parikt/sources/pari.git, storing pristine
snapshots at pari/<resolved-commit>. All four builders use it and derive separate mutable
*-work directories. Existing backend Git checkouts remain intact and can seed immutable tag
objects without downloading again. Only moving master/latest refs refresh; their snapshots
deduplicate by resolved commit. A failed fetch or incomplete extraction never publishes a
workspace; a missing snapshot can be reconstructed from cached objects. Recipe/target identities
reset mutable workspaces before applying changed build patches. New Git stores are initialized
privately before atomic publication. The helper is declared
as a Gradle native-task input and included in builder fingerprints.

JVM complete engines are cached at ~/.cache/parikt/engines/jvm/<fingerprint>. Identity uses the
resolved commit, GMP, target, bridge/JNI/build-script inputs, compiler binary/target/sysroot and
build options. Selection labels do not duplicate a compatible engine. Linux's optional-data
directory is a real build option and is included in its identity. A per-fingerprint lock spans
validation, compilation and publication even for independent mutable work directories. Force
replaces the same entry; generation markers refresh staged consumer copies on subsequent runs.
The work-lock helper combines exclusive file claims with legacy directory guards, holds multiple
locks, and releases only those owned by the current shell/subshell level. No extra package is needed.

verify_source_cache.ps1 passes nine source-acquisition/isolation/recovery checks through WSL
under both PowerShell 7 and Windows PowerShell 5.1, including three contending requests and
recipe reset. The contention suite passed three repetitions per shell. The expanded native
lock test also passes. The final module run passes eleven cases including a later appC and a
single forced producer (build/cache-reuse-jvm-final.log). The final independent-workspace proof
passes all four cases: cold producer serialization, forced refresh, metadata repair, and
release/master/latest compiled-cache alias reuse (build/cache-reuse-concurrent-jvm-final.log).
The JVM harness now logs to unique per-run files instead of waiting for EOF on inherited pipes;
build/module-version-tests/latest-run.txt locates the retained case logs.

Android assembleDebug (both ABIs, minSdk 21), Native Linux/Windows verification and web engine
smoke plus JS/WasmJS Node tests pass together: 145 Gradle tasks, 24 executed/121 up to date, logged in
build/cache-reuse-target-regressions.log. Linux tests and web smoke execute successfully; Windows
Native tests also execute directly and pass. The independent production consumer (2.19.0) and terminal
launcher (2.15.0) restore compatible JVM engines and pass actual evaluation. Their logs are
build/cache-reuse-independent-consumer-final.log and build/cache-reuse-terminal-final.log.

Global compiled-engine caching is implemented for JVM; the other backends retain their existing
target/stage caches while sharing source acquisition. Production module routing and multi-application output identity now pass the corresponding installer suites on all supported platforms. Apple hosts, clean-
machine setup, Linux JVM cross-output reuse and emulator execution were not verified here.

## Boundaries

- One selected engine per final APK, Native executable or JVM application/process. Classloader
  isolation, renamed native symbols and multi-engine runtime APIs are outside this feature.
- No per-target, flavor or build-type version selection in the first implementation.
- No new Apple targets, Maven publication or automatic host-prerequisite installation.
- Implementation/runtime validation is limited to the completed proof recorded above.

## Primary references for the proofs

- [Gradle dependency substitution](https://docs.gradle.org/current/userguide/resolution_rules.html)
- [Gradle capabilities](https://docs.gradle.org/current/userguide/component_capabilities.html)
- [Kotlin/Native embedded static libraries](https://kotlinlang.org/docs/native-definition-file.html)

These APIs underlie the completed compatibility proofs and production acceptance suites above, verified against the pinned repository toolchain.


## Production Android integration results (2026-11-04)

All 14 cases pass: build/android-tests/019645ae5a8b/logs. Source classes/Kotlin metadata match across default/scoped providers. Every arm64-v8a/x86_64 split contains one selected PARI/GMP/JNI/runtime set; PARI/GMP hashes match stripped canonical caches. Both task orders, same-version reuse, override removal, explicit shared/source/helper conflicts, rootless shared/independent chains, missing selections, default-task errors and root-default restoration pass. Final rootless APKs are retained in that run's apks directory.

Both retained x86_64 apps actually pass Startup/version/arithmetic/factorization/error recovery on API36.1 (build/platform-android-runtime-production-final.log). The owned emulator used a separate fresh data image after the existing partition lacked install space. Only new proof apps were installed/uninstalled; existing apps were preserved. Real Compose Android 2.19.0 override assembly and debug/release graphs pass, and its property/binaries are restored to 2.15.0. Final all-target regression: 175 tasks, 55 executed/120 up to date (build/platform-android-all-targets-final.log). All 11 real-application and 13 module-only JVM cases pass, as do five property diagnostics and 24 discovery checks.

Settings now emit stable complete provider/helper scripts; optional Android setup runs inside them when the root shares AGP (see the configuration-cache follow-up). This retains Kotlin-only rootless builds without AGP/SDK requirements. Helpers reference canonical sources, consumer-owned native caches and distinct CXX/build paths; selected provider manifests preserve Startup/minSdk21. Public selectors never infer a missing root default. CLI bootstrap filters real participating Android selections; IDE requests conservatively prepare all helper models after project configuration. Cold model setup may precede conflict resolution. Android Studio sync UI, ARM64/API21 runtime, Apple/clean-machine/Linux-host coverage and publication remain outside verified scope.

Final web regression after Android integration also passes all 14 cases and portable-lockfile checks: build/wp/50ce7e896860/logs. Temporary fixtures, properties and the owned emulator data image are cleaned up. Both examples folders, existing targets and Android minSdk21 are preserved. No custom Gradle plugin was introduced. The remaining limits above concern additional host/IDE/runtime coverage, not unfinished items in this implementation checklist.

## Configuration-cache compatibility follow-up

- [x] Make JVM engine commands and launcher/test library paths serializable; verify strict store/reuse.
- [x] Make Native/web/Android builder actions and generated outputs cache compatible without a custom plugin.
- [x] Make graph/identity/selection diagnostics use serializable inputs rather than live project models.
- [x] Add PowerShell acceptance for cache reuse, root/local additions/edits/removal, rootless builds and conflicts.
- [x] Verify platform builds and cache reuse, preserve shared source/engine caches, examples and minSdk21.
- [x] Update consumer documentation and handoff with verified scope and any upstream limitations.
Configuration-cache acceptance: all 23 cases pass under strict failure mode, including fresh
first-to-second-build reuse, Kotlin-only rootless setup, AGP-shared root/defaultless setup,
root/local file additions/edits/removal, no-default selector failure/reuse, missing/invalid selections and explicit shared conflicts,
then restored cached runs. Logs: build/configuration-cache-tests/403abc387ae7/logs. The platform
store/reuse cases cover 184 tasks with zero problems: both JVM versions, class/Native identities,
Android debug assembly/graphs, Linux/Windows Native links and JS/Wasm tests/graphs.

Additional strict cache store/reuse passes actual standalone JVM/Windows Native/JS/Wasm runs,
Linux execution, JVM UI/shared tests, both Compose browser distributions, interactive JVM/Windows
Native CLI exit, and Android's local 2.19.0 override (restored afterward). Deleting the consumer's
konan.txt before both storage and reuse proves KGP regenerates metadata before deferred Native
arguments read it. Logs use build/configuration-cache-{consumer,native-metadata,terminal,native-cli,
ui-tests-browser,android-override}-*.log. Existing Native ten-case and Android fourteen-case
production regressions pass: build/np/fcd993683b91/logs and build/android-tests/763156329ed9/logs.

Stable provider/helper scripts are generated completely during settings; optional Android models
use public Gradle/AGP properties when the root shares AGP. This avoids modifying script inputs
after Gradle reads them and preserves Kotlin-only module-only builds. Runtime actions retain paths,
strings, file collections and a plain Native argument bean, with no live Project/Task references.
The participant set is filled during configuration and serialized as read-only task data for npm
handlers. Cold Android preparation uses Gradle's tracked process provider. No custom plugin,
global cache enablement, target/ABI change or Android minSdk change was introduced. Host/IDE and
publication limits remain as recorded above. Final web regression passes all 14 cases plus portable locks (build/wp/8f7e32404d8c/logs). No temporary selection files remain.

## Project review follow-up (2026-11-04)

Review at 6e81a26: PROJECT_REVIEW.md. All six correctness fixes are implemented and verified.

- [x] [P1] Use actual PARI hash-table bounds in JNI command enumeration (2.15.0 crash; 2.19.0 incomplete results).
- [x] [P1] Prevent GP quit callbacks from freeing PARI during an active evaluation.
- [x] [P1] Make repeated/uninitialized close safe across the common API and JNI backend.
- [x] [P2] Validate JNI stack-size bounds before entering native initialization.
- [x] [P2] Restrict JVM launcher/test wiring to actual PariKt participants.
- [x] [P2] Guard empty commands in the shared C wrapper.
- [x] Add cross-backend public API and JVM subprocess regression coverage for these boundaries.
- [x] Consolidate duplicated default/scoped provider and PowerShell harness setup after correctness fixes.

Completed plan: STRUCTURAL_CLEANUP_PLAN.md (baseline 920cd84).
Contributor onboarding is documented in docs/CONTRIBUTOR_GUIDE.md: project/target implementation,
PARI/cache integration and test-script usage, prerequisites, side effects and interpretation.
Local links/script coverage and all ten documented preparation task paths were verified.
The canonical engine definition now lives in parikt/build.gradle.kts for both default/scoped
providers; separate platform provider templates are removed. PowerShell harness extraction is
verified across nine suites. All production version/cache, backend, discovery, module-only and
independent JVM forced-build/reuse cases pass. The handoff is concise and previous rollout notes
are archived. Detailed counts, logs and coverage limits are in STRUCTURAL_CLEANUP_PLAN.md.

Shared C owns empty-input/close/quit safety and actual table bounds; JNI dynamically sizes its
command list and checks native-long bounds. Header detection supports both PARI releases in JVM
and Android builds. Common tests share boundary scenarios; JVM adds six subprocess tests for each
configured version and defensive direct-JNI checks.

Verification: both 2.15.0 and 2.19.0 pass JVM, Linux/Windows Native and JS/Wasm Node boundary tests.
All 25 strict configuration-cache cases (including unrelated JavaExec/Test store/reuse) and the
platform cache graph pass at build/configuration-cache-tests/78797ba4ac73/logs. Production version
suites pass ten Native, fourteen web plus portable locks, and fourteen Android cases. Every Android
split has exactly one selected engine; both x86_64 APKs pass Startup/evaluation and JNI boundary
checks on API36.1. Logs are under build/project-review/fix-*. Both examples folders, every existing
target, canonical source reuse and Android minSdk21 are preserved. Remaining structural cleanup is
separate; ARM64/API21 runtime and fresh-host/IDE coverage remain outside these checks.
