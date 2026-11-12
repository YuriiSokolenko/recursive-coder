package ai.yuriilab.recursivecoder

/** Host-side acceptance is independent of every model-generated response. */
enum class PreparationApproval { ACCEPT, REJECT, HUMAN_REVIEW_REQUIRED }

/** Authoritative graph and approved artifact IDs returned by a completed host CAS. */
data class PreparationStageSnapshot(
    val graph: StageGraph,
    val acceptedIds: Set<ArtifactId>
)

/** Verified evidence and original model usage stored with a pending RUNNING/DRAFT manifest. */
data class PreparationPendingApproval(
    val validation: StageValidationEvidence,
    val usage: StageRoleUsage
)

/**
 * A single-use host-owned generation. Never manufacture this from model output.
 * `pendingApproval` is only populated by the trusted host after a previous
 * `finishStage(..., pending = evidence)` committed RUNNING/DRAFT.
 */
data class PreparationStageLease(
    val generation: String,
    val stageRunId: StageRunId,
    val graph: StageGraph,
    val acceptedIds: Set<ArtifactId>,
    val authority: StageToolAuthoritySource,
    val pendingApproval: PreparationPendingApproval? = null
)

/**
 * Host-only CAS boundary, implemented by a trusted adapter (not the agent).
 *
 * beginStage checks expectedGraph + expectedAcceptedIds against authoritative
 * state, accepts a CREATED or READY stage, performs the legal CREATED -> READY
 * transition when needed, then atomically records RUNNING and issues one
 * active generation. Exactly one concurrent claimant may succeed.
 *
 * A null begin result is a definitive rejection without state change; an
 * exception is an UNKNOWN result and the caller MUST reconcile with the host.
 *
 * current/finishStage/abortStage fence by active generation. finishStage
 * MUST independently authorize a transaction against its authoritative graph,
 * not simply persist the caller's proposed graph or accepted IDs. It MUST
 * reject an invalid or stale transition without consuming the lease:
 *   - Only the leased stage and at most its own single output manifest may
 *     change; all other stage nodes, manifests and graph-level fields remain
 *     byte-for-byte/value-identical to the authoritative snapshot.
 *   - Accepted IDs are unchanged except for the one leased output ID added
 *     on an ACCEPTED transition. Never remove IDs or accept arbitrary IDs.
 *   - ACCEPTED/FROZEN requires the host's own ACCEPT decision for the exact
 *     candidate identity, path, revision and SHA-256 under this lease; agent
 *     text, caller arguments or a prior lease cannot grant acceptance.
 *   - A draft may remain RUNNING only with a single DRAFT/EDITABLE output and
 *     the `pending` evidence+usage; no pending evidence may accompany a
 *     terminal transition. Missing or surplus pending evidence is invalid.
 * Only after all checks pass may it atomically persist graph + accepted IDs,
 * revoke tool authority and consume the generation. If it commits RUNNING/DRAFT,
 * `pending` MUST be stored in the same atomic operation and returned via
 * beginHumanApproval. A rejected finish leaves the lease active for an
 * authorized abort or retry.
 * Supplying pending on other transitions, or omitting it for RUNNING/DRAFT, is
 * invalid. beginHumanApproval atomically leases a persisted pending draft.
 *
 * abortStage MUST use authoritative host state, not lease.graph (which may be
 * malformed), to atomically transition the active stage to FAILED/CANCELLED,
 * revoke authority and consume the generation. A stale lease is not reusable.
 * An exception during finish/abort has UNKNOWN outcome: caller must reload.
 *
 * approve is a PROMPT, bounded host decision, not a blocking wait for human
 * input; interactive workflows must promptly return HUMAN_REVIEW_REQUIRED.
 * The host may remember a revocable, generation-fenced approval for finishStage
 * authorization, but approve MUST NOT commit irreversible external effects or
 * accepted state before finishStage succeeds. Late responses after a timeout
 * must be discardable or idempotent; a timed-out approval cannot implicitly
 * mutate accepted IDs or freeze an artifact.
 */
interface PreparationStageHost {
    fun readBytes(path: String): ByteArray?
    fun beginStage(
        expectedGraph: StageGraph, stageRunId: StageRunId,
        expectedAcceptedIds: Set<ArtifactId>
    ): PreparationStageLease?
    fun beginHumanApproval(
        expectedGraph: StageGraph, stageRunId: StageRunId,
        expectedAcceptedIds: Set<ArtifactId>
    ): PreparationStageLease?
    fun current(lease: PreparationStageLease): Boolean
    fun finishStage(
        lease: PreparationStageLease, updatedGraph: StageGraph,
        updatedAcceptedIds: Set<ArtifactId>,
        pending: PreparationPendingApproval? = null
    ): Boolean
    fun abortStage(
        lease: PreparationStageLease, terminal: StageState
    ): PreparationStageSnapshot?
    fun approve(
        candidate: StageArtifactManifest, evidence: StageValidationEvidence,
        usage: StageRoleUsage
    ): PreparationApproval
}
