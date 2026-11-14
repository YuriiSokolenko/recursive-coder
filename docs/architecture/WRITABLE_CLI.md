# Opt-in writable five-role and recursive CLI (Issue #79)

The installed CLI now has a separate **offline-only** `workflow` command.
`recursive-coder run REQUEST` remains SessionRequest v1, **read-only**, and
`recursive-coder tasks` retains its original scheduler semantics. No default
dependency-injection module grants write access.

## Invocation

```text
recursive-coder workflow /absolute/plan.json \
  --approvals /absolute/approvals.json --enable-stage-writes
```

All three inputs are mandatory; omitting the flag or approvals fails before
opening any stage. There is no interactive or implicit authorization fallback.

The workflow plan is UTF-8 JSON (version 1, maximum 64 KiB), with exactly:

- `sessionId`: a valid host identifier (short enough to fit suffixed stage IDs).
- Optional `eventsPath`: absolute path of a **new** JSONL Session Protocol v1
  journal outside the isolated checkout and lock scope. Existing files are
  never overwritten. The output is a correlated, metadata-only audit stream
  for five role invocations, preparation acceptance and Reviewer decisions.
  An unavailable journal prevents stage execution (`EVENT_JOURNAL_UNAVAILABLE`).
  A journal append/close/checksum failure during execution prevents a
  **successful outcome from being published**, but stages may **already have
  written to or committed the checkout** before the failure is detected.
  In this case the CLI returns `reconciliation_required` with
  `EVENT_JOURNAL_UNCONFIRMED`; this is not a rollback. A stopped/denied
  stage result is also upgraded to this uncertain outcome if the journal
  cannot be verified. Crash recovery remains #10.
- `workspace`: `rootPath`, `headSha` (exact 40-digit lowercase Git HEAD),
  `branch`, `lockDirectory` (absolute external lock scope).
- `artifacts`: keys `SDD`, `ATDD`, `TDD`, `DEVELOP`; values each contain
  `id` and permitted, distinct repo-relative `path`.
- `instructions`: nonempty text for each of the five role names, including
  `REVIEW`.
- `developerTargets`: one to seven entries, each with `path` and optional
  exact preimage `expectedSha256` (null/omitted means create).
- `fakeProposals`: deterministic offline fixture response text for
  `SDD`, `ATDD`, `TDD`, `DEVELOP`. The Developer response must follow
  the existing strict `{"files":[{"path":"...", "content":"..."}]}` proposal
  grammar. The Reviewer response is constructed dynamically from the trusted
  check/write IDs in the read-only Reviewer prompt.
- `fakeReviewerVerdict`: `ACCEPT`, `REJECT` or `NEEDS_HUMAN`.
- `check`: a host/operator-chosen absolute `executable` and string
  `arguments` (at most 16), run in the existing bubblewrap sandbox as
  `offline-check` after each write; `/usr/bin/true` cannot be used.
  `git_diff_check` is also always required.
- `limits`: positive `maxTokens`, `maxDurationMillis` and
  `deadlineSeconds` within the CLI's bounded ranges. If recursive work is
  enabled, optional `maxDepth` (default 1), `maxChildren` (default 1),
  `maxToolCalls` (default 6) and `childTokens` (default 500) apply to each
  independently bounded origin; a depth of 0 or insufficient budget denies
  child admission rather than silently omitting the work.
- Optional `recursiveChildren`: **both** `SDD` and `DEVELOP` checkout records
  are mandatory when recursion is enabled. Each record provides absolute
  `rootPath`, `headSha`, `branch`, `lockDirectory`, and a host-selected
  `requestId`, optionally `maxTokens` for a stricter per-origin child
  token grant (otherwise the shared `limits.childTokens` is used). Both
  SDD and future Developer admissions are preflighted at the SDD hook
  before either child writes; Developer is re-authorized against its
  real running parent lease before executing. Each checkout must be a
  separately provisioned clean Linux
  Git checkout at the **same initial HEAD and branch** as the parent, with its
  own isolation marker and disjoint external lock scope. The child workspaces
  may not overlap each other, the parent checkout, plan/approvals or journal.
  Offline negative-fixture fields are `dependsOnSelf` (boolean) and, for
  the Developer child only, `outputArtifactId` (tests an authenticated
  cross-origin ID collision). Both are bound by the approval plan SHA-256.

The separate approvals file also has version 1 and **exactly** `planSha256`
(the SHA-256 of the plan file's raw bytes) and `approvedSha256` (a map of
all four write-role names to the **exact** expected output SHA-256 values).
For the Developer, the approved output is the generated implementation
manifest, not just an unverified model source. The candidate role, artifact ID,
path and digest must all match the independently provisioned approval file;
otherwise the trusted host rejects it.

The plan hash covers `fakeReviewerVerdict`: changing that fixture
invalidates the approvals. There is no separate signed Reviewer verdict;
this coupling is only for fake-mode tests and is not a live-provider design.
The callback is local and noninteractive: it is **not** a signature, user
identity assertion or human approval workflow.

## Checkout and trust boundary

Run only on Linux with `/usr/bin/git` and `/usr/bin/bwrap` configured,
in a **dedicated, clean, isolated repository checkout** with a real `.git`
directory. The operator must place the literal UTF-8 marker
`isolated-workflow-v1\n` at
`<checkout>/.git/recursive-coder-isolated-workflow`.
This is a deliberate operator assertion, **not** a sandbox of the workspace;
isolate checkout access from untrusted same-UID processes separately.
Canonical path checks and subsequent file use are not atomic: a concurrent
same-UID actor can swap filesystem entries after inspection. These checks do
not replace exclusive control over the isolated checkout and external inputs.

The plan, approval file, and shared lock directory must live **outside** the
checkout, even through symlinked parent directories. The CLI resolves real
locations, including the existing parent prefix of a new lock directory.
The CLI process's working directory cannot be **inside** the checkout.
The Linux production host checks the exact HEAD, branch, local Git
configuration and pristine index/worktree before beginning. The host owns
StageGraph transitions, HMAC-backed receipts, accepted artifacts, frozen
digests, Git handoffs, and final independent Reviewer acceptance. Neither the
fixture engine nor the operator JSON can create a trusted receipt or bypass
the protected-spec/test write policy.

On successful execution, the command reports `{"outcome":"completed",
"code":"SUCCEEDED"}` and exits 0. Stopped/rejected workflows exit 2.
Pre-execution denials exit 2 with JSON `{"outcome":"denied","code":"..."}`
and fixed, non-sensitive codes such as `APPROVAL_MISMATCH`,
`MARKER_MISSING`, `CONFIG_INSIDE_CHECKOUT`, `LOCK_OVERLAPS_CHECKOUT`,
or a typed Git baseline code. Input, paths, check output and prompts are
never reflected into diagnostic messages.
A host-uncertain state requiring out-of-band reconciliation exits 1, never
automatically retries, and **may leave uncommitted workspace changes**.
An exit 1 **without a JSON body** can instead mean an uncaught JVM-fatal
`Error`; treat the checkout as uncertain and reconcile it independently.
Do not reuse this checkout after an uncertain outcome without independent
reconciliation. A journal file is exclusively created with `CREATE_NEW`.
If an attempt created a file before failing, it is deliberately **not**
deleted or overwritten (even when journal construction fails after open).
Close the sink, preserve it as diagnostic evidence, and provision a **new
journal path** on retry after independently reconciling any uncertain
workspace state. Invalid, relative, overlapping, or pre-existing journal
paths yield `INVALID_EVENT_JOURNAL_PATH`; a failed open yields
`EVENT_JOURNAL_UNAVAILABLE`.

## Test and scope

`tests/test_cli_writable_workflow.py` invokes the **installed distribution**,
not an in-process shortcut. It covers all five roles with a real Git worktree
and sandbox checks, a clean final baseline, missing opt-in, stale approvals,
dirty checkout, independent Reviewer rejection and protected test paths.
It additionally checks missing approvals, markers, canonical paths (including
symlinked parents), wrong HEAD/branch, per-artifact digests, invalid checks,
`NEEDS_HUMAN`, and genuine post-write failure requiring reconciliation.
Journal acceptance additionally exercises unavailable files, relative/NUL/
checkout/lock-overlapping paths, loss of the on-disk journal after events
were emitted, and the resulting `EVENT_JOURNAL_UNCONFIRMED` decision.
The GitHub `kotlin` job runs these cases after `installDist`. Missing bwrap
or user namespace support in CI is a **failure**, not a silent skip.

The offline provider uses the existing `FakeModel` capability handshake plus
fixed per-role proposals; it is **not** a production LLM completion provider.
The role usage is fixture data, not measured token consumption.

## Authenticated recursive opt-in

When `recursiveChildren` is present, the same installed `workflow` command
runs child SDD while the real parent SDD writer lease is active, and runs an
independent full five-stage child workflow while the real parent Developer
lease is active. The latter preauthorizes only the policy-derived Developer
child stage identity, after its genuine SDD/ATDD/TDD prerequisites. No
child is given the parent checkout as its writable workspace.

The production Linux child hosts perform their own TrustedWriteRuntime
writes and mandatory Git/sandbox checks, retain HMAC-backed receipts, and
bind each result to its derived child graph/stage/request identity. The
`RunTwoOriginRecursiveChildrenUseCase` then independently admits, charges
and integrates both sources through authenticated child receipt adapters.
Missing approvals, cycles, depth/child/token/tool limits, conflicts and stale
evidence fail closed. A denial of Developer's preflight occurs before SDD
writes. An unconfirmed child execution requires out-of-band reconciliation:
its physical checkout may already have changed; cancellation is propagated
rather than treated as a normal model denial. FakeModel usage is *not* provider-measured: the
recursive scheduler charges the entire allocated child token cap and
counts the genuine write plus required checks.

The parent must write the same approved artifact bytes through its **own**
trusted stage tools. It never copies child checkout files into the parent
behind the TrustedWriteRuntime gate. Before SDD→ATDD, and before
Developer→Reviewer and the final successful graph transition, the wrapper
rechecks both sides' authenticated manifests, declared paths, output digests
and full write postimages, plus the accepted two-origin integration.
The independent Reviewer gate remains authoritative; a successful child
alone cannot approve the parent. The same optional Session Protocol v1
journal correlates parent role, nested child role, child admission, usage,
integration and Reviewer events; missing/dropped journal bytes still
prevent publication of success. Journaling never records model prompts,
source bytes, secrets or tool output.

The installed regression suite includes successful five parent roles, both
nested origins, a clean final Git baseline and correlated events; cycle,
depth/token ceilings, rejected Reviewer, protected test paths and
cross-origin artifact ID conflict. These are deterministic offline fixtures
only, **not** live-model integration or a full isolation guarantee against
same-UID adversarial processes. No checkpoint/restart after uncertain
writes (#10), external issue tracker, CI coordinator or production API is
introduced. Keep issue #79 open until the current branch passes all required
CI gates and a security review.
