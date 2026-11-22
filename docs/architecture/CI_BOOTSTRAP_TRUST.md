# CI bootstrap supply-chain trust (issue #144)

The six required CI jobs run on GitHub-hosted `ubuntu-24.04`. Workflow actions use reviewed immutable 40-hex Git commit SHAs, with human-readable version comments. Each job runs the independent `python3 -I tools/check_ci_bootstrap.py --audit` check. The `contracts` job runs offline negative fixtures for changed/mutable action refs and altered Gradle archive/checksum inputs.

## Authenticated Gradle executable

The four Gradle-consuming jobs no longer rely on `gradle/actions/setup-gradle`. Each downloads `gradle-9.8.1-bin.zip` over HTTPS to a fresh runner temporary directory, validates the ZIP against the independently reviewed SHA-256 below, and only then unpacks/executes the distribution:

`dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`

Source: [official Gradle 9.8.1 release checksums](https://gradle.org/release-checksums/), binary-only distribution. The trust root is committed; the code never accepts the downloaded adjacent `.sha256` file as an authority. On CI `./gradlew` requires the `RECURSIVE_VERIFIED_GRADLE` absolute executable path, and refuses any implicit locally installed or cached Gradle fallback. The same verified directory is prepended to later steps' PATH for standalone Gradle fixture tools; the isolated production verifier calls the binary by absolute path. Local developer builds continue using the pre-release system-Gradle launcher; a self-contained Wrapper is tracked separately in #12.

The dependency SHA-256 manifests apply only after Gradle itself is authenticated. This change leaves all six CI job names and the independent production distribution verifier intact.

## Remaining hosted-runner and JDK trust boundary

We trust GitHub's runner image, execution and artifact services, hosted-machine isolation, implementation bytes at the pinned action commit SHAs, and GitHub file-command machinery. The workflow still trusts the Temurin JDK 21 selected by the pinned `actions/setup-java` (and Python 3.12 / Node 22 installers where used). JDK, Python and Node *versions* are selected, not independently authenticated byte-for-byte by a reviewed toolchain-archive hash: action pinning is not toolchain archive attestation. HTTPS and Gradle's upstream distribution infrastructure are still required for availability, while the pinned Gradle digest ensures content integrity. No C3, tool, model, resume or side-effect authority changes are included.
