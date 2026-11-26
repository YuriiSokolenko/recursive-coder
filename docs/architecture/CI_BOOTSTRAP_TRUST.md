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

Removing `setup-gradle` also removes its dependency caching. CI intentionally uses a fresh Gradle download and dependency state in each hosted job; that increases network traffic and run time in exchange for a smaller bootstrap/cache trust surface. Keep dependency-verification checks enabled. Any Gradle version upgrade requires an independently reviewed new distribution checksum and a clean six-job run.

## Independent review evidence (issue #231)

The proposed manual `trusted-review.yml` workflow is separate from the six required production CI jobs. It is intended for GitHub-hosted execution from the owner-reviewed version on protected `dev`, not from a PR branch. The workflow and its runner are independently pinned by the CI drift audit; changes require owner review and independent verification. The audit is not an adversarial trust boundary.

After initial independent verification and activation on `dev`, open Actions > Independent trusted review evidence > Run workflow, select `dev`, and supply the exact base SHA, head SHA and open PR number. Use PR number `0` for a pre-push candidate that GitHub can serve by SHA; it must descend from the supplied base on `dev`. Candidate mode does not verify a PR's metadata and is not a substitute for reviewing the final PR pair. If GitHub refuses an unattached commit fetch, do not treat the run as evidence.

The workflow uses read-only API permissions and a fresh hosted VM. Only the workflow revision and a clean detached trusted-base worktree are checked out. The candidate is read as Git objects. Helpers are extracted from the base, their Git blob IDs checked, and their SHA-256, exit status, stdout and stderr emitted as JSON log records. Git and helpers run without the API token, inherited Git configuration or hooks. Temporary data is removed on exit; the hosted VM is disposable. No build, Gradle tasks, candidate hooks or agent discovery run in this job.

Reviewers must independently obtain the current PR pair via GitHub, read the actual run metadata and full job log, verify the workflow event is manual dispatch on `dev`, verify the workflow SHA is an independently reviewed revision of the trusted execution chain, confirm all three helper hashes against the exact base, inspect the complete output and exit codes, and require the final complete record for the exact pair. A skipped job, missing record, failed helper, moved PR, or candidate-controlled workflow is not evidence. The vocabulary scanner's zero exit does not imply no findings. The checkout checker does not attest an AI provider's external discovery configuration. The human or AI reviewer must still apply the entire strict review policy and check all six normal CI statuses.

This workflow cannot independently approve its own introduction or a change to its execution chain. Bootstrap requires the existing independent trusted-base route and explicit owner review before activation. Until that is established, this is a candidate mechanism, not an accepted substitute for missing evidence. Demonstration against #229 and reviewer access to its evidence are tracked separately in [#233](https://github.com/YuriiSokolenko/recursive-coder/issues/233), after #231 is implemented and independently approved. No policy exception or private runner is authorized.
