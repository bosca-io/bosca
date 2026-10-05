package bosca.git.model

import bosca.db.annotation.ColumnName
import bosca.db.annotation.DbMapper
import bosca.db.mapper.JsonbMapper
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * A job within a pipeline run. Each job runs on a single agent and
 * contains an ordered list of steps. Jobs can depend on other jobs
 * via [dependsOn] and can be parameterized via [matrixValues].
 */
@Serializable
data class PipelineJob(
    @Contextual val id: UUID = UUID.NIL,
    @Contextual @ColumnName("pipeline_run_id") val pipelineRunId: UUID,
    val name: String,
    val status: PipelineRunStatus = PipelineRunStatus.QUEUED,
    @ColumnName("runner_label") val runnerLabel: String = "default",
    @Contextual @ColumnName("agent_id") val agentId: UUID? = null,
    @ColumnName("matrix_values") val matrixValues: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap()),
    /** Artifacts this job declares it produces, with coordinates resolved at run creation. */
    @property:DbMapper(JsonbMapper::class)
    val artifacts: List<ArtifactDefinition> = emptyList(),
    /**
     * The artifacts this job requires before dispatch, as a serialized
     * `List<ArtifactRequirement>` with coordinates already resolved against the run context. The
     * scheduler reads these to gate claimability; empty = no gate.
     */
    val requirements: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    /**
     * The upstream pipeline runs this job requires before dispatch, as a serialized
     * `List<PipelineRequirement>` with correlation refs already resolved against the run context.
     * Gated by the same [requirementsSatisfiedAt] stamp as [requirements]; empty = no pipeline gate.
     */
    @ColumnName("pipeline_requirements") val pipelineRequirements: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonArray(emptyList()),
    /**
     * When the requirement checker last verified every requirement — artifact requirements against
     * the registry AND pipeline requirements against upstream run history — the claim query
     * dispatches a gated job only once this is set. Null while waiting (and for requirement-less
     * jobs, where the empty requirement arrays satisfy the claim predicate).
     */
    @Contextual @ColumnName("requirements_satisfied_at") val requirementsSatisfiedAt: OffsetDateTime? = null,
    /**
     * When a user explicitly opened the external requirement gate without waiting for the
     * configured artifact or upstream-pipeline requirements. This is distinct from
     * [requirementsSatisfiedAt] so exceptional execution remains visible after the job runs.
     */
    @Contextual @ColumnName("requirements_bypassed_at") val requirementsBypassedAt: OffsetDateTime? = null,
    /** Principal that requested [requirementsBypassedAt]. */
    @Contextual @ColumnName("requirements_bypassed_by") val requirementsBypassedBy: UUID? = null,
    /** Optional explanation supplied with the external-requirement override. */
    @ColumnName("requirements_bypass_reason") val requirementsBypassReason: String? = null,
    /**
     * The earliest requirement-timeout expiry ([created] + the smallest requirement timeout). A job
     * still unsatisfied past this fails loudly, naming its unmet coordinates. Null when ungated.
     */
    @Contextual @ColumnName("requirements_deadline") val requirementsDeadline: OffsetDateTime? = null,
    /**
     * The job's DEFERRED `if:` condition (failure routing) — one that references
     * `needs.*` or uses `always()`/`failure()`/`cancelled()`, so it cannot be decided at run
     * creation. Evaluated server-side once every dependency is terminal: true stamps
     * [conditionSatisfiedAt] (claimable even over failed deps), false marks the job SKIPPED.
     * Null for ordinary jobs, whose conditions were consumed at creation.
     */
    val condition: String? = null,
    /** The environment KEY this job targets, null for ordinary jobs. */
    val environment: String? = null,
    /**
     * Whether this job parks for human approval before dispatch — from the job's own
     * `approval: true` or its environment's policy, resolved at run creation. The claim query skips
     * an approval-required job until [approvedAt] is stamped; rejection fails the job.
     */
    @ColumnName("approval_required") val approvalRequired: Boolean = false,
    /** When the job was approved; an approval-required job dispatches only after this is set. */
    @Contextual @ColumnName("approved_at") val approvedAt: OffsetDateTime? = null,
    /** Who approved (profile id). */
    @Contextual @ColumnName("approved_by") val approvedBy: UUID? = null,
    /** The approver's optional comment. */
    @ColumnName("approval_comment") val approvalComment: String? = null,
    /**
     * When the deferred [condition] evaluated true — a deferred job is claimable only once this is
     * set. Null while waiting for dependencies to settle (and for non-deferred jobs).
     */
    @Contextual @ColumnName("condition_satisfied_at") val conditionSatisfiedAt: OffsetDateTime? = null,
    @ColumnName("depends_on") val dependsOn: List<String> = emptyList(),
    /**
     * The secret NAMES this job may resolve — the pipeline definition's `secrets:`
     * declaration, flattened at run creation. Empty means the definition declared none: resolution
     * falls back to the repository's secrets (legacy behavior) with environment-scoped ones
     * excluded unless the job is bound to their environment.
     */
    @ColumnName("secrets") val secretNames: List<String> = emptyList(),
    @ColumnName("timeout_minutes") val timeoutMinutes: Int? = null,
    @ColumnName("error_message") val errorMessage: String? = null,
    /**
     * Which execution attempt this row represents (run control). 1 for a first run;
     * incremented in place each time the job is re-run via re-run-failed — the row is reset to
     * QUEUED and re-executed, so a re-run replaces the failed attempt's results.
     */
    val attempt: Int = 1,
    /**
     * Durable ID of the request placed on the `kubernetes-jobs` queue for this attempt.
     * Null means this attempt has not yet been reserved for Kubernetes; ordinary-agent
     * eligibility is determined separately from the configured runner-label routing.
     */
    @Contextual @ColumnName("kubernetes_dispatch_id") val kubernetesDispatchId: UUID? = null,
    /**
     * Set only after the Kubernetes terminal result and all CI/run/agent side effects commit
     * together. A null value keeps this row eligible for crash-recovery reconciliation.
     */
    @Contextual
    @ColumnName("kubernetes_finalized_at")
    val kubernetesFinalizedAt: OffsetDateTime? = null,
    @Contextual val created: OffsetDateTime = OffsetDateTime.now(),
    @Contextual val started: OffsetDateTime? = null,
    @Contextual val finished: OffsetDateTime? = null
)
