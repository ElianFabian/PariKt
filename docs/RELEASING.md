# CI and Maven Central preparation

PariKt and PARI/GP have separate versions. `gradle.metadata.properties` proposes the library
identity `io.github.elianfabian:parikt:0.1.0`; root/module `parikt.properties` still
selects the engine. This metadata does not change source-provider coordinates or constitute
a working binary Maven publication. No component has been uploaded.

## What runs in GitHub Actions

`ci.yml` handles pull requests, main pushes, version tags and manual dispatch. It calls the
reusable `verify.yml`. Actions use verified immutable commit revisions, read-only repository
permissions and no publishing secrets. Checkout credentials are not retained.

- Windows supplies its JDK21 public JNI headers as an intermediate artifact.
- Ubuntu builds/tests the Linux JVM/Native engines, JS/Wasm Node and Chromium suites, shared
  UI/state tests under Xvfb and the Android debug APK. It reads the NDK version from the catalog.
  It also cross-builds the Windows Native test executable and matching JNI libraries, using the
  Windows JDK headers rather than pretending Linux JNI platform headers are interchangeable.
- A Windows runner executes six JNI boundary probes and both Native tests from those artifacts.
  It needs no WSL installation. This verifies actual Windows execution, not only cross-linking.
- Intel and ARM64 macOS jobs select Xcode 26.4 and use the Apple verification runner. Normal
  CI uses `-HostOnly`; version tags and manual `full_apple=true` add the mobile simulator tests
  and all Apple framework/test-binary links. Missing SDKs/runtimes and failed tests are failures.

Test logs/reports are retained on failure. Intermediate binaries are short-lived Actions
artifacts, never committed to Git. Normal engine selection remains 2.19.0; the explicit Apple
two-version acceptance proof additionally uses 2.15.0. CI does not disable lock checks/signing
errors or mark unsupported/skipped Apple execution as successful tests.

Full source workflow 44353196417 / a41523a passes, including the Linux-to-Windows
handoff, both Mac hosts, mobile simulator tests and all twelve Apple framework/test-binary
links. Historical failed and superseded/cancelled runs remain visible in Actions. The manual
`candidate.yml` separately tests ordinary Maven publications with external consumers; it
has no remote upload endpoint or publication credentials. Expanded candidate run 44375737186
at 9b66110 is in progress. See HANDOFF.md for the latest completed evidence.

## Publication boundary

Git ignores generated engine outputs. Currently the source installer downloads PARI/GMP sources
and compiles/caches binaries when reusable artifacts are absent. A general prebuilt-engine
download service is not implemented. Neither Maven nor an ordinary dependency automatically
runs the Gradle settings/native-build scripts contained in a producer's checkout.

The independent `publishing/` build implements version-independent bindings and explicit
Maven-loaded engine provisioning. See [publishing/README.md](../publishing/README.md) for its
settings bootstrap and proof scripts. Finish the remaining packaging gates in
`RELEASE_PLAN.md`: JVM native provisioning, Android payload/rules, Native binding/archive
separation and web engine loading without local npm file paths. Preserve one selected engine
per final app, shared-library compatibility and independently selected PARI versions. The
published binary path also needs compiler-version consumer tests; the source-consumer matrix
does not prove binary klib compatibility across Kotlin 2.x.

We deliberately do not attach Maven Publish to the existing engine-provider projects: their
Native klibs embed an engine and their web dependencies use local generated paths. Publishing
them unchanged would advertise an incomplete/version-bound installation. KProxyable's runtime
does not have this native provisioning requirement.

## Publish 0.1.0

1. Verify access to the `io.github.elianfabian` namespace in the Central Publisher Portal.
   Generate a publishing token. If KProxyable uses this namespace, the same account and
   existing PGP key can be used. Make the signing public key available to Central.
2. In GitHub repository **Settings → Environments**, create `maven-central` and add
   `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` using the token credentials.
   Add the ASCII-armored private key as `GPG_SIGNING_KEY` and its passphrase as
   `SIGNING_PASSWORD` if the key uses one. Set `SIGNING_KEY_ID` if needed to select
   the signing key. Environment protection rules can require an approval before upload.
3. Publish a regular GitHub release for tag `v0.1.0` on the verified release commit.
   The **Publish to Maven Central** workflow checks the tag against
   `gradle.metadata.properties`, runs platform CI and Maven candidate checks, and
   signs and validates the full bundle on an Apple Silicon runner. It uploads a
   user-managed deployment only after those checks pass.
4. Open the Central deployment from the workflow summary. Once Central marks it
   **Validated**, select **Publish** in the Portal.

Use the workflow's **Run workflow** button for a signed preview on the selected ref.
It assembles the same Maven coordinates, validates their metadata and retains the ZIP as
a GitHub Actions artifact. It uses a disposable key and verifies every signature locally.
The preview requires neither your signing key nor a Central token.

The signed bundle contains `parikt`, all 18 platform publications and
`parikt-engine-tools`. Its validator checks the root Gradle module redirects, the files
and hashes in module metadata, archive contents, required sources and documentation,
and detached signature presence. It generates checksum sidecars and excludes repository
indexes. Central performs signature and namespace validation before publication.

- [Central publishing requirements](https://central.sonatype.org/publish/requirements/)
- [Central Portal upload API](https://central.sonatype.org/publish/publish-portal-api/)
