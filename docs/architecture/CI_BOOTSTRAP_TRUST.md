# CI bootstrap supply-chain trust (issue #144)

The six required CI jobs run on GitHub-hosted `ubuntu-24.04`. Workflow actions use reviewed immutable 40-hex Git commit SHAs, with human-readable version comments. Each job runs `python3 -I tools/check_ci_bootstrap.py --audit`, a **drift guard, not an adversarial trust boundary**: a malicious PR can change the workflow and the audit policy together. Actual change authority requires `CODEOWNERS` plus enforced code-owner review on protected branches. The `contracts` job runs offline negative fixtures for changed/mutable action refs, checkout credentials and altered Gradle archive/checksum inputs.

## Authenticated Gradle executable

The four Gradle-consuming jobs no longer rely on `gradle/actions/setup-gradle`. Each downloads `gradle-9.8.2-bin.zip` over HTTPS to a fresh runner temporary directory, validates the ZIP against the independently reviewed SHA-256 below, and only then unpacks/executes the distribution:

`21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`

Source: [official Gradle 9.8.2 release checksums](https://gradle.org/release-checksums/), binary-only distribution. The trust root is committed; the code never accepts the downloaded adjacent `.sha256` file as an authority. When `CI=true` or `GITHUB_ACTIONS=true`, `./gradlew` requires the `RECURSIVE_VERIFIED_GRADLE` absolute executable path and refuses a locally installed or cached Gradle fallback. `CI=false` without `GITHUB_ACTIONS=true` uses the normal local launcher. Third-party CI must independently install and verify the distribution and supply `RECURSIVE_VERIFIED_GRADLE`; the bundled `--install-gradle` provisioning is GitHub Actions-specific. The shell launcher checks that the path exists and is executable, while the trusted GitHub bootstrap step supplies that path after verifying the archive (the launcher does not rehash it). The same verified directory is prepended to later steps' PATH for standalone Gradle fixture tools; the isolated production verifier calls the binary by absolute path. Local developer builds continue using the pre-release system-Gradle launcher; a self-contained Wrapper is tracked separately in #12.

The dependency SHA-256 manifests apply only after Gradle itself is authenticated. This change leaves all six CI job names and the independent production distribution verifier intact.

## Why 9.8.2, not 9.9.x (issue #147)

Gradle 9.8.2 is a **patch update within the already reviewed 9.8 line**. It
fixes the [false virtual-platform alignment warning](https://github.com/gradle/gradle/issues/39454)
listed in the [9.8.2 release notes](https://docs.gradle.org/9.8.2/release-notes.html),
and includes the security fixes from 9.8.1. The binary-only ZIP SHA-256
was independently checked against [Gradle's official release checksums](https://gradle.org/release-checksums/)
before updating the trusted manifest.
This change does not silently move the project to Gradle 9.9.x, whose
new minor-version behavior and plugin compatibility require a separate
reviewed upgrade, not a broadened bootstrap patch.

The compatibility gate remains JDK 21, Kotlin 2.4.21, strict dependency
verification, Gradle configuration-cache regressions, and the isolated
production launcher/bytecode verifier. All six GitHub-hosted jobs must pass
against the new authenticated distribution before a squash merge to `dev`.
The ZIP is hashed **before** any extraction or execution, and negative
fixtures explicitly reject a tampered download without invoking `unzip`.

## Remaining hosted-runner and JDK trust boundary

We trust GitHub's runner image, execution and artifact services, hosted-machine isolation, implementation bytes at the pinned action commit SHAs, and GitHub file-command machinery. The workflow still trusts the Temurin JDK 21 selected by the pinned `actions/setup-java` (and Python 3.12 / Node 22 installers where used). JDK, Python and Node *versions* are selected, not independently authenticated byte-for-byte by a reviewed toolchain-archive hash: action pinning is not toolchain archive attestation. HTTPS and Gradle's upstream distribution infrastructure are still required for availability, while the pinned Gradle digest ensures content integrity. No C3, tool, model, resume or side-effect authority changes are included.

## Code-review policy and maintenance

As checked on 2026-11-22, [the active `dev` ruleset](https://github.com/YuriiSokolenko/recursive-coder/rules/27857731) requires all six CI checks and a PR but currently sets `required_approving_review_count: 0` and `require_code_owner_review: false`. The separate [staged `main` ruleset](https://github.com/YuriiSokolenko/recursive-coder/rules/27859663) is also active, but has not enabled required approvals and still lists the older two-job checks. Therefore CODEOWNERS currently **does not enforce security-owner approval** on either branch. Enabling independent code-owner review for `dev` and `main` is tracked in [#146](https://github.com/YuriiSokolenko/recursive-coder/issues/146); this PR cannot establish that control by editing in-repository scripts.

Every checkout must set `persist-credentials: false`, enforced by an offline negative-tested drift check. The audit deliberately rejects alternative/flow-style `uses:` references: reviewers must inspect any new action shape or reusable workflow before adapting the checker. Major-version action comments are human-readable hints, not authority; updating an action pin requires verifying the target GitHub commit, changing the workflow SHA and version comment, updating `APPROVED_ACTIONS` and exercising the negative tests. The audit accepts `# v7` or `# v7.0.1`-style comments but compares them against the explicitly reviewed value in its allowlist, so dependency bots cannot silently authorize a new version.

## Downloaded module cache (#215)

Gradle distributions still use the fresh authenticated ZIP bootstrap above.
The producer (`bytecode-build`) alone restores an exact-key persistent cache
into a fresh isolated Gradle user home. The key covers OS, architecture,
Gradle version, both verification manifests, the build-logic version catalog,
every `*.gradle.kts` script (settings, build scripts and the precompiled
convention plugins that declare dependencies), root Gradle properties, the
cache transfer policy, the dependency-resolution init script and the workflow.
The audit refuses a key that misses any tracked dependency input. Save reuses
the restore-time primary key. There are no restore-keys; only successful
producer execution on a push to `dev` or `main` may publish a persistent
entry. PR and dispatch runs never save one.

### Resolution happens once, before compilation

Only two producer steps may contact registries: plugin resolution (`help`)
and `:resolveCiDependencies` from
[`tools/resolve_ci_dependencies.init.gradle`](../../tools/resolve_ci_dependencies.init.gradle),
run in the root and standalone build-logic builds. In each JVM project, a
project-local task resolves the external artifacts of every source set's
compile, runtime and annotation-processor classpath and of the Kotlin
compiler, compiler-plugin and build-tools classpaths. It never compiles or
executes tests. Both steps retry at most twice on observed HTTP 429/5xx, with
60 and 180 second delays: Maven Central's initial rate-limit blocks expire on
their own, and [Sonatype advises](https://central.sonatype.org/faq/429-error/)
against retrying harder. Verification failures are never retried.
[`tools/run_ci_gradle.py`](../../tools/run_ci_gradle.py) measures observed
Gradle HTTP GET/HEAD operations with `--debug` for these resolution-only
tasks alone. It hides debug and info events together with their continuation
lines, and shows every visible event in full.

Every other Gradle invocation in the producer, architecture and full JVM jobs,
including production compilation and all tests, runs with `--offline`. Gradle
itself then guarantees that these steps make no registry operations, so any
artifact missing from the cache fails deterministically instead of being
downloaded. The audit rejects an online Gradle command outside the two
resolution steps and production compilation before resolution.

After staging, the producer restores the staged cache into a separate fresh
Gradle home and repeats both resolutions with `--offline` before either
publication. That proof also fails if Gradle reports resolution from a context
other than the owning project. It does not fail on unrelated deprecations: the
settings repository guard still reads the deprecated `artifactUrls` property. Architecture and full JVM jobs
restore the run-local artifact from the **same workflow run**, even on a cold
PR; they do not publish persistent entries. The independent production
distribution verifier and installed CLI verifier never consume the module
cache. The artifact is overwritten on producer reruns and retained for three
days; failed-job-only reruns after expiry require rerunning the producer.

### What is transported

The transport is limited to `modules-2/files-2.1` and `metadata-2.107`, as
described in the [Gradle dependency cache documentation](https://docs.gradle.org/current/userguide/dependency_caching.html).
No lock files, garbage-collection markers, transforms, compiled scripts,
configuration cache, project state, distributions or build outputs are
transported, and unknown layouts and links refuse. A raw file is transported
only if its exact group, module, version and file name have a manifest pin
and its SHA-256 equals that pin; the same bytes under another coordinate are
not authorized. Pinned coordinates with other bytes refuse in both
directions. Gradle also downloads the POM of a module that publishes Gradle
Module Metadata to find its marker, then verifies and uses only the `.module`
file, so only that file is pinned. Those superseded POMs are the single
category of unpinned files that staging discards; they are listed by
coordinate in the job log and step summary. Any other unpinned file fails
staging, and every unpinned file refuses on restore. Gradle continues strict
coordinate-specific artifact and metadata verification. The workflow-wide
`GRADLE_OPTS=-Dorg.gradle.caching=false` default also covers Gradle builds
started by Python test suites; the audit pins its exact value. Explicit
`--no-build-cache` remains required on every workflow Gradle command.

### Resolution-state trust boundary

#215 asks that the cache stay a transport optimization, never a trust input.
For artifact bytes this holds: the XML manifests remain the sole checksum
authority, the preflight and Gradle both enforce them, and no restored or
transported byte is accepted without its exact pin. Gradle's binary resolution
metadata (`metadata-2.107`) is the deliberate exception. The manifests do not
authenticate it. Without it, Gradle re-contacts registries for every module
even when all pinned files are present, so a raw-files-only cache does not
survive rate limiting.

Its provenance is restricted instead. A persistent entry can only be created by
a successful producer on a push to `dev` or `main`; GitHub cache ref isolation
prevents fork and PR merge-ref caches from reaching protected branches, and
run-local metadata never leaves its workflow run. Arbitrary PR code can still
affect its own run. A party able to write a protected-branch cache entry could
alter recorded resolution state. Strict verification still refuses any
artifact or metadata file without a pin, so such a change is limited to
already-pinned artifacts: for example, omitting a transitive dependency or
selecting another pinned version. The independent production verifier compares
the distribution against the producer's runtime classpath manifest; it does
not re-derive the dependency graph, so it would not detect such a change. This
is not protection against a compromised cache service or protected-branch
producer.

Adopting this boundary changes the #215 acceptance wording for resolution
state. It requires explicit owner acceptance and independent security review
before merge. A future way to remove this exception is committed dependency
locking, which would let the repository, not the cache, define the resolved
graph.

The hermetic fixture requires fewer restored than cold loopback requests,
proves offline resolution from a fresh restored home, and repeats the negative
checksum cases. Run-specific measurements belong in the PR or issue, not in
this design note. Full cold/warm six-job CI and comparable registry metrics
remain mandatory acceptance evidence.
