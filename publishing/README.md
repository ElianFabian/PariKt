# Maven candidate build (not released)

This independent build compiles the canonical library with Kotlin 2.0.21. It produces
ordinary Maven/KMP publications, separate from engine preparation tools. It does not
replace the existing source installer. Do not upload candidates until RELEASE_PLAN.md's
platform and external-consumer gates pass.

From the repository root, using PowerShell 7 and JDK 21:

```powershell
./publishing/gradle.ps1 :library:publishJvmPublicationToCandidateRepository :library:publishKotlinMultiplatformPublicationToCandidateRepository :engineTools:publishToolsPublicationToCandidateRepository
```

The launcher checks the Gradle distribution checksum and retains logs in
`build/publication-logs`. Candidates are written to `publishing/build/candidate-repository`.
No remote upload happens. Signing is optional for these local checks; a release must
explicitly require signing and pass all publication checks.

After building candidates, run `./tests/publication/verify_candidate_contents.ps1` and
`./tests/publication/verify_jvm_candidate.ps1`. The latter copies only Maven artifacts into
an isolated fixture, extracts the engine tools (no Kotlin library sources), builds engines
on demand and exercises two engine versions through a shared Kotlin library. It also checks
same-version reuse and conflicting explicit requirements, using Kotlin 2.0.0 and 2.4.21.
Windows requires the existing WSL C prerequisites; Linux requires a C toolchain and JNI headers.
This first installer candidate supports JVM Windows x86_64/Linux x86_64 only. Other backends,
prebuilt engine downloads, strict configuration-cache checks and complete binary compatibility
coverage remain release prerequisites.

Native bindings deliberately contain no engine archive. Web bindings use the stable
`@parikt/engine` module name. Both require the unfinished consumer engine installer;
successful publication alone is not proof of a usable installation. Android's AAR
contains the Startup temp-directory setup and JNI keep rules, but no native engines.
