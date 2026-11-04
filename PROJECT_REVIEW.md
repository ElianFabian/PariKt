# Project review — 2026-11-04

Reviewed commit: `6e81a26`. Findings below describe that historical baseline; all six are now
fixed, with verification recorded in the resolution section.

The module boundaries are sensible: `parikt` owns the public API/backends, the Android helper
owns NDK packaging, `consoleUi` shares presentation/state, and applications provide launchers.
Source integration and its focused proofs are separated from the standalone consumer example.
The significant problems found are in runtime boundary contracts and overly broad JVM build hooks.

## Findings

### 1. [P1] JNI command enumeration uses the wrong PARI hash-table size

Location: `parikt/native/paridroid/paridroid.c:46-52`, enumeration at `:207-237`.

The dynamic-library branch defines PARI's own global `functions_tblsz` as 4095 and traverses
4096 buckets. The actual PARI 2.15.0 DLL exports a table size of **135**. Calling the public
`PariKt.getSupportedCommands()` after normal initialization reproducibly crashes the JVM with
an access violation inside `paridroid_getcommands` (`paridroid.dll+0x1909`). Disassembly places
the fault at dereferencing an invalid command entry after leaving the real table.

For 2.19.0 the exported `functions_hash_MASK` is **8191**, so the same bound visits only half
the table. JNI returned 305 commands; the web backend for that release returned 592. Platform
configuration can affect command availability, but the incorrect 4096-versus-8192 traversal
itself proves that enumeration is incomplete.

Use the real version-appropriate PARI symbols and bounds, as the static backends already do;
do not define replacement copies of PARI's globals. Add public-API command enumeration tests
on both supported JNI versions. The JNI buffer's independent fixed 1000-entry capacity also
needs an explicit overflow policy rather than silent truncation.

### 2. [P1] GP `quit()` frees the engine during evaluation

Location: `parikt/native/paridroid/paridroid.c:89-101` and `:169-182`.

The registered GP `quit` callback immediately calls `pari_close()` and returns into GP evaluation.
The evaluation/result-formatting path continues using PARI state that has been freed. Public
`PariKt.eval("quit()")` reproducibly crashes a JVM/PARI 2.15.0 child process in `libpari-gmp.dll`.

The command-line applications intercept only bare `quit`/`exit`, which does not cover `quit()`,
`quit(0)` or nested GP calls. The graphical console passes commands directly to this path.
Because this callback is shared, the same unsafe lifetime transition exists in other backends;
their crash behavior was not separately executed in this review.

Reject GP shutdown through a recoverable evaluation error, or defer shutdown until the active
evaluation and result handling have completed. Intercepting a few command strings in UI code
does not solve the callback's lifetime problem.

### 3. [P1] Repeated JVM close can crash the process

Location: `parikt/src/commonMain/kotlin/com/elianfabian/parikt/PariKt.kt:66-68`,
`parikt/native/paridroid/paridroid.c:241-242`.

The common API calls the backend even when `_initialized` is already false. JNI forwards
unconditionally to `pari_close()`. An initialize/close/close sequence reproducibly causes
a native access violation on JVM/PARI 2.15.0. Native and web bridges already guard repeated
close, and their tests explicitly exercise it, so public lifecycle behavior differs by backend.

Make closing uninitialized/already-closed state safe in the common API and defensively at the
native boundary. Add JVM lifecycle tests, including close before initialization and failed init.

### 4. [P2] Invalid JNI stack sizes can terminate the hosting process

Location: `parikt/native/paridroid/jni_bridge.c:38-39`, common initialization at
`parikt/src/commonMain/kotlin/com/elianfabian/parikt/PariKt.kt:19-22`.

JNI passes the supplied `jlong` straight to the C wrapper's `long` argument without validation.
After closing a normally initialized engine, `PariKt.init(0)` exits the JVM process with PARI's
stack-overflow/"End of program" diagnostic, instead of reporting an ordinary Kotlin argument
error. Native and web reject nonpositive sizes. On Windows, narrowing `jlong` to 32-bit C
`long` is another reason to validate representable bounds before calling PARI.

Validate common size invariants before native entry and enforce backend-specific bounds. This
does not imply that arbitrary native out-of-memory conditions can be recovered safely.

### 5. [P2] Unrelated JVM modules receive engine dependencies and library-path overrides

Location: `gradle/parikt.jvm.gradle.kts:88-106`.

With a root default, every project has a selection. The JVM hook tests only whether it has a
JVM configuration; it does not test `parikt.participants`. It therefore adds native preparation
to every `Test`/`JavaExec` task and replaces `java.library.path`, including modules with no PariKt
dependency. Native/web/Android hooks already check participation.

Confirmed with a disposable build containing only an unrelated `java` module and a root
default. `:unused:test --dry-run` schedules `:parikt:buildJvmNativeDependencies`. On an unprepared
machine this can trigger native prerequisite setup for unrelated tests, and replacing the
library path can interfere with other JNI dependencies.

Apply JVM runtime/test wiring only to participating consumers; cover root-default builds with
unrelated Java/Kotlin modules, rather than only unselected modules in rootless fixtures.

### 6. [P2] JNI permits an empty command to reach an out-of-bounds C read

Location: `parikt/native/paridroid/paridroid.c:182`, JNI evaluation at
`parikt/native/paridroid/jni_bridge.c:62-83`.

`in[strlen(in) - 1]` accesses before the buffer for an empty string. Native and web explicitly
short-circuit empty input before entering this wrapper; JNI does not. The isolated JVM empty
command probe returned successfully, so this is a statically demonstrated undefined-behavior
path, not an observed crash in this run. Put the empty-input guard in the shared C entry point
so all callers receive the same protection.

## Duplication and structural maintenance

- **Default versus scoped engine setup:** `parikt/build.gradle.kts` repeats JVM, Native and web
  registration/configuration found in `gradle/parikt.jvm-engine.gradle.kts`,
  `parikt.native-engine.gradle.kts` and `parikt.web-engine.gradle.kts`. Future changes must be
  applied twice. Prefer one parameterized provider implementation, while retaining public
  selectors, stable module identities, the existing targets and the no-custom-plugin constraint.
- **Backend lifecycle/state checks:** common Kotlin, JNI, Native C, web C and the JS adapter
  maintain overlapping state/validation. The differing empty-input, stack-size and close
  behavior above is concrete evidence of drift. Centralize the safety contract in the shared
  core and keep platform-specific ownership/loading/encoding behavior in thin adapters.
- **PowerShell harness plumbing:** process startup, direct log redirection, output assertions
  and fixture cleanup are repeated across the configuration/selection/application/platform
  suites. A shared helper would reduce fixes being applied inconsistently across scripts.
- **Historical documentation:** the handoff and module plan mix current state with many old
  intermediate assumptions and completed experiments. Keep a concise current-state handoff and
  move historical experiments to an archive or design-history document.
- **Test organization:** native and web public lifecycle tests cover cases absent from JVM tests.
  Share semantic API cases across backends, retaining dedicated subprocess tests for native
  crash boundaries and backend-specific thread/loading tests. Preserve both examples folders:
  they serve different purposes (installation example versus compatibility proofs).

## Verification and limits

- Existing JVM tests, Linux/Windows Native checks, JS/Wasm tests and Android debug assembly pass
  with strict configuration-cache storage: 170 tasks, 18 executed/152 up to date. This run used
  normal up-to-date checks, not a forced rebuild of every task.
- Isolated JVM child processes used the existing public API and prepared PARI 2.15.0/2.19.0
  libraries. Three access violations and the invalid-size process exit were reproduced without
  loading the libraries into the review host process. Core dumps were disabled.
- Runtime logs, JVM crash diagnostics, disassembly and the unrelated-module task graph are
  retained under ignored `build/project-review/`; the initial review changed no production source.
- Android/ARM64 runtime, Android Studio UI, other host platforms and a fresh machine were not
  revalidated. This review does not establish absence of further defects.

Recommended order: fix the three P1 native boundaries first; add JVM subprocess/API regression
coverage; fix size/empty-input handling and JVM participation scoping; then consolidate duplicated
provider and harness setup in separate behavior-preserving changes.

## Resolution and regression checks

All six findings are fixed in the accompanying implementation.

| Finding | Change and check |
| --- | --- |
| Command enumeration | Read actual PARI table symbols; JNI counts then allocates. Both versions enumerate 1,200 generated functions without truncation. |
| GP quit | Shared callback raises a recoverable error; bare, explicit and nested quit leave evaluation available. |
| Repeated close | Common API and shared C guard closed state; close-before-init, repeated close and reinitialization pass. |
| Stack sizes | Common API rejects nonpositive sizes; JNI checks native-long bounds. Public and direct-JNI subprocess checks pass. |
| Unrelated JVM modules | Runtime hooks require participation or provider identity. Real unrelated JavaExec/Test tasks preserve library paths and schedule no PARI preparation on strict cache store/reuse. |
| Empty input | Shared C guards before indexing; repeated empty input and subsequent arithmetic pass. |

Six JVM tests each run bounded child processes against configured engines. The shared contracts
also pass real Linux/Windows Native and JS/Wasm Node tests, with both PARI 2.15.0 and 2.19.0.
Root platform verification passes 156 tasks; the standalone 2.19.0 consumer passes 66 tasks.
All 25 strict configuration-cache cases plus platform storage/reuse pass. Production version
regressions pass ten Native, fourteen web plus portable locks, fourteen Android and eleven real
application cases. Both Android ABIs compile and package selected-only engines; both x86_64 apps
pass Startup and the extended JNI boundary smoke on API36.1. Logs: build/project-review/fix-*.
Proof apps were uninstalled and the owned emulator stopped after verification.

Shared contract coverage and core safety checks reduce the demonstrated lifecycle drift. Provider
and PowerShell harness deduplication remain separate cleanup. Both examples folders and all
targets remain; Android minSdk stays 21. ARM64/API21 runtime, other hosts and IDE UI remain
unverified in this checkpoint. These checks do not establish absence of further defects.
