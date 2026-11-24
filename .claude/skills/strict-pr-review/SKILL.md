---
name: strict-pr-review
description: End-to-end strict review of a pull request in YuriiSokolenko/recursive-coder - fetch, verify claims, check repository rules and CI, post an English review comment where every finding is mandatory. Use for any review or re-review of a PR in this repository.
---

# Strict PR review for Recursive Coder

The reviewer's job is to block anything that is not finished. Every finding is
required. There is no "optional", "nit", "consider" or "approve with nits".

## 1. Get the PR (read-only)

Work in a separate clone, never in a working copy that has local changes.

```bash
gh api repos/YuriiSokolenko/recursive-coder/pulls/<N> \
  --jq '.base.ref,.base.sha,.head.sha,.commits,.title,.body'
git fetch --depth=200 origin pull/<N>/head:pr-<N>
git fetch --depth=200 origin dev
git log --oneline <base_sha>..pr-<N>
git diff --stat <base_sha>...pr-<N>
git diff <base_sha>...pr-<N>
```

- Use the API's head and base SHAs, not the ones quoted in the PR body or chat.
  If they differ, say so in the review.
- For a re-review, also diff the previous reviewed head to the new head, and
  check every earlier finding one by one.
- Read the code around each change as well as the diff: callers, sibling
  branches, the owning contract, and the tests for that behavior.

## 2. Verify every claim

Build a list of each claim in the PR body, commit messages and changed docs
(for example "all X migrated", "no behavior change", "pins unchanged", "tests
independent", "N categories"). Check each claim against the code or by running
a script. A claim that is false, unverifiable or broader than the change is a
finding.

## 3. Mandatory checks

Run all of these and record the result of each one:

1. **C3 pins and protected paths:** run the `c3-pin-check` skill.
2. **Vocabulary and constants:** for any PR that touches codes, categories,
   protocol strings or constants, run the `diagnostic-vocabulary-audit` skill.
3. **Purity:** `:core:contracts` has no I/O, clock, filesystem, process or
   network access (`git diff` for new imports of `java.io`, `java.nio.file`,
   `java.net`, `ProcessBuilder`, `Instant.now`, `System.`).
4. **Fail-closed:** every new decision path denies by default. Unknown or
   unparsable input is rejected, never treated as allowed.
5. **Authorization reuse:** role and path rules come from
   `ToolAuthorizationPolicy`, with no parallel allowlists.
6. **Protocol v1:** no silent change under `contracts/v1/`, in fixtures, event
   types or `tasks.json`. Kotlin vocabulary that mirrors a schema enum must be
   asserted against the checked-in schema in
   `SessionVocabularySchemaIntegrationTest` (or an equivalent schema oracle).
7. **Tests:** every new or changed JUnit class or method has an effective
   `@Tag` from `unit`, `integration` or `e2e`, and the tier matches what it
   touches (files, Git, HTTP, processes or Koog mean not `unit`). Behavior
   tests assert the specific denial code. Oracles are literals, not the
   constants under test. New behavior without a test is a finding.
8. **Docs:** English only (no Cyrillic), links resolve, wrapping matches the
   surrounding file, implemented and planned behavior are separated, and new
   `docs/architecture/*.md` files are linked from the README, `ARCHITECTURE.md`
   or `MODULES.md`. Inventory or audit docs must match the code exactly.
9. **History:** the branch is based on `dev`, and commit titles follow
   `type(#issue): summary`. Review-fix commits must be folded into the commits
   they fix before merge, or the PR is squash-merged.
10. **CI:** the head SHA must have all checks completed and successful.

    ```bash
    gh api repos/YuriiSokolenko/recursive-coder/commits/<head_sha>/check-runs \
      --jq '.check_runs[]|[.name,.status,.conclusion]|@tsv'
    gh api "repos/YuriiSokolenko/recursive-coder/actions/runs?head_sha=<head_sha>" \
      --jq '.workflow_runs[]|[.name,.status,.conclusion]|@tsv'
    ```

    For a failure, read the job log
    (`gh api repos/YuriiSokolenko/recursive-coder/actions/jobs/<id>/logs`) and
    turn the root cause into a finding. Pending CI means `Changes requested`.

Gradle cannot run in the review sandbox: the plugin portal returns 403. Do not
retry it. State this in the review.

## 4. Grade and write

- **Important:** wrong behavior, a security or fail-closed gap, a protocol or
  C3 risk, a false claim, a missing test for new behavior, or scope the PR
  claims but did not deliver.
- **Medium:** rule violations from CONTRIBUTING.md, incomplete consolidation,
  wrong test tier, inventory gaps.
- **Minor:** history, formatting and wording. Still required.

Each finding states: location (file:line or file plus count), what is wrong,
the rule or doc it breaks, and the exact fix. When an item legitimately cannot
be fixed in this PR (for example it needs a C3 change), the required action is
to open a tracked issue and link it from the relevant doc. The finding is not
dropped.

Use `template.md` in this skill folder for the comment. Verdict:
`Changes requested` if there is any finding or CI is not green, otherwise
`Approved`.

## 5. Post and clean up

```bash
gh api -X POST repos/YuriiSokolenko/recursive-coder/issues/<N>/comments \
  -F body=@<review-file> --jq '.html_url'
```

- Use REST only. `gh pr comment` and GraphQL return 403 in Claude sessions.
- Post the review once it is written. The repository owner's standing rule is
  to post without asking again.
- Report the verdict, the main findings and the comment URL to the requester,
  in the requester's language.
- Never push the `pr-<N>` branch. Clean up with
  `git checkout dev && git branch -D pr-<N>`.
