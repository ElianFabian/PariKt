# Consumer-selected PARI version

## Decision

The consumer selects PARI in its root `parikt.properties`, independently of the PariKt source
revision. Do not publish one PariKt release per PARI version, introduce a version-selection
plugin, or silently choose a PARI version for consumers.

The first supported integration is source modules included by a settings script. Pin PariKt
using a Git submodule or a checked-out release. The consumer compiles the selected engine;
generated AARs, klibs and web assets necessarily contain that selection. This is not yet a
binary-only Maven installation. Source integration preserves the existing static Native backend
without introducing runtime engine loading or a new C ABI.

## Checklist

- [x] 1. Add a required `parikt.properties` reader with useful missing/invalid/conflicting-setting
  errors. Read the file through Gradle providers so edits invalidate configuration.
- [x] 2. Switch Android, JVM, JS/Wasm and Native to that one selection. Remove implicit defaults
  from Gradle and native scripts. Keep GMP/compiler configuration internal.
- [x] 3. Add reusable source-module settings integration with its own dependency catalog, consumer
  build directories and native outputs. Do not depend on the consumer's catalog or repository layout.
- [x] 4. Add a standalone consumer example. Pin the PariKt checkout separately from `pari.version`.
  Exercise the public API and assert the requested version and real evaluation.
- [x] 5. Verify two PARI versions, switching back, missing/invalid configuration, source/bridge
  cache invalidation and existing-target regressions. Record exact verification coverage.
- [x] 6. Document setup, prerequisites, generated outputs, version/cache semantics and limits.

## Binary identity

Users set only `pari.version`. Generated engine caches must distinguish PARI source, GMP,
target/ABI, bridge/header sources, compiler/sysroot and build options. Source hashes do not
require a manual bridge version counter. An explicit bridge ABI or build revision becomes
relevant if separately compiled engine artifacts are distributed later.

## Distribution limits

An ordinary dependency cannot execute its own Gradle scripts in the consuming build. The
settings integration is an explicit installation step. A future source distribution can package
the same modules/scripts, but Maven publication and automatic source/toolchain provisioning on
every host are outside this change. Existing target/host restrictions remain in force.

## Verification

- Gradle configuration succeeds; five configuration-diagnostic checks pass before native setup.
- Work-cache lock test passes, including command substitutions/subshells and competing builders.
  Real concurrent consumer/development builds also wait for each other without staging corruption.
- Independent consumer JVM, Windows/Linux Native, JS Node and WasmJS Node programs pass with
  both PARI 2.15.0 and 2.19.0. Each asserts the requested version, arithmetic and factorization.
  Android bridge AAR assembly passes for both selections and both configured ABIs.
- Development PARI 2.15.0 regressions pass: Android minSdk-21 assembly, shared Compose state tests,
  desktop UI integration test, both Native test suites and JS/Wasm launcher compilation.
- Switching the consumer from 2.19.0 to 2.15.0 and back to 2.19.0 passes; the restored 2.19.0
  engine caches are reused. The root demo/test selection stays 2.15.0 throughout.
- A temporary JNI source comment changes the fingerprint and triggers a real rebuild, without
  changing PARI 2.19.0. Restoring the exact source restores its original fingerprint; the next
  unchanged run reports a cache hit and passes public-API evaluation. The source is restored.
- Both production Compose browser distributions and the normal JVM terminal launcher pass after
  migration. Root and standalone npm lockfiles use the stable consumer-owned relative engine path;
  obsolete engine entries have been removed and npm lockfile upgrade tasks pass.

Checks are in `tests/build_configuration`; runtime/build logs are under ignored `build/`.
Host coverage is Windows 11 x86_64 with WSL Linux x86_64. This change did not rerun the Android
emulator: Android coverage here is APK/AAR assembly, retaining the previously tested runtime path
and minSdk 21. Apple targets, clean-machine setup and Maven publication remain unverified/unimplemented.
