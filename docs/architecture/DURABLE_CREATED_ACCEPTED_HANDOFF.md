## #104: trusted-host durable `created → accepted` handoff

`DurableCreatedAcceptedHandoff.publish` is an opt-in, host-only primitive in
`:infra:journal`, deliberately **not** wired into `DefaultRunSessionUseCase`
or the CLI. The host supplies an independently verified and already durable
`created` checkpoint (C1), matching trusted `CheckpointHostContext`, a
read-only `ResumeHostAttestation` with expected fence generation, and the
single canonical `created → accepted` event from its trusted event emitter.

The method rejects an unsafe phase/edge before admission. It then obtains a
fresh OS-backed resume lease, checks the admitted C1 identity, reopens the
exact checkpoint-bound durable ledger, calls `appendFenced` and
`saveFromOpenLedgerFenced` on the **same** writer, and returns only after C2
is durably committed. It closes the writer before retiring the resume lease.
It never rewrites or repairs an uncertain append/checkpoint tail. A rejected
or uncertain publication must be investigated, not automatically retried.

This primitive does **not** authorize execution after the handoff. In
particular, the current runtime emits pre-acceptance model/tool events, and
no runtime/receipt publications are generation-fenced. Allowing runtime work
after C2 would make a previously safe `accepted` checkpoint misleading
after a crash. Keep this opt-in helper disconnected from production session
execution until the separately reviewed #104/#105 host integration can
atomically guard entry into `running` and subsequent side effects.

The offline integration test covers real C1 → fenced event → durable C2,
writer/lease retirement, next-generation admission/reopen, and early denial
without a new fence. Slow-fsync races, crash windows, actual runtime-path
error redaction, model/child/receipt fencing and recovery remain outstanding
in #104/#105. The separate filesystem allowlist hardening remains PR #122.
