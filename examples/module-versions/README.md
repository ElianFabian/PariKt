# Module-specific PARI versions: JVM, Native and web proofs

This standalone build exercises steps 1–2 of [the module-version plan](../../PARI_MODULE_VERSION_PLAN.md).
The default fixture is the experimental JVM integration. Production now supports JVM module
overrides too. Native routing here is an opt-in linking proof; production Native/web/Android
overrides remain gated. Existing targets and Android minSdk 21 are preserved.

From the repository root, using its JDK/Gradle/native build prerequisites:

```powershell
.\gradlew.bat -p examples/module-versions verifyEngineIdentity :sharedLogic:verifyEngineGraph :appA:verifyEngineGraph :appB:verifyEngineGraph :appA:run :appB:run
pwsh -File tests/build_configuration/verify_module_versions.ps1
pwsh -File tests/build_configuration/verify_module_selections.ps1
pwsh -File tests/build_configuration/verify_source_cache.ps1
pwsh -File tests/build_configuration/verify_jvm_cache.ps1
.\gradlew.bat -p examples/module-versions pariktSelections
```

On Linux, use `./gradlew` and PowerShell 7 for the JVM verification script. Linux execution of this
fixture is not yet verified. Windows requires WSL with the existing JVM builder prerequisites.

## Opt-in Kotlin/Native linking proof

On Windows x86_64 with WSL and the existing Native build prerequisites:

```powershell
.\gradlew.bat -p examples/module-versions -PnativeProof :nativeAppA:verifyMingwX64 :nativeAppB:verifyMingwX64 :nativeAppA:verifyLinuxX64 :nativeAppB:verifyLinuxX64
.\gradlew.bat -p examples/module-versions -PnativeProof verifyNativeIdentity :nativeAppA:verifyNativeGraph :nativeAppB:verifyNativeGraph
pwsh -File tests/build_configuration/verify_native_module_versions.ps1
```

The flag adds nativeAppA (inherited 2.19.0), nativeAppB (explicit 2.15.0), and nativeSharedLogic.
Both apps use the same shared library compiled against the default once per target. Custom
launch tasks run Windows executables directly and Linux executables through WSL. Each app
checks its version, arithmetic, factorization and recovery after division by zero.

Generated engine paths become `:pariktEngine_<version>:parikt`. Their stable leaf project name
is necessary because KGP derives its read-only interop module identity from that name.
Each provider has its own outputs, references the existing common/Native sources and reuses
the checkout's fingerprint-validated Native caches. Namespace parents use empty build scripts.
The Native extension template is combined into each generated project build script so its
Kotlin plugin APIs use the project's plugin classpath. Both apps share native-app.gradle.kts.

Before linking, checks inspect the resolved provider, every engine klib path and all embedded
libparikt.a archives. There must be exactly one archive and its SHA-256 hash must match the
selected engine. verifyNativeIdentity compares manifest dependency identities and hashes of
Kotlin metadata/IR across the providers, excluding the intentionally different native archives.
The stable leaf/group names also share Gradle's default capability. The fixture therefore
checks explicitly requested provider paths as well as resolved components: both higher and
lower competing engine requests must fail, even if Gradle would merge their versions.
All seven disposable acceptance cases pass on Windows x86_64 with WSL Linux: both orders,
identity/archive checks, same-version reuse, explicit requirements, higher/lower duplicate
providers and restored executables. Cache/reuse/restoration cases fetch no source and compile
no Native engines. Logs are under build/native-tests; temporary fixtures are safely removed.
This is a compatibility proof, not production Native override support.

## Opt-in JS/Wasm proof

```powershell
.\gradlew.bat -p examples/module-versions -PwebProof :webAppA:jsNodeProductionRun :webAppB:jsNodeProductionRun :webAppA:wasmJsNodeProductionRun :webAppB:wasmJsNodeProductionRun
.\gradlew.bat -p examples/module-versions -PwebProof verifyWebIdentity verifyWebNpm verifyWebBundles :webAppA:verifyWebGraph :webAppB:verifyWebGraph
pwsh -File tests/build_configuration/verify_web_module_versions.ps1
```

webAppA inherits 2.19.0 and webAppB selects 2.15.0, both using webSharedLogic compiled against
the default. Generated private bindings import distinct @parikt/engine-<version> npm packages.
Each app's npm manifest retains only its selected engine; an inherited library's default npm
dependency does not force that engine into the overriding app. Metadata-only packages permit
npm installation without building unused native payloads. Selected run/test/bundle tasks
prepare payloads and track only the selected asset directory.

verifyWebIdentity compares public ABI signatures and metadata formats, recording the distinct
klib unique names. It uses the pinned Native klib tool installed by the Native verification
stage, found through standard konan caches; normal Node/browser builds do not require that
diagnostic tool. verifyWebNpm checks canonical engine paths/hashes and JS webpack instance
coherence. Wasm uses KGP's separate managed tooling. verifyWebBundles builds all four production
distributions and requires exactly one selected PARI Wasm hash and no other engine version.

Use production Node runs when combining them with production browser builds. KGP development
and production sync tasks write the same npm directory and cannot be mixed in one invocation.
Development Node runs work separately. After selection edits, regenerate the portable fixture
locks with kotlinUpgradePackageLock and kotlinWasmUpgradePackageLock before verification.

All nine disposable acceptance cases plus the portable-lock check pass on Windows x86_64/WSL
with PowerShell 7: unused payloads, both orders/cache reuse, same-version reuse, override
removal/restoration, strict requirements, duplicate rejection and restored bundles. All four
browser pages also actually load and report their selected versions and arithmetic/factorization/
error-recovery success. Each distribution includes index.html and can be served over HTTP.
Production web overrides remain gated; this is an isolated compatibility proof.

The root `parikt.properties` sets 2.19.0. `appA` and `sharedLogic` inherit it; `appB/parikt.properties`
overrides it with 2.15.0. Both apps call the existing public API through the same compiled
`sharedLogic` library. Each runs in its own JVM, asserts the selected engine version, arithmetic
and exact factorization, and checks there is only one `PariKt.class` resource.

The proof layout is:

- `:parikt` is an empty public dependency selector. Consumer declarations still use
  `project(":parikt")`; application configuration does not name internal providers.
- Settings includes one generated `:pariktEngine_<version>` KMP JVM project per distinct
  selection, with a copy of `engine.gradle.kts` under `build/parikt/engineProjects/`.
  Source directories reference the pinned checkout's existing common/JVM sources directly.
- `gradle/parikt.selections.settings.gradle.kts` discovers all declared consumer projects after
  settings evaluation, including late includes and custom `projectDir` mappings. Engine/helper
  projects are excluded. Both integrations use `gradle/parikt.properties.settings.gradle.kts`
  for strict provider-based root/local parsing and version/origin/explicitness records.
- Each participating consumer configuration substitutes the public selector, including
  transitive references, with its selected component. `appA` exercises both direct and
  transitive dependencies; `appB` exercises transitive-only routing.
- All providers use Kotlin module name `parikt`. `verifyEngineIdentity` compares SHA-256 hashes
  of every class and Kotlin module metadata entry between the generated jars. Each consumer's
  `verifyEngineGraph` inspects compile/runtime components, scans for duplicate PariKt classes
  and checks exactly one stable `META-INF/parikt.kotlin_module` entry.
- Providers retain their default project capability and add the shared
  `com.elianfabian.parikt:engine` capability. Gradle rejects competing providers; there is no
  highest-version selection rule. See Gradle's [substitution rules](https://docs.gradle.org/9.6.0/userguide/resolution_rules.html)
  and [capability documentation](https://docs.gradle.org/9.6.0/userguide/component_capabilities.html).
- Resolved project graph traversal validates explicit local library requirements and reports
  both versions, both configuration files, the consuming configuration and dependency chain.
  An inherited library default does not impose a requirement on its consumer.
- Launch tasks use the selected component's native task/output. The unchanged native builder
  manages source/toolchain stamps and locks; caches are versioned under `build/parikt/native/`.
  A shared `-PjvmNativeDir` is rejected when multiple selections make it ambiguous.

The runtime PowerShell check copies this fixture into ignored `build/module-version-tests/`, runs both
apps in both orders, checks unchanged native cache reuse, adds a compatible explicit library
requirement, rejects conflicting compile/runtime requirements, deliberately inserts a duplicate
provider and verifies rejection, then restores a valid graph. It also adds appC after the first
build and checks reuse of the existing component/engine without fetching or recompiling PARI.
A forced build with appA/appC must have exactly one native producer; the following run reuses
that same engine entry. A final module-only case removes
the root file, gives all participating modules matching explicit selections and runs both
apps again with one shared source component. Logs remain in unique per-run directories;
`build/module-version-tests/latest-run.txt` points to the latest case logs. The harness uses
direct file logs so compiler daemons retaining output handles cannot stall capture. The temporary
fixture is removed after checking its path. Checked-in configuration files are never edited.

The selection PowerShell check uses `build/module-selection-tests/` for configuration checks
without engine compilation. It covers root inheritance, root-only reuse, overrides added/edited/
removed, invalid/empty/unknown/malformed files, obsolete `-PpariVersion`, module-only configuration,
unrelated modules, nested paths, custom directories, helper exclusion and script placement.

`pariktSelections` reports effective selections, origins, explicit/inherited status and whether
each module has a direct/transitive PariKt dependency. Participation is found from declared
project dependency edges after project configuration, without resolving engine artifacts. A
participating module must have a selection; unrelated modules may remain unselected. A parent
module's override is never a child default. Present invalid files always fail.

Without a root file, give every participating module its own valid `parikt.properties`. An
explicit shared-library selection remains a strict requirement, so it must agree with its
consuming applications. The root is never inferred from the first override, even if all
overrides agree. The `:parikt` convenience tasks `buildJvmNativeDependencies`, `jvmJar`, `build`,
`assemble` and `check` forward to the root-default component when available; otherwise they fail
with instructions to set the root default or invoke a consuming application's task.

PARI acquisition is now shared by all four backend builders in the host's
`~/.cache/parikt/sources`. The source-cache check uses a disposable local Git remote to count
acquisitions, race requests, verify pristine snapshots and exercise moving revisions/failure
recovery. It needs no network downloads. Legacy source caches can seed the shared Git store;
their modified working files never become canonical source files.

JVM engines use a shared fingerprinted compiled cache in `~/.cache/parikt/engines/jvm`.
The Windows/WSL `verify_jvm_cache.ps1` proof uses two independent mutable work directories and
one cold engine cache: exactly one producer compiles and the other waits/reuses it. It then
forces a replacement, verifies that the other consumer refreshes without recompiling, repairs
incomplete cache metadata, and checks release/master/latest aliases of one real PARI commit.
The test requires a previously prepared JVM compiler/GMP source cache and performs real builds.
Consumer library paths stay in their own staged output directories. Linux JVM optional-data
paths are part of engine identity; different configured paths are separate build options.

Configuration-cache support, broader production/launcher integration and Native/web/Android
identity and packaging proofs remain in the plan. JVM class/metadata equality does not prove
Native klib or JS/Wasm interchangeability. Do not enable overrides in the production integration
on the strength of this fixture alone.
