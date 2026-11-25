# Contributing to Recursive Coder

## Language policy

**English is the sole language for repository-maintained documentation.** Write and review README files, architecture RFCs, design notes, configuration documentation, release notes, examples, issue descriptions, PR descriptions, and documentation comments in English.

Use English for new commit titles and descriptions. Keep this rule for generated documentation, diagrams containing text, and future website or help content. Existing source identifiers and standardized protocol literals must not be translated because wire compatibility takes precedence.

Before proposing a change, verify that edited documentation contains no non-English prose, that internal links still resolve, and that examples describe the implementation accurately. The CI checks for Cyrillic characters in maintained Markdown documentation as a mechanical regression guard; editorial review remains necessary for English-only quality.

## Independent product boundary

Keep task execution, session state, authorization, tool runtime, checkpoints, and evaluation owned by Recursive Coder. A third-party API, git host, IDE, or model endpoint may be configured as an optional integration, never as a mandatory orchestration dependency.

Documentation describing planned behavior must explicitly distinguish it from working functionality.

## Semantic constants and wire vocabulary

- Put shared Session Protocol vocabulary in `:core:contracts`, preferably next to the corresponding contract (for example, `SessionProtocol`). Define domain values in the owning domain, and keep implementation-only constants private or internal to the adapter or build tool that owns them.
- Reuse existing typed values or enums for closed domain sets; explicitly encode stable wire strings at boundaries and reject unsupported values according to the existing policy. A schema-reserved value does not imply runtime support.
- Extract repeated literals when they represent the same semantic value or invariant, not just because their spelling or number happens to match. Avoid generic constants containers and new dependencies across architectural boundaries.
- Keep independent schema examples, compatibility assertions, negative fixtures and test oracles literal so that unintended changes are visible. Human-facing prose, self-explanatory local values, and independently owned external API keys need no shared constant.
- Keep build and tooling vocabulary in its own ownership boundary; do not import production Kotlin definitions into Python or JavaScript verifiers. Review drift across languages using independent tests, not shared expected values.

See the [semantic vocabulary inventory](docs/architecture/SEMANTIC_VOCABULARY.md) for the staged cleanup tracked by #194.

## JVM test tiers

Every JUnit Jupiter test must have an effective `@Tag("unit")`, `@Tag("integration")` or `@Tag("e2e")`. Apply it to the test class for a uniform suite, or to individual methods when appropriate. Pure `unit` tests use only in-memory inputs and fakes; anything invoking a file, Git, HTTP, socket, subprocess or Koog belongs to a higher tier. A class tagged `integration` may give one method `e2e`; the focused integration suite excludes that method.

Before opening a PR, run `./gradlew checkTestTiers unitTest` and the guard's independent regression cases with `python3 -m unittest discover -s tests -p 'test_test_tiers.py' -v`. The original `./gradlew test installDist` remains unfiltered. See [test tier policy](docs/architecture/TEST_SUITES.md) for the supported JUnit annotations, diagnostics and static-check limitations.

## Development and documentation workflow

- Branch from `dev` and open PRs into `dev`; use `main` for intentional release integration.
- Every PR into `dev` resolves an issue. Open the issue first if none exists. Link the primary issue in the PR description with `Closes #<issue>`.
- Title a PR into `dev` `#<issue> <issue title>`, using the exact current title of the primary issue, for example `#201 Consolidate shared diagnostic and denial code vocabulary`. If the PR resolves additional issues, list them separately with `Closes #<issue>`. Intentional release PRs from `dev` into `main` are exempt from this issue and title convention; all other review requirements still apply.
- Use squash or merge commits for integration into `dev`, not rebase merges. The resulting commit subject is the PR title followed by GitHub's ` (#<pr>)` suffix, where `<pr>` is the pull request number. The repository defaults `squash_merge_commit_title` and `merge_commit_title` are `PR_TITLE`. The person merging checks the proposed subject before merging and verifies the resulting subject afterward. Branch commit titles may use short `type: summary` or `type(#issue): summary` titles. The active `dev` ruleset allows only merge and squash; verify this setting before merging.
- Distinguish **implemented and CLI-integrated**, **implemented standalone**, and **planned** features. In particular, the Linux trusted write runtime is not part of the read-only `run` / `tasks` Session Runtime; five-role orchestration is reachable only through the gated fake-only `workflow` command, and live-model Koog orchestration is planned.
- Verify the nine product modules against `settings.gradle.kts`, build versions against the version catalog, and CI claims against `.github/workflows/ci.yml`.
- CI runs all six jobs on GitHub-hosted `ubuntu-24.04` since merged [PR #71](https://github.com/YuriiSokolenko/recursive-coder/pull/71). Fork PR code executes on GitHub-managed ephemeral VMs, never on private N150 hardware. Tests remain offline from any live inference provider. Required sandbox E2E must not be skipped.
- For documentation PRs, check relative links, versioned commands, security/portability claims, roadmap status and canonical protocol compatibility. A passing CI run does not replace editorial review.
