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
- [ ] Separate the published library from engine provisioning on every backend, prove a clean
  external Maven consumer, then add actual signed publication tasks and a gated upload workflow.

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
