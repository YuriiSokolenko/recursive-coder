---
description: Strictly review a recursive-coder pull request and post the review to GitHub
argument-hint: <PR number or URL>
---

Review pull request $ARGUMENTS in YuriiSokolenko/recursive-coder using the
`strict-pr-reviewer` agent from the verified base revision, following
AGENTS.md. If the loaded agent is not from that revision, follow the trusted
policy directly.

Requirements:
- Follow the `strict-pr-review` skill end to end. Run `c3-pin-check` on every
  PR, and `diagnostic-vocabulary-audit` whenever codes, categories, protocol
  strings or constants are touched.
- Every finding is mandatory. Do not mark anything optional, a nit or a
  suggestion. The verdict is `Changes requested` unless there are zero findings
  and all CI checks on the head SHA succeeded.
- For a re-review, check each previous finding against the new head and report
  which ones are fixed and which are not.
- Post the English review as a PR comment through the REST API. Then reply to
  me in my language with the verdict, the key findings and the comment URL.
