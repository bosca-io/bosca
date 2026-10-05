package bosca.workops.model.release

import bosca.db.annotation.ColumnName
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * R26 — a program-scoped bundle of per-project Versions. A
 * Release is the unit of "we shipped this together"; it holds a
 * release date, owner, and a join table linking each
 * participating project's [bosca.workops.model.version.Version]
 * row.
 */
@Serializable
data class Release(
    @Contextual
    val id: UUID = UUID.NIL,
    @ColumnName("program_id")
    @Contextual
    val programId: UUID,
    val name: String,
    val description: String? = null,
    @ColumnName("release_date")
    @Contextual
    val releaseDate: OffsetDateTime? = null,
    @ColumnName("released_at")
    @Contextual
    val releasedAt: OffsetDateTime? = null,
    @ColumnName("owner_profile_id")
    @Contextual
    val ownerProfileId: UUID? = null,
    val version: Long = 0,
    @Contextual
    val created: OffsetDateTime = OffsetDateTime.now(),
    @ColumnName("deleted_at")
    @Contextual
    val deletedAt: OffsetDateTime? = null,
)

/** Participation row linking a project Version into a Release. */
@Serializable
data class ReleaseProjectVersion(
    @ColumnName("release_id")
    @Contextual
    val releaseId: UUID,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("version_id")
    @Contextual
    val versionId: UUID,
    @ColumnName("deployment_order")
    val deploymentOrder: Int? = null,
    @ColumnName("deployment_status")
    val deploymentStatus: String = "PENDING",
    @ColumnName("deployed_at")
    @Contextual
    val deployedAt: OffsetDateTime? = null,
    @ColumnName("deployed_by_principal_id")
    @Contextual
    val deployedByPrincipalId: UUID? = null,
    @ColumnName("rollback_version_id")
    @Contextual
    val rollbackVersionId: UUID? = null,
)

/**
 * A read projection of one native git-ci RELEASE or PROMOTION run correlated through its persisted
 * `release.id` trigger parameter. The full run, jobs, approvals, and logs remain queryable in git-ci.
 */
@Serializable
data class ReleaseRunView(
    @Contextual
    val runId: UUID,
    /** The git-ci status name: QUEUED, RUNNING, SUCCESS, FAILURE, CANCELLED, or SKIPPED. */
    val status: String,
    @Contextual
    val startedAt: OffsetDateTime,
    /** When the run reached a terminal status, or null while it is still in-flight. */
    @Contextual
    val finishedAt: OffsetDateTime? = null,
    /** Wall-clock duration once finished, or null while in-flight. */
    val durationMs: Int? = null,
)

/** A pipeline-declared input rendered by the release Start or Promote dialog. */
@Serializable
data class ReleasePipelineInput(
    val name: String,
    val type: String,
    val defaultValue: String? = null,
    val description: String? = null,
    val options: List<String> = emptyList(),
    val required: Boolean,
)

/** One step in the exact job set selected by git-ci for a release or promotion run. */
@Serializable
data class ReleasePipelinePlanStep(
    val name: String,
    /** A built-in action name, or `run` for a repository-owned shell step. */
    val action: String,
)

/** One dependency-ordered job in a native git-ci release execution plan. */
@Serializable
data class ReleasePipelinePlanJob(
    val key: String,
    val environment: String? = null,
    val approvalRequired: Boolean,
    val needs: List<String>,
    /** Human-readable artifact and upstream-pipeline gates declared by this job. */
    val requirements: List<String>,
    val steps: List<ReleasePipelinePlanStep>,
    /** Topological column: zero has no selected dependencies; larger values run later. */
    val depth: Int,
)

/**
 * A read-only projection of the exact jobs git-ci would persist for one repository pipeline and
 * RELEASE/PROMOTION target. WorkOps supplies release correlation; git-ci remains the planner.
 */
@Serializable
data class ReleasePipelinePlan(
    @Contextual val pipelineId: UUID,
    val pipelineName: String,
    @Contextual val repositoryId: UUID,
    val repositorySlug: String,
    /** RELEASE or PROMOTION. */
    val triggerType: String,
    val environment: String? = null,
    val promotesFrom: String? = null,
    val inputs: List<ReleasePipelineInput>,
    val jobs: List<ReleasePipelinePlanJob>,
)

/**
 * R7 / R26 — a task can affect multiple projects (e.g. a
 * platform incident impacts every consuming project). The join
 * row lets BQL filter on
 * `affectsProject = <projectKey>` even on tasks whose primary
 * project differs.
 */
@Serializable
data class TaskAffectedProject(
    @ColumnName("task_id")
    @Contextual
    val taskId: UUID,
    @ColumnName("project_id")
    @Contextual
    val projectId: UUID,
    @ColumnName("added_at")
    @Contextual
    val addedAt: OffsetDateTime = OffsetDateTime.now(),
)
