package bosca.workops.service

import bosca.security.model.PermissionAction
import bosca.security.model.PermissionService
import bosca.serialization.UUID
import bosca.service.Service
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.compatibility.RecordCompatibilityResultInput
import bosca.workops.model.dependency.BuildReadiness
import bosca.workops.model.dependency.CreateDependencyDeclarationInput
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.model.pipeline.CreatePipelineRunInput
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.pipeline.PipelineStatus
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotesGenerationInput
import bosca.workops.model.release.VersionReleaseNotes
import kotlinx.serialization.json.Json

/**
 * Manages the project-to-project dependency graph that drives
 * cross-repo version coordination, build readiness checks, and
 * downstream cascade orchestration.
 */
interface DependencyDeclarationService : Service {
    suspend fun getById(id: UUID): DependencyDeclaration?
    suspend fun listByConsumer(projectId: UUID): List<DependencyDeclaration>
    suspend fun listByProvider(projectId: UUID): List<DependencyDeclaration>
    suspend fun declare(input: CreateDependencyDeclarationInput): DependencyDeclaration
    suspend fun updateStatus(id: UUID, status: DependencyStatus, expectedVersion: Long): DependencyDeclaration
    suspend fun updateResolvedVersion(id: UUID, versionId: UUID?, status: DependencyStatus, expectedVersion: Long): DependencyDeclaration
    suspend fun remove(id: UUID)
}

/**
 * Tracks Maven, npm, Docker, and other artifacts published from
 * CI pipelines. Status transitions (PENDING -> PUBLISHED) fire
 * the ArtifactPublished automation trigger.
 */
interface ArtifactPublicationService : Service {
    suspend fun getById(id: UUID): ArtifactPublication?
    suspend fun listByVersion(versionId: UUID): List<ArtifactPublication>
    suspend fun listByProject(projectId: UUID): List<ArtifactPublication>
    suspend fun register(input: RegisterArtifactInput): ArtifactPublication
    suspend fun markPublished(id: UUID, publishedByPrincipalId: UUID, expectedVersion: Long): ArtifactPublication
    suspend fun markFailed(id: UUID, expectedVersion: Long): ArtifactPublication
    suspend fun yank(id: UUID, expectedVersion: Long): ArtifactPublication

    /** Deletes the publication record — a release-attempt rollback un-registering what it published. */
    suspend fun remove(id: UUID)
}

/**
 * Records and queries CI/CD pipeline executions. The Bosca CLI
 * reports runs directly via GraphQL; external CI providers
 * report via webhook ingestion.
 */
interface PipelineRunService : Service {
    suspend fun getById(id: UUID): PipelineRun?
    suspend fun getStageById(id: UUID): PipelineStageRun?
    suspend fun listByProject(projectId: UUID): List<PipelineRun>
    suspend fun create(input: CreatePipelineRunInput): PipelineRun
    suspend fun complete(id: UUID, status: PipelineStatus, expectedVersion: Long): PipelineRun
    suspend fun listStages(pipelineRunId: UUID): List<PipelineStageRun>
    suspend fun addStage(pipelineRunId: UUID, stageName: String, externalUrl: String?): PipelineStageRun
    suspend fun completeStage(stageId: UUID, status: PipelineStatus): PipelineStageRun
}

/**
 * Manages deployment target environments (dev, staging, prod)
 * and tracks which project versions are deployed where,
 * including health check status and promotion chains.
 */
/**
 * Drift entry showing a mismatch between what's deployed in an
 * environment and what a release bundles.
 */
data class EnvironmentDrift(
    val projectId: UUID,
    val deployedVersionId: UUID?,
    val expectedVersionId: UUID,
    val driftType: EnvironmentDriftType,
)

enum class EnvironmentDriftType { VERSION_MISMATCH, NOT_DEPLOYED }

/**
 * The global, user-managed catalog of environment lifecycle stages (development / staging /
 * production / preview / …). Pipelines reference a type by name — portable across programs —
 * and each program's environments instantiate a type.
 */
interface EnvironmentTypeService : Service {
    suspend fun list(): List<EnvironmentType>
    suspend fun getById(id: UUID): EnvironmentType?
    /** A type by its globally-unique [name] (case-insensitive), or null. */
    suspend fun getByName(name: String): EnvironmentType?
    suspend fun create(name: String, description: String?, displayOrder: Int): EnvironmentType
    suspend fun update(id: UUID, name: String, description: String?, displayOrder: Int, expectedVersion: Long): EnvironmentType
    /** Fails while any environment still instantiates the type — retype those environments first. */
    suspend fun delete(id: UUID)
}

interface EnvironmentService : Service, PermissionService<Environment, UUID> {
    suspend fun getById(id: UUID): Environment?
    suspend fun getDeployment(deploymentId: UUID): EnvironmentDeployment?
    suspend fun listByProgram(programId: UUID): List<Environment>
    /** An environment by its program-unique [name] (case-insensitive), or null — the exact-name
     *  override when a program has several environments of one type. */
    suspend fun getByProgramAndName(programId: UUID, name: String): Environment?
    /** An environment by its program-unique stable [key] — how release-pipeline YAML links. */
    suspend fun getByProgramAndKey(programId: UUID, key: String): Environment?
    /** Grants [groupId] an [action] on the environment itself (approve/deploy gating). Idempotent. */
    suspend fun addPermission(environmentId: UUID, groupId: UUID, action: PermissionAction)
    /** Revokes a grant added by [addPermission]. */
    suspend fun removePermission(environmentId: UUID, groupId: UUID, action: PermissionAction)
    /** The environments in [programId] instantiating [typeId] — how a relay's Get Environment node
     *  resolves its named type within the run's own program. */
    suspend fun listByProgramAndType(programId: UUID, typeId: UUID): List<Environment>
    suspend fun create(input: CreateEnvironmentInput): Environment
    suspend fun update(id: UUID, input: UpdateEnvironmentInput, expectedVersion: Long): Environment
    /** The ids of the environments [environmentId] can be promoted from (many-to-many). */
    suspend fun promotionSourceIds(environmentId: UUID): List<UUID>
    suspend fun delete(id: UUID)
    /**
     * Records a new deployment of a version to an environment — status PENDING. It does NOT deploy
     * anything; the actual push to the environment's external channel is separate, and [markDeployed]
     * (or [markFailed]) records the real outcome once known.
     */
    suspend fun createDeployment(input: DeployInput, createdByPrincipalId: UUID): EnvironmentDeployment
    suspend fun markDeployed(deploymentId: UUID, deployedByPrincipalId: UUID, expectedVersion: Long): EnvironmentDeployment
    suspend fun markFailed(deploymentId: UUID, expectedVersion: Long): EnvironmentDeployment
    suspend fun markRolledBack(deploymentId: UUID, expectedVersion: Long): EnvironmentDeployment
    suspend fun updateHealthCheck(deploymentId: UUID, status: HealthCheckStatus, expectedVersion: Long): EnvironmentDeployment

    /**
     * Probes [deploymentId]'s health ONCE — the single implementation behind the wait-healthy job
     * and the CI verify-deployment gate, so both observe identically. A durable
     * HEALTHY returns immediately; a declared `healthCheckUrl` is probed via [probe] (true =
     * healthy, recorded through [updateHealthCheck]); no URL, a failed probe, or a probe error
     * returns the current status unchanged — this never downgrades health.
     */
    suspend fun probeHealth(deploymentId: UUID, probe: suspend (String) -> Boolean): HealthCheckStatus
    suspend fun currentState(environmentId: UUID): List<EnvironmentDeployment>
    /**
     * Where [environmentId] differs from what [releaseId] ships — an older/different version deployed,
     * or a bundled project not deployed there at all. Only channel-compatible projects are considered
     * (see [channelCompatibleProjects]): a server project is never "not deployed" in a Play track.
     */
    suspend fun environmentDrift(environmentId: UUID, releaseId: UUID): List<EnvironmentDrift>
    /**
     * The [releaseId] projects that can ship to [environmentId]'s channel at all, judged by their
     * declared CI artifacts. Store channels (Play tracks, TestFlight, the App Store) take only their
     * platform artifact; generic (infrastructure) environments take everything else — including
     * projects with no declarations, so a repository that simply lacks them doesn't vanish from drift.
     */
    suspend fun channelCompatibleProjects(environmentId: UUID, releaseId: UUID): List<UUID>

    /**
     * Records the INTENT to promote: every version currently DEPLOYED in [sourceEnvironmentId] gets a new
     * PENDING deployment on [targetEnvironmentId], stamped with [releaseId] (so the release's "what's
     * where" view sees it). It does NOT promote anything — the actual rollout is the channel adapters'
     * (the relay's Promote node), and [markDeployed] records the real outcome once each target deployment
     * lands. Enforces the promotion graph: [sourceEnvironmentId] must be a configured promotion source of
     * the target. Returns the new (PENDING) target deployments.
     */
    suspend fun createPromotionDeployment(
        sourceEnvironmentId: UUID,
        targetEnvironmentId: UUID,
        releaseId: UUID?,
        promotedByPrincipalId: UUID,
    ): List<EnvironmentDeployment>

    /** Every environment deployment carrying [releaseId] — the release's live "what artifact/version is
     *  in what environment" view, populated by the Deploy/Promote nodes stamping the release id. */
    suspend fun deploymentsByRelease(releaseId: UUID): List<EnvironmentDeployment>
}

/**
 * Records cross-repo compatibility test results — whether a
 * specific consumer version compiles and runs against a specific
 * provider version.
 */
interface CompatibilityTestResultService : Service {
    suspend fun getById(id: UUID): CompatibilityTestResult?
    suspend fun listByConsumerVersion(consumerProjectId: UUID, consumerVersionId: UUID): List<CompatibilityTestResult>
    suspend fun record(input: RecordCompatibilityResultInput): CompatibilityTestResult
}

/**
 * Registers and queries API surface analysis reports that detect
 * breaking changes between versions. Breaking reports automatically
 * flip downstream dependency declarations to INCOMPATIBLE.
 */
interface ApiSurfaceReportService : Service {
    suspend fun getById(id: UUID): ApiSurfaceReport?
    suspend fun listByVersion(versionId: UUID): List<ApiSurfaceReport>
    suspend fun register(report: ApiSurfaceReport, json: Json): ApiSurfaceReport
}

/**
 * Generates and manages release notes aggregated from tasks
 * associated with bundled release versions.
 */
interface ReleaseNotesService : Service {
    suspend fun getByRelease(releaseId: UUID): ReleaseNotes?
    suspend fun generate(releaseId: UUID, sections: String): ReleaseNotes
    suspend fun autoGenerate(releaseId: UUID): ReleaseNotes
    suspend fun editManually(releaseId: UUID, sections: String, expectedVersion: Long): ReleaseNotes

    /** Lists the version-owned notes for every version bundled by [releaseId]. */
    suspend fun listVersionNotes(releaseId: UUID): List<VersionReleaseNotes>

    /** Generates localized, store-ready drafts for every version bundled by [releaseId]. */
    suspend fun autoGenerateLocalized(releaseId: UUID, createdByPrincipalId: UUID): List<VersionReleaseNotes>

    /** Replaces one bundled version's localized variants after a human dashboard edit. */
    suspend fun editVersionNotes(
        releaseId: UUID,
        versionId: UUID,
        variants: String,
        editedByPrincipalId: UUID,
    ): VersionReleaseNotes

    /**
     * Returns required store fields for [locales], failing with a message naming the missing locale
     * and field instead of allowing a store submission with incomplete metadata.
     */
    suspend fun requireLocalized(versionId: UUID, locales: List<String>): List<LocalizedReleaseNotes>
}

/** Kit-owned source-copy generation seam used by WorkOps' release-notes service. */
interface ReleaseNotesAIService : Service {
    /** Produces one non-blank store-note set in [ReleaseNotesGenerationInput.sourceLocale]. */
    suspend fun generate(input: ReleaseNotesGenerationInput): LocalizedReleaseNotes
}

/**
 * Manages deployment ordering and status on release component
 * versions — the per-project entries bundled into a release.
 */
interface ReleaseDeploymentService : Service {
    suspend fun setDeploymentOrder(releaseId: UUID, projectId: UUID, versionId: UUID, order: Int): bosca.workops.model.release.ReleaseProjectVersion
    suspend fun markDeploying(releaseId: UUID, projectId: UUID, versionId: UUID, principalId: UUID): bosca.workops.model.release.ReleaseProjectVersion
    suspend fun markDeployed(releaseId: UUID, projectId: UUID, versionId: UUID, principalId: UUID): bosca.workops.model.release.ReleaseProjectVersion
    suspend fun markRolledBack(releaseId: UUID, projectId: UUID, versionId: UUID, rollbackVersionId: UUID): bosca.workops.model.release.ReleaseProjectVersion
}

/**
 * Walks the dependency graph for transitive consumer/provider
 * queries and impact analysis. Used by the CLI's `build-order`
 * command and the `impactAnalysis` GraphQL query.
 */
interface DependencyGraphService : Service {
    suspend fun transitiveConsumerProjectIds(projectId: UUID): List<UUID>
    suspend fun transitiveProviderProjectIds(projectId: UUID): List<UUID>

    /**
     * The given projects in dependency-safe build order: every declared PROVIDER before its consumers,
     * considering only edges among [projectIds] (a provider outside the set doesn't constrain it).
     * Ties keep the input order, so a caller's own ordering (e.g. deployment order) is the tiebreak.
     * A cycle among the projects fails loudly — a cyclic "build order" is not an order.
     */
    suspend fun buildOrder(projectIds: List<UUID>): List<UUID>
}

/**
 * Aggregates dependency status, compatibility results, and artifact
 * publications to determine whether a project can build right now.
 */
interface BuildReadinessService : Service {
    suspend fun check(projectId: UUID): BuildReadiness
}
