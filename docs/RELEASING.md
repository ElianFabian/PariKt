# CI and Maven Central preparation

PariKt and PARI/GP have separate versions. `gradle.metadata.properties` proposes the library
identity `io.github.elianfabian:parikt:0.1.0-SNAPSHOT`; root/module `parikt.properties` still
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

The workflows are prepared but have not run on GitHub yet. Local Windows verification of the
runtime script uses locally built artifacts; that does not establish the new Linux-to-Windows
artifact handoff or Apple hosted-runner results. Push/dispatch is a separate user action.

## Publication boundary

Git ignores generated engine outputs. Currently the source installer downloads PARI/GMP sources
and compiles/caches binaries when reusable artifacts are absent. A general prebuilt-engine
download service is not implemented. Neither Maven nor an ordinary dependency automatically
runs the Gradle settings/native-build scripts contained in a producer's checkout.

Before configuring a publishable binary KMP component, implement the packaging proof in
`RELEASE_PLAN.md`: JVM native provisioning, Android payload/rules, Native binding/archive
separation and web engine loading without local npm file paths. Preserve one selected engine
per final app, shared-library compatibility and independently selected PARI versions. The
published binary path also needs compiler-version consumer tests; the source-consumer matrix
does not prove binary klib compatibility across Kotlin 2.x.

We deliberately do not attach Maven Publish to the existing engine-provider projects: their
Native klibs embed an engine and their web dependencies use local generated paths. Publishing
them unchanged would advertise an incomplete/version-bound installation. KProxyable's runtime
does not have this native provisioning requirement.

## Central Portal setup after the packaging proof

Use the Central Portal, not the retired OSSRH deployment protocol. Its requirements include
complete POM metadata, source/documentation artifacts, checksums and signatures. The proposed
metadata follows the project's existing GPL-3.0-or-later wrapper declarations; KProxyable's MIT
license is not copied. Root LICENSE contains the standard GPLv3 text. Preserve upstream notices
in eventual engine distributions.

The future release job should:

1. Require an exact `v<library version>` tag, a non-SNAPSHOT metadata version and all platform/
   external-Maven-consumer checks from that same commit.
2. Assemble and inspect the complete candidate repository locally, including all target
   publications and engine installer/manifests, before any network upload.
3. Use a protected `maven-central` GitHub environment and a manual dispatch for upload.
4. Receive credentials only in that publication job via secret environment variables. With
   the publishing plugin used by KProxyable, the conventions are MAVEN_CENTRAL_USERNAME,
   MAVEN_CENTRAL_PASSWORD, GPG_SIGNING_KEY, SIGNING_KEY_ID and SIGNING_PASSWORD, exposed as
   the corresponding ORG_GRADLE_PROJECT_mavenCentral*/signingInMemory* variables.
5. Fail if credentials/signing or artifact validation fail. Upload for Portal validation/manual
   release first, rather than automatically publishing every pushed tag.

The Central namespace must be verified for the account used to publish; a GitHub repository
name alone does not establish access. No credentials are needed to run existing CI or metadata
checks, and no keys from KProxyable were read or copied.

```powershell
pwsh -File tests/build_configuration/verify_release_metadata.ps1
# After choosing the real release version in gradle.metadata.properties:
pwsh -File tests/build_configuration/verify_release_metadata.ps1 -Release -Tag v0.1.0
```

The checker also rejects tracked native/build binaries (except the required public Gradle
wrapper JAR). Metadata validation alone must not be described as Central-ready packaging.

Primary references:
- [Central publishing requirements](https://central.sonatype.org/publish/requirements/)
- [Central Portal publishing with Gradle](https://vanniktech.github.io/gradle-maven-publish-plugin/central/)
- [GitHub runner images](https://github.com/actions/runner-images)
