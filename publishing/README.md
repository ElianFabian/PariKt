# Maven candidate build (not released)

This independent build compiles canonical library sources with Kotlin 2.0.0 into ordinary
Maven/KMP publications. Engine preparation tools are a separate artifact: PARI/GMP engines
are built on demand and cached, never included in the library or tracked by Git. The existing
source installer remains supported. Public release gates are tracked in RELEASE_PLAN.md.

From the repository root, using PowerShell 7 and JDK 21:

```powershell
./publishing/gradle.ps1 :library:publishJvmPublicationToCandidateRepository :library:publishKotlinMultiplatformPublicationToCandidateRepository :engineTools:publishToolsPublicationToCandidateRepository
```

The launcher verifies the Gradle distribution checksum and retains logs in
`build/publication-logs`. Candidates go to `publishing/build/candidate-repository`.
There is no remote upload endpoint. Local signing is optional; release signing must be required.

## Consumer settings

After publication, a consumer loads engine tools from Maven in `settings.gradle.kts`.
The following describes the candidate API, not an already available Central release.
Replace `<library-version>` and add the local candidate repository for private proofs.
Keep `pluginManagement` before `buildscript`.

```kotlin
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("io.github.elianfabian:parikt-engine-tools:<library-version>") }
}
apply(from = javaClass.classLoader.getResource("gradle/parikt.bootstrap.settings.gradle.kts")!!)
```

Include your modules normally and add `io.github.elianfabian:parikt:<library-version>` to their
library dependencies. Set `pari.version=2.19.0` in root `parikt.properties`; module-local files
supply explicit overrides. Final apps select one engine each, even when they share a compiled
Kotlin library. Conflicting explicit requirements fail. JS consumers use ES modules.
The bootstrap extracts tools under a file lock into an immutable, hash-addressed Gradle cache.
No library source checkout or custom Gradle selection plugin is required.

## Verification scripts

Run scripts from the root after publishing the relevant platform candidates:

- `tests/publication/verify_candidate_contents.ps1`: archive/POM/JVM identity/JNI-rule checks;
  rejects leaked engines. `-AppleOnly` expects all twelve Apple targets.
- `verify_jvm_candidate.ps1`: six Kotlin release points, shared library/three applications,
  both engine versions, explicit conflicts and warm reuse. `-ConfigurationCache` requires
  strict storage/reuse; `-SharedModule parikt` checks that formerly reserved module name.
- `verify_native_candidate.ps1`: Windows, Linux or host macOS applications under Kotlin
  2.0.0/2.4.21; one selected archive per link, runtime, conflicts and warm hashes.
- `verify_android_candidate.ps1`: debug/R8 APKs, both ABIs, minSdk21, Startup metadata, stripped
  payload hashes, engine-free shared AAR and explicit conflicts. Runtime is a separate step.
- `verify_android_candidate_runtime.ps1`: installs/runs all four proof APKs on an explicitly
  selected emulator, then removes them. It rejects physical devices and existing proof packages.
- `verify_web_candidate.ps1`: JS/Wasm Node runtime, production bundles, preserved user Webpack
  fragments and conflicts. `-Browser` adds real headless-browser tests.
- `verify_signing.ps1`: isolated one-day synthetic PGP key, required signing and detached-signature
  verification. Cleans its private keyring; does not use production credentials or upload.

Older Kotlin browser-test tooling needs an explicit Karma framework to serve emitted binary
assets; the web probe's `proof-karma/assets.js` supplies it through public `useConfigDirectory`.
Modern KGP already supplies `webpack-output`, so the adapter skips itself. This is test-runner
setup; production web routing does not depend on Karma. The probe verifies emitted custom
fragment assets because old KGP filters successful Webpack stdout. JVM/web probes support
`-ResumeDirectory` only inside their retained fixture directories; use the matching compiler/options.
Native `-ConfigurationCache` requires strict store/reuse, verified under both compiler endpoints.

The scripts retain fixtures/logs under `build/maven-*-consumer` and `build/signing-proof`.
Windows C builds require WSL prerequisites; Linux requires C/JNI tools; Apple requires a compatible
Xcode and modern Bison. Android needs SDK/NDK; web needs Emscripten through the existing builder.

With the corrected Kotlin 2.0.0 publication baseline, local proofs pass: all six JVM points
with strict cache reuse; Native oldest/newest runtimes and strict cache reuse; Android debug/R8
packaging, both ABIs and minSdk21; and oldest/newest JS/Wasm Node, bundles and four browser tests
per endpoint. All use the embedded Maven bootstrap. Four Android emulator runtimes and nine
synthetic-key signatures were verified at the earlier milestone; Android runtime was not
repeated after changing the compiler baseline.

Hosted source CI is green at 9b66110. Its Maven candidate run passes Linux JVM/Native and ARM
Mac all twelve publications/host consumers; Intel Mac still runs. Those artifacts used the
previous 2.0.21 baseline, so corrected-baseline hosted checks remain a gate, alongside intermediate
web compiler lines, Android/web strict cache checks and actual Central bundle/upload work.

## Build files

- `library/build.gradle.kts`: canonical library bindings, stable JVM metadata, Android minSdk21,
  engine-free Native klibs and generic `@parikt/engine` web imports.
- `engineTools/build.gradle.kts`: C sources/build recipes and ordinary applied Gradle scripts;
  no Kotlin library implementation or compiled engines.
- `parikt.bootstrap.settings.gradle.kts`: Maven JAR extraction and settings entry point.
- `parikt.binary.settings.gradle.kts`: root/module parsing, participant discovery, strict
  requirement validation, JVM provisioning and C-only Android helpers per selected version.
  Android helpers attach to final applications; shared AARs retain only Maven bindings.
- `parikt.binary.native.gradle.kts`: consumer SDK preparation, final Native engine linking and
  rejection of embedded extra engine archives. Apple floors come from consumer compiler metadata.
- `parikt.binary.web.gradle.kts`: per-process Node routing and per-app Webpack aliases; preserves
  user fragments and rejects conflicting explicit engine aliases.
- `publication.gradle.kts`: POM, documentation artifacts, optional signing and local repository.

The applied scripts live under `publishing/engineTools/`. They configure standard Gradle/Kotlin/
Android plugins; a successful archive publication alone does not prove consumer installation.
