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
- [ ] Run the hosted workflows, including the Linux-to-Windows handoff and both Apple hosts.
  Initial commit e0b328c / run 44340852491 reached the runners and exposed Ubuntu SDK-tool PATH
  and Apple SwiftPM inventory-routing failures. Fixes and a targeted SwiftPM regression are
  implemented (build/apple-tests/4396d7b427d5/); hosted rerun is pending. No green CI claim yet.
  Run 44342835721 additionally exposed generated inventory app requirements; its targeted
  regression now includes the foreign application, not only the foreign provider.
- [ ] Separate the published library from engine provisioning on every backend, prove a clean
  external Maven consumer, then add actual signed publication tasks and a gated upload workflow.
  The isolated publishing/ build is being implemented with a Kotlin 2.0.21 binary baseline;
  candidate outputs are not ready for public distribution until the consumer proof passes.

## Candidate progress (2026-11-18)

- [x] Build ordinary unsigned common/JVM/Android/JS/Wasm/Linux Native/Windows Native Maven
  candidates with Kotlin 2.0.21, separate from engine tooling. Android minSdk remains 21.
- [x] Inspect current snapshot archives for leaked engine binaries, stable JVM module identity,
  Android JNI consumer rules and complete POM fields (29 archives, including four Native klibs).
- [x] Prove clean Maven-only JVM consumption on Windows with Kotlin 2.0.0 and 2.4.21: a shared
  Kotlin library, three apps, independent 2.19.0/2.15.0 selection, same-version reuse and explicit
  requirement conflicts. Evidence: build/maven-consumer/c59afbf75aa7/.
- [x] Recheck all five source-installer property diagnostic cases after sharing participant parsing.
- [ ] Run the new manual private-candidate workflow on Linux; it has no upload/signing credentials
  and publishes only to its temporary local repository and seven-day workflow artifact storage.
- [ ] Finish Android, Native and web binary-consumer provisioning plus their external proofs;
  add prebuilt engine assets/checksums and validate cache reuse/fallback builds.
- [ ] Build and consume Apple Maven candidates on compatible Xcode/Kotlin toolchains.
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
