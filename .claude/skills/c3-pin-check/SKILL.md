---
name: c3-pin-check
description: Verify the six trusted C3 source pins in recursive-coder and list CODEOWNERS-protected files changed by a PR. Use in every PR review and before pushing any change that touches core/, infra/, app/, agent/, build-logic/ or .github/.
---

# C3 pin and protected-path check

The trusted C3 sources are pinned by SHA-256 in
`build-logic/.../architecture/ArchitectureGraph.kt`
(`approvedSourceSha256`). `checkArchitecture` enforces them in CI. This skill
verifies them without Gradle.

## Run

```bash
python3 -I .claude/skills/c3-pin-check/scripts/check_c3_pins.py \
  --repo <clone> --base <base_sha> --head pr-<N>
```

- A `FAIL` line, or a non-zero exit code, means a pinned source changed while
  its pin did not. That is an Important finding unless the PR is an explicit
  C3 change.
- `C3 CHANGE` means pinned sources or the pin inventory changed. Require:
  - the review record described in
    `docs/architecture/TRUSTED_C3_REVIEW.md`, updated in the same PR;
  - updated pins that match the new bytes;
  - independent behavioral oracles for the changed behavior;
  - owner review (see `.github/CODEOWNERS`).
- The `protected` list shows the CODEOWNERS paths the PR touches. Changes under
  `.github/`, `build-logic/`, `gradle/`, `*.gradle.kts`, `tools/` and the
  architecture tests are supply-chain changes: review them line by line, and
  call out any widened permission, new dependency, new network access or
  weakened check.

Literals that stay in pinned files are acceptable only when the inventory
records them as excluded pinned literals.
