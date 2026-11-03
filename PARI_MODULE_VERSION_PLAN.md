# Project defaults and module-specific PARI versions

Status: steps 1–2 complete in the experimental JVM selection layer. Steps 3–8 remain open.
Production shares the strict parser but still selects one PARI version per Gradle build. PARI_VERSION_PLAN.md
remains its verified baseline. JVM success does not establish Native/web/Android compatibility.
Step 3 now has shared source acquisition implemented across backend builders and JVM compiled
engine reuse verified, including the expanded concurrency and force checks. Broader output/routing
work remains open.

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

## Findings from the current project

- gradle/parikt.settings.gradle.kts sets one gradle.extra["parikt.version"] and includes one
  :parikt and :pariktAndroidNative. Their build files and output paths assume those singletons.
- consoleUi depends on :parikt, so routing only direct application dependencies would miss shared
  library dependencies. terminalApp/desktopApp also look up the root native task directly.
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

This stage applies to the experimental JVM selection layer. Production shares its strict parser;
production module routing and other platform implementations remain gated by the following stages.

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

- [ ] Parameterize shared source paths, helper dependencies, build directories and CXX/model paths.
  Remove fixed singleton output locations from generated source components.
- [x] Preserve target/version/source/toolchain fingerprints and work-cache locks. Share compatible
  dependency caches, without sharing incompatible mutable compilation outputs.
- [x] Unify PARI source acquisition across backend work caches using a canonical immutable source
  cache per resolved revision, with separate derived mutable build workspaces as needed.
- [x] Prove that a later module requesting an already available version reuses its source download,
  source component and compatible compiled engine, without another download or compilation.
  Also verify concurrent same-version requests and force-rebuild reuse by all matching consumers.
  Verified in the JVM fixture and independent Windows JVM workspaces; other platform routing
  and complete global compiled-engine caches remain outside this completed cache proof.
- [ ] Scope force/output-directory overrides; reject ambiguous paths shared by different engines.
- [ ] Validate explicit transitive requirements and reject duplicate providers before final linking,
  packaging or launch. Diagnostics must identify the dependency chain and configuration files.
- [ ] Account for Android model bootstrap timing: do not promise conflict detection before all
  downloads until proven. Avoid preparing unused engines where requested models permit it.

### 4. Finish JVM launchers and tests

- [ ] Wire JavaExec, test tasks and desktop launchers to their selected engine provider/output.
  Replace hardcoded :parikt native-task lookup where an override can apply.
- [ ] Verify root inheritance, local override, same-version reuse and override edits/removal.
- [ ] Run both apps in separate JVM processes in both task orders; keep the terminal/desktop
  demos working and verify strict dependency-conflict diagnostics.

### 5. Prove Kotlin/Native linking and shared-library compatibility

- [ ] Extend the fixture to mingwX64 and linuxX64. Inspect Kotlin/interop klib identities rather
  than assuming different Gradle project names produce interchangeable native dependencies.
- [ ] Confirm sharedLogic can compile against the default API and be used by the overriding app.
  Inspect final link inputs for exactly one selected libparikt.a and no incompatible duplicate.
- [ ] Run both selected versions on Windows and through WSL Linux; assert version, arithmetic,
  factorization and error recovery without rewriting configuration between application tasks.
- [ ] If embedded archives prevent compatible substitution, resolve the design gate before
  enabling Native overrides; evaluate version-neutral bindings with app-selected final linking.

### 6. Isolate JS/Wasm engine imports and assets

- [ ] Give each engine a distinct staging path. Scope package-generation/webpack dependencies
  to the relevant consumer instead of installing an unconditional all-engines hook.
- [ ] Prove npm workspace hoisting cannot select another application's engine. Use unique internal
  npm package identities and generated per-component import bindings if required; keep the public
  PariKt.init()/eval() API unchanged.
- [ ] Check Kotlin JS/Wasm metadata identity and portable lockfiles as well as npm resolution.
- [ ] Run both apps using JS and WasmJS Node targets, build both browser distributions and verify
  actual browser loading/version output. Test both build orders and unchanged-cache reuse.

The existing Emscripten factory already creates independent Wasm memories per factory call.
The one-version-per-app rule here defines scope; it is not a claim that web engines can never
be isolated. Multiple versions inside one application are a separate, unplanned feature.

### 7. Support Android application overrides

- [ ] Add two Android apps with distinct application IDs and shared Kotlin code. One inherits
  the root selection; the other overrides it. Retain minSdk 21 and automatic Startup initialization.
- [ ] Resolve one matching native bridge AAR per app, with independent helper/CXX directories.
  Verify Gradle sync/NDK model generation with both selections.
- [ ] Assemble both APKs in one invocation and inspect each ABI for exactly one GMP/PARI/JNI set.
- [ ] Run both apps on the supported emulator and assert their respective versions and evaluation.
  Reject conflicts before packaging; do not conceal them using native-library pickFirst rules.

### 8. Regression coverage, examples and documentation

- [x] Add PowerShell configuration/graph checks for inheritance, module-only configuration,
  invalid overrides, nested paths, custom projectDir and explicit transitive conflicts.
- [ ] Preserve existing configuration checks, adapting the missing-root case to the new rules.
  Demonstrate simultaneous selections in the fixture, not just sequential global-file changes.
- [ ] Run existing Android, Compose, JVM, Native and JS/Wasm checks. Root-only consumers retain
  their existing dependency declarations and behavior; matching selections reuse caches.
- [ ] Document setup, strict library requirements, shared-library behavior, diagnostics, source
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
production root-only installer and experimental JVM settings. It records version/origin and
explicit/inherited status, rejects invalid present files (including malformed Java-properties
Unicode escapes), and preserves unknown-key and obsolete-property diagnostics. Production keeps
its required root default; the experimental selection layer permits module-only configuration.

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
Production output isolation/routing, other JVM launchers, configuration-cache support and the
Native/web/Android proofs remain open. No custom Gradle plugin was added.

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
target/stage caches while sharing source acquisition. Production module routing and Native/web/
Android multi-application output identity still need their planned proofs. Apple hosts, clean-
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

These APIs inform the proposed experiments; the project-specific KMP identity and packaging
requirements above still need to be proven against this repository's pinned toolchain.
