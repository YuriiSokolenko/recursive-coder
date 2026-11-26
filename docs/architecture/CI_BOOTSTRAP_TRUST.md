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

## Downloaded module files cache (#215)

Gradle distributions still use the fresh authenticated ZIP bootstrap above.
Three jobs restore only a staged copy of
`caches/modules-2/files-2.1`, in fresh isolated Gradle user homes.
The independent production distribution verifier does not restore it.
The pinned `actions/cache/restore` and `actions/cache/save` entry points use
the exact same key: OS, architecture, Gradle version, both verification
manifests, the actual build-logic version catalog, settings and build scripts,
and root Gradle properties. Save uses the primary key computed at restore time,
not a hash recomputed after compilation. No restore-keys or partial hits are
consumed.

The raw downloaded files are checked against the union of the reviewed SHA-256
pins before copying them into the fresh Gradle user home. Unpinned restored
bytes, links and unexpected file layout fail closed. Gradle independently
enforces strict coordinate-specific verification during normal resolution.
The staging filter discards unpinned files on export. Serialized resolution
metadata (`metadata-*`), transforms, compiled scripts, configuration cache,
project state, executable distributions and build outputs are not transported.
In particular, serialized metadata is not itself authenticated by the XML pins;
it is deliberately rebuilt from the official repositories. This choice may
still require network metadata and checksum requests on warm runs.

Only the successful producer job on a `push` to `dev` or `main` can save
a cache, on an exact-key miss, after the production build and additional
test compilation dependency population. PRs and manual dispatch restore only.
A fork can change its own workflow, but GitHub's cache ref isolation prevents
its PR merge-ref cache from becoming a protected-branch cache. Cold PRs cannot
seed a shared cache. Later jobs can restore a producer's saved entry; any
missing test-runtime dependencies are resolved normally. A cache is not a
dependency authority or a guarantee of offline availability.

Initial Gradle plugin resolution retries at most twice after observed HTTP
429/5xx responses, with 5 and 15 second delays; verification failures are
not retried. The major direct Gradle invocations report observed Gradle
HTTP GET/HEAD operations; this is not a packet-level count and excludes
unwrapped fixture subprocesses and transport-level redirects. Cold and warm
evidence, including the loopback corruption and unapproved-artifact fixtures,
must be recorded before claiming the issue's performance acceptance criteria.
Keep strict verification and `--no-build-cache` enabled. Any Gradle version
upgrade requires an independently reviewed new distribution checksum and a
clean six-job run.
