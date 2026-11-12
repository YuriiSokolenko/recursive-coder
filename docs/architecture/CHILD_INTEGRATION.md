# Verified recursive child integration (issue #79)

**Implementation status:** The in-memory scheduler has an opt-in, host-backed
artifact-integration gate. The preexisting scheduler constructor remains
compatible when no `RecursiveChildIntegrationHost` is supplied. This is one
part of issue #79, not the completed installed-CLI five-role acceptance.

## Trusted boundary

A `RecursiveStageExecutor` can propose a child and report its execution
outcome, but it cannot construct an accepted result. Only the separately
constructed host port supplies the accepted artifact-ID set, child manifests,
current file bytes, and reserved parent manifests. The scheduler independently
SHA-256 hashes current bytes. The host must validate trusted write/check
receipts, approval decisions, and live workspace authority before returning
those facts. A model response, a manifest with `ACCEPTED`, or a successful
child is **never** sufficient alone.

After a child finishes, its parent calls `integrateChildren()`. The pure
policy walks a deterministic sibling topological order (lexical tie-breaking),
requires all direct children to have succeeded, checks exact child stage/role
provenance, accepted/frozen manifests, independent host acceptance, live
digests, exact write scope, immutable paths, and conflicts with both siblings
and existing parent artifacts. Nested integrated manifests are rolled upward,
without losing the actual producer identity; they are rechecked against
authoritative child manifests and bytes. There is no partial merge. Rejected
or missing evidence yields a typed denial, not a silent fallback.

With the host port installed, a parent with children cannot finish successfully
until it integrated all of them. It also cannot succeed after new children were
admitted or after an accepted manifest changed between integration and terminal
commit. A failure in any child blocks the parent and its dependent siblings.
Explicit repair/replan is the only permitted fallback; this policy does not
automatically convert a refusal into acceptance.

The `INTEGRATED` event records the parent/child IDs, role origin and child
request ID. It is metadata-only and carries no contents or credentials.
The existing `RecursiveEventSink` is best-effort telemetry. Production
persistent Session Protocol v1 correlation and a full installed CLI workflow
are still required for completing #79.

## Verification

- `RecursiveChildIntegrationPolicyUnitTest`: dependency determinism,
  rejection/failed children, approval, digest, frozen ownership, path/ID
  conflicts, malformed dependencies, read-only Reviewer.
- `RecursiveChildIntegrationSchedulerIntegrationTest`: two-level SDD and
  Developer delegation with transitive integration, per-child correlated
  integration events, mandatory integration, revocation and stale file
  detection, and read-only Reviewer.

The scheduler and this documentation do not claim physical write authority,
production Koog dispatch, durable crash-recovery, or checkpoint resume.
Those boundaries belong to host adapters and the remaining #79/#10 work.
