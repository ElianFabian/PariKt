# GitHub Actions and Maven Central preparation

Reference: sibling KProxyable, inspected read-only on 2026-11-18. Reuse its separation of test
jobs, release metadata and environment-only signing credentials. Do not copy its MIT POM,
ignored signing failures or lock-mismatch bypasses. PariKt's wrapper already declares GPL-3.0-or-later.

User intent: publish PariKt separately from engine binaries. PARI/GMP/native outputs remain
generated/downloaded on demand, cached and absent from Git. A source ZIP is not a replacement
for the requested ordinary Maven/KMP library dependency.

Execution authorized on 2026-11-18: commit/push the prepared work and fix actual CI failures
before creating the first release tag. Packaging/publication must pass its own external-consumer
and signing gates; a tag is not a substitute for those checks.

- [x] Add PR/push CI for Linux, actual Windows runtime and Intel/ARM64 macOS; retain reports.
- [x] Add centralized proposed publication metadata and a credential-free metadata check.
- [x] Document Central Portal namespace, signing, test gates and the binary packaging prerequisites.
- [x] Validate workflows with official checksum-verified actionlint 1.7.12, parse all new
  PowerShell scripts, validate metadata and run all six Windows JNI probes plus both Native
  tests from local CI-format artifacts. Evidence: build/ci-local/windows-1fa59209/ and stage.log.
- [x] Run the regular hosted workflows, including the Linux-to-Windows handoff and both Apple
  hosts: full run 44353196417 / a41523a passes every job, including Apple simulators
  and all twelve framework/test-binary links.
  Initial commit e0b328c / run 44340852491 reached the runners and exposed Ubuntu SDK-tool PATH
  and Apple SwiftPM inventory-routing failures. Fixes and a targeted SwiftPM regression are
  implemented (build/apple-tests/4396d7b427d5/); subsequent hosted reruns passed.
  Run 44342835721 additionally exposed generated inventory app requirements; its targeted
  regression now includes the foreign application, not only the foreign provider.
- [ ] Separate the published library from engine provisioning on every backend, prove a clean
  external Maven consumer, then add actual signed publication tasks and a gated upload workflow.
  The isolated publishing/ build is being implemented with a Kotlin 2.0.0 binary baseline (corrected after an oldest-Wasm ABI failure);
  candidate outputs are not ready for public distribution until the consumer proof passes.

## Candidate progress (2026-11-18)

- [x] Build ordinary unsigned common/JVM/Android/JS/Wasm/Linux Native/Windows Native Maven
  candidates with Kotlin 2.0.21, separate from engine tooling. Android minSdk remains 21.
- [x] Inspect current snapshot archives for leaked engine binaries, stable JVM module identity,
  Android JNI consumer rules and complete POM fields (29 archives, including four Native klibs).
- [x] Prove clean Maven-only JVM consumption on Windows with Kotlin 2.0.0 and 2.4.21: a shared
  Kotlin library, three apps, independent 2.19.0/2.15.0 selection, same-version reuse and explicit
  requirement conflicts. Evidence: build/maven-consumer/c59afbf75aa7/.
- [x] Expand Windows JVM binary consumption to all six supported release points; runtime,
  independent selections, conflicts and warm reuse pass (e80fc6af4124).
- [x] Prove Windows Native binary consumption under Kotlin 2.0.0/2.4.21 with a shared library,
  two engine versions, exactly one archive per link, runtime, visible explicit conflicts and
  warm reuse (d134ac6f2de1). Linux/Apple Native binary execution remains pending.
- [x] Recheck all five source-installer property diagnostic cases after sharing participant parsing.
- [x] Run the new manual private-candidate workflow on Linux (44350029452); it has no upload/signing credentials
  and publishes only to its temporary local repository and seven-day workflow artifact storage.
- [x] Prove Android debug/R8/both-ABI packaging, minSdk21, Startup, stripped hashes and conflicts,
  plus four emulator runtimes (fa55b711b95c); JS/Wasm Node, production bundles, custom fragments
  and four real browser tests pass under Kotlin 2.4.21 (0a7f30a4f8aa).
- [x] Prove Maven-only embedded bootstrap, module named :parikt and strict JVM cache reuse
  (28558c04de51, 76f958e35c6c, 0235cebeeb1c); source property diagnostics remain green.
- [x] Verify nine detached Maven signatures with an isolated disposable key (5f65f628b190).
  No production signing credentials were accessed; private test keyring removed.
- [ ] Repeat Native/Android/web proofs through the embedded Maven bootstrap; broaden older
  compiler web/non-JVM cache coverage. Engines build on demand; prebuilt downloads are optional
  future acceleration and are not required for the source-build provisioning path.
- [x] Publish all twelve Apple Maven candidates and consume on Intel/ARM macOS under Kotlin
  2.0.0/2.4.21 (44388331545 / 789b0d4); mobile Maven runtime remains unverified.
- [ ] Complete binary-consumer Kotlin/platform matrix and configuration-cache checks.
- [ ] Add signed, protected Central Portal validation/upload only after these gates pass.
  The repository currently has no Actions secrets or environments configured (names-only check).
  No signing key or token has been read/copied from KProxyable.
- [ ] Create the first release tag only after hosted verification and publication gates pass.

## Binary publication prerequisites

The current integration is source-based; Gradle scripts from an ordinary Maven dependency do
not execute in the consumer. Applying a publishing plugin to today's engine providers would
not create a complete portable installation:

- JVM JARs load external libraries through java.library.path. A binary consumer needs explicit
  engine provisioning and runtime wiring without requiring PariKt's Kotlin sources.
- Android needs selected per-ABI native payloads in its final APK/AAB plus JNI/R8 rules. Its
  current helper is a generated project dependency, not a public Maven dependency.
- Native cinterop currently embeds libparikt.a. A version-independent binary binding needs
  stable bridge ABI identification and selection/linking of exactly one compatible engine
  archive at final application link time. Apple SDK/deployment/ILP32 identities must be kept.
- JS/Wasm klibs currently reference selected engine npm packages using consumer-local file paths.
  Those paths cannot be published unchanged. Engine loading/bundling must support two final
  apps choosing different versions even when they share one compiled Kotlin library.

Keep PARI selection in root/module parikt.properties, reject explicit conflicts, reuse engine
caches, preserve all current targets/examples/minSdk21, and avoid a custom selection plugin.
Prove this using an isolated repository containing only candidate Maven publications and a
fresh external consumer, without a source checkout or incidental native outputs. The existing
source installer remains supported while this publication path is developed.

Only after that proof should io.github.elianfabian:parikt:<library-version> be presented as a
ready-to-use binary dependency. Do not publish incomplete klibs or publish one PariKt version
per PARI version. Credentials or tag creation cannot substitute for this packaging proof.

Corrected-baseline evidence: strict JVM cache across all six points (8cc5e3b22d5a), Native
2.0.0/2.4.21 runtime/conflict/hash/cache checks (fd94ebf0da0d), oldest web Node/bundles/fragments/
four browsers/conflicts (6a85dc2a10fc), and Android packaging/ABI/minSdk21/R8 checks (a93895e60b53).
The Android rerun is packaging-only; earlier four-emulator-runtime evidence used 2.0.21 bindings.
Newest web full proof also passes (052754ba3fad); corrected-baseline hosted Maven reruns remain pending. Source CI at 9b66110
is green (44375696793); ARM Mac Maven candidate at the previous baseline passes (a66df5cc5cb8).

Hosted corrected-baseline run 44381272892 builds/inspects all candidates and passes all six
Linux JVM/cache cases. All three Native jobs fail one 2.4.21 cache assertion after successful
builds/runtimes: KGP initially populates klib/platform/<target>, invalidating the first cache.
The corrected probe separates initial SDK provisioning from a required strict store/reuse pair;
verification is pending. Focused native_only dispatch is diagnostic; full mode remains the
release gate.

Cold-SDK correction verified: 789b0d4 source CI 44388300267 and Native-focused candidate
44388331545 both pass. Windows/Linux/Intel/ARM probes verify both compiler endpoints with
SDK provisioning followed by strict cache store/reuse, runtimes, archive identities/hashes
and conflicts. Full-mode candidate verification is still a release gate; native_only omits JVM.

Six representative Maven web Node/production-bundle/fragment/conflict points now pass locally.
Kotlin 2.1.21 requires the explicit tested Binaryen 130 profile; default 120 crashes. The same
optimization flags and both optimized Node runtimes pass with 130, as do four browser tests.
All six points also pass actual headless JS/Wasm browser tests for both PARI versions.
The final twelve cases passed on 2026-11-19. Android/web strict cache checks, full candidate
verification and signing/Central work remain open. See KOTLIN_COMPATIBILITY_PLAN.md for fixtures.

## 0.1.0 release preparation

- [x] Set Maven coordinates to 0.1.0 and require matching release metadata.
- [x] Add published-release Central workflow, disposable-key signed preview and complete bundle validation.
- [x] Verify full signed bundle assembly (44516060539): target 7b28f22 for v0.1.0.
- [x] Create the maven-central GitHub environment.
- [ ] Owner supplies Central and PGP secrets, publishes the GitHub release and approves Central publication.

Unsigned 0.1.0 preview 44513216051 passed all 20 coordinates and 126 payloads. Local nine-signature proof de16cb5fe435 and JVM strict-cache fixture 3f98abacba99 passed. The final preview adds disposable signing and verifies every detached signature. Android debug cache checks passed on the retained Maven fixture; standard Webpack directory regression proof is pending.

Signed preview 44514895465 failed before assembly because multiple publications signed a shared documentation file. Dedicated outputs fix the local multi-publication signing proof (14 signatures, 1ccf3027eb07). Signed hosted rerun 44516060539 verified all 126 signatures and validated all 20 coordinates on 7b28f22.

The release owner chose bare tag 0.1.0 and already published it at c8dba9c. Use the Central workflow's explicit release_tag input from main to retry it, after adding signing and Central secrets. The prior run stopped before signing. All retry jobs are pinned to the existing tag commit.

Full release verification can now finish before tagging. Central reuses a successful same-SHA run of that dedicated workflow and otherwise executes both suites. Only the verification schedule changes. Signed bundle assembly and validation remain required.

The original 0.1.0 release event run 44533431643 passed all eight verification jobs. Retry from main with release_tag=0.1.0 so the corrected workflow can reuse that exact historical verification and proceed to signing.
