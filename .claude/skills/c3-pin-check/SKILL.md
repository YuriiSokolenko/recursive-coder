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

Extract the helper from the verified base revision as described in the strict
review skill, or use verified CI evidence under that skill's rules.

```bash
python3 -I <trusted-helper-dir>/check_c3_pins.py \
  --repo <clone> --base <base_sha> --head <head_sha>
```

- A `FAIL` line or non-zero exit code means pin verification failed. A
  mismatch, missing file, or invalid pin inventory is an Important finding.
  Even explicit C3 changes must have valid, matching pins before approval.
- `C3 CHANGE` means pinned sources or the pin inventory changed. Require:
  - the review record described in
    `docs/architecture/TRUSTED_C3_REVIEW.md`, updated in the same PR;
  - updated pins that match the new bytes;
  - independent behavioral oracles for the changed behavior;
  - owner review (see `.github/CODEOWNERS`).
- The `protected` list conservatively includes paths protected at either the
  base or head revision; removed rules cannot hide an affected file. Changes
  under `.github/`, `build-logic/`, `gradle/`, `*.gradle.kts`, `tools/` and the
  architecture tests are supply-chain changes: review them line by line, and
  call out any widened permission, new dependency, new network access or
  weakened check.

Literals that stay in pinned files are acceptable only when the inventory
records them as excluded pinned literals.
