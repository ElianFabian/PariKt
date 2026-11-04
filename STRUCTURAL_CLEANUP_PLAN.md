# Structural cleanup after the correctness review

Baseline: `920cd84`. Preserve root defaults/module overrides, strict conflict rejection,
one engine per application, canonical source/cache reuse, every target, both examples folders,
Android minSdk 21 and opt-in configuration caching. Do not introduce a custom Gradle plugin.

- [x] 1. Use one parameterized engine build definition for default and scoped providers.
  Keep consumer-owned outputs, optional Android support and stable Kotlin identities. Keep
  repository tests and verification tasks available without adding tests to scoped providers.
- [x] 2. Extract shared PowerShell process/log/assertion and fixture-copy/cleanup helpers.
  Preserve each suite's arguments, negative-case assertions, logs and file restoration.
- [x] 3. Verify default/scoped identities, both versions and task orders, reuse, rootless builds,
  conflicts, unrelated JVM modules, strict cache storage/reuse and all backend tests/builds.
  Run root builds serially and retain diagnostic logs in ignored build directories.
- [x] 4. Make HANDOFF.md a concise current-state entry point and archive rollout history.
  Update the module checklist/review and record exact verification scope and remaining limits.

ARM64/API21 runtime, fresh-machine setup and IDE UI are additional environment coverage, not
structural fixes. Do not mark those verified through compilation or tests on another device.

## Progress

Default/scoped providers now use parikt/build.gradle.kts; duplicate platform templates are
removed. JVM class/metadata identity passes. Nine PowerShell suites use harness.ps1 for
process/log handling and fixture helpers. The previous handoff is archived without losing its
rollout details; HANDOFF.md now describes current invariants, commands and coverage limits.
Harness probes preserve spaces, !, &, and %; non-ANSI Unicode arguments are converted by this
Windows/JBR launcher even with UTF-8 file encoding, so Unicode launcher support is not claimed.

Passed: 25 strict cache cases including platform store/reuse; 10 production Native cases;
14 web cases plus portable locks; 14 Android cases with selected-only payloads on both ABIs;
11 real-app cases; 13 module-only cases; 24 discovery checks; five property diagnostics; and
three harness checks under both PowerShell 7 and Windows PowerShell 5.1. All-backend/default
tests plus Android assembly store/reuse pass (161 tasks each); standalone 2.19.0 tests pass
(66 tasks). Backend builds retain valid incremental/up-to-date results. All 11 independent JVM
compatibility cases also pass, including forced build, shared-engine reuse, source reuse and
module-only runtime. Logs: build/structural-cleanup/.

The generated scoped build definition is byte-identical to the default definition. Suite function
names and test-case invocations match the baseline. Android APK assertions were restored unchanged
after an extraction mistake and the complete suite reran successfully. The archive preserves the
previous handoff; no rollout evidence was deleted. All planned structural cleanup is complete.
