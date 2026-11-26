# Dependency trust boundary (#142)

Gradle dependency verification is **mandatory** in strict mode. The root build
reads `gradle/verification-metadata.xml` for plugin resolution, included
`build-logic`, and production/test dependencies. Standalone
`./gradlew -p build-logic ...` reads
`build-logic/gradle/verification-metadata.xml` instead.

The two files are reviewed security inputs, not build outputs. Normal CI must
never run `--write-verification-metadata` or auto-accept missing checksums.
`tools/check_dependency_verification.py` rejects missing, malformed, or
weakened manifests; `--negative-fixtures` separately tests a corrupted
cached JAR and an unapproved new Maven artifact before Java compilation.

## CI module-cache transport (#215)

CI transfers raw module files and version-matched Gradle binary resolution
metadata into a fresh Gradle user home. The producer alone restores and
saves persistent cache entries; saving requires a successful push to
`dev` or `main`. PRs and dispatch runs never save persistent entries.
Dependent build jobs receive a separate current-run artifact; the independent
production verifier never consumes it.

The restore preflight rejects unpinned raw bytes and unsafe filesystem
entries; Gradle still enforces strict verification for resolved artifacts.
Binary resolution state is not authenticated by the manifests. It relies on
protected-ref cache provenance or same-run producer provenance, as described
in the [CI bootstrap trust boundary](architecture/CI_BOOTSTRAP_TRUST.md#downloaded-module-cache-215).
No other Gradle state or build outputs are transported. The producer resolves
all JVM test and test-fixture runtime artifacts and repeats the same resolution
in a fresh restored Gradle home offline before publication; root and standalone
build-logic are both checked.

The hermetic fixtures exercise fresh cold, restored online and restored
offline Gradle homes, assert reduced loopback HTTP requests, and reject
corruption and unapproved dependencies.

## Initial baseline: independent official-repository comparison

A one-shot GitHub-hosted, credential-free verifier compared all **671 unique
SHA-256 entries** in both manifests with official HTTPS Maven Central and
Gradle Plugin Portal publication bytes and checksums on November 22, 2026.
[Verification run and full logs](https://github.com/YuriiSokolenko/recursive-coder/actions/runs/45060981924):

- **450** entries matched directly against published SHA-256 sidecar files.
- **213** matched downloaded artifact bytes against the pinned SHA-256 and
  the official published SHA-1 sidecar.
- **8** Kotlin multiplatform JARs (Clikt, Mordant, Colormath) use logical
  artifact names different from published URLs; their pinned `.module`
  metadata was independently verified, its declared SHA-256 matched the pin,
  and the published JAR bytes matched SHA-256.
- **0 mismatches / 0 unresolved** at this baseline.

These checks compare against the bytes served over HTTPS by official artifact
repositories. They do **not** establish that a publisher's account, signing
keys, repository or the build infrastructure was uncompromised. Treat any
future checksum change as a new trust decision requiring manual review.
The one-shot workflow was deleted after verification and is not a mechanism
for auto-updating either manifest.

## Changes requiring security review

1. Change dependency declarations and their versions in a dedicated PR.
2. In a disposable, credential-free environment with a **new**
   `GRADLE_USER_HOME`, resolve all production, runtime, test and plugin
   configurations for the root build **and** the standalone `build-logic`
   build. Use `--write-verification-metadata sha256` for that controlled
   bootstrap only.
3. Compare each generated artifact to an independently downloaded copy from
   the intended official Maven Central/Gradle Plugin Portal coordinate.
   Verify publisher signatures or official checksums wherever available.
   A checksum written by Gradle from an already-populated cache does **not**
   establish independent provenance.
4. Manually review the *exact* additions/removals in **both** manifests;
   investigate changed checksums without automatically accepting them.
5. Run strict CI, the negative cache fixtures, configuration-cache cold/warm
   tests, JVM/E2E, installed CLI verifier and an independent security review.
   Do not merge checksum updates without that review.

The manifest protects dependencies handled by Gradle's dependency
resolution engine. It does not authenticate the Gradle distribution itself,
actions used to bootstrap the toolchain, arbitrary executable initialization
scripts, or runtime side effects. No changes to C3 authority or resume
semantics are implied.

## Required `dev` status checks

The `dev` branch ruleset must require **all six** statuses from
`.github/workflows/ci.yml`:

- Architecture boundaries and pure unit tests
- Build isolated production verifier input
- Independent production distribution verifier
- Full JVM integration and sandbox E2E
- Installed CLI E2E on independently verified distribution
- Versioned session contracts

The branch ruleset should also require PR review, block direct pushes, and
ensure changes to checksum manifests and `build-logic` are reviewed. These
GitHub administrative settings cannot be enforced solely by committing a
CODEOWNERS file.
