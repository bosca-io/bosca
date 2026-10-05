package bosca.workops.service

import bosca.db.transaction
import bosca.di.ObjectProvider
import bosca.graphql.Batch
import bosca.git.model.DiffLineType
import bosca.git.service.DiffService
import bosca.git.service.RepositoryBrowseService
import bosca.git.service.RepositoryService
import bosca.localization.model.LocalizationProject
import bosca.localization.model.LocalizationString
import bosca.localization.model.LocalizationStringInput
import bosca.localization.model.LocalizationTranslation
import bosca.localization.model.LocalizationTranslationInput
import bosca.localization.model.TranslationOrigin
import bosca.localization.model.TranslationState
import bosca.localization.service.LocalizationService
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.service.annotation.ServiceImplementation
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.BreakingChangeLevel
import bosca.workops.model.artifact.ReleaseDeclaredArtifact
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.compatibility.RecordCompatibilityResultInput
import bosca.workops.model.dependency.CreateDependencyDeclarationInput
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentDeploymentStatus
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.model.pipeline.CreatePipelineRunInput
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.pipeline.PipelineStatus
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotesChange
import bosca.workops.model.release.ReleaseNotesCommit
import bosca.workops.model.release.ReleaseNotesGenerationInput
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.repository.ArtifactPublicationRepository
import bosca.workops.repository.ApiSurfaceReportRepository
import bosca.workops.repository.CompatibilityTestResultRepository
import bosca.workops.repository.DependencyDeclarationRepository
import bosca.workops.repository.EnvironmentDeploymentRepository
import bosca.workops.repository.EnvironmentPermissionRepository
import bosca.workops.repository.EnvironmentRepository
import bosca.workops.repository.EnvironmentTypeRepository
import bosca.workops.repository.PipelineRunRepository
import bosca.workops.repository.PipelineStageRunRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ReleaseProjectVersionDeploymentRepository
import bosca.workops.repository.ReleaseNotesTaskRepository
import bosca.workops.repository.ReleaseRepository
import bosca.workops.repository.ReleaseNotesRepository
import bosca.workops.repository.ProjectRepositoryRepository
import bosca.workops.repository.VersionRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import org.slf4j.LoggerFactory

private data class ScopeIds(val programId: UUID?, val portfolioId: UUID?)

private val log = LoggerFactory.getLogger("bosca.workops.service.MultiRepoService")

private suspend fun resolveScope(
    projectRepository: ProjectRepository,
    programRepository: ProgramRepository,
    projectId: UUID,
): ScopeIds {
    val project = projectRepository.getById(projectId) ?: return ScopeIds(null, null)
    val program = programRepository.getById(project.programId)
    return ScopeIds(project.programId, program?.portfolioId)
}

// ── Dependency Declaration Service ────────────────────────────────────

@ServiceImplementation
class DependencyDeclarationServiceImpl(
    private val repository: DependencyDeclarationRepository,
    private val projectRepository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val dispatcher: AutomationDispatcher,
) : DependencyDeclarationService {

    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun listByConsumer(projectId: UUID) = repository.listByConsumer(projectId)

    override suspend fun listByProvider(projectId: UUID) = repository.listByProvider(projectId)

    override suspend fun declare(input: CreateDependencyDeclarationInput): DependencyDeclaration = transaction {
        if (input.consumerProjectId == input.providerProjectId) {
            throw WorkOpsValidationException("providerProjectId", "a project cannot depend on itself")
        }
        projectRepository.getById(input.consumerProjectId)
            ?: throw WorkOpsNotFoundException("Project", input.consumerProjectId.toString())
        projectRepository.getById(input.providerProjectId)
            ?: throw WorkOpsNotFoundException("Project", input.providerProjectId.toString())
        repository.add(
            consumerProjectId = input.consumerProjectId,
            consumerVersionId = input.consumerVersionId,
            providerProjectId = input.providerProjectId,
            providerVersionConstraint = input.providerVersionConstraint,
            resolvedProviderVersionId = input.resolvedProviderVersionId,
            dependencyType = input.dependencyType.name,
            artifactCoordinates = input.artifactCoordinates,
            status = DependencyStatus.CURRENT.name,
        )
    }

    override suspend fun updateStatus(id: UUID, status: DependencyStatus, expectedVersion: Long): DependencyDeclaration {
        val updated = repository.updateStatus(id, status.name, expectedVersion)
            ?: throw WorkOpsNotFoundException("DependencyDeclaration", id.toString())
        if (status == DependencyStatus.OUTDATED) {
            val scope = resolveScope(projectRepository, programRepository, updated.consumerProjectId)
            try {
                dispatcher.fireDependencyOutdated(updated.consumerProjectId, scope.programId, scope.portfolioId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to fire DependencyOutdated for {}: {}", id, e.message, e)
            }
        }
        return updated
    }

    override suspend fun updateResolvedVersion(id: UUID, versionId: UUID?, status: DependencyStatus, expectedVersion: Long): DependencyDeclaration {
        val updated = repository.updateResolvedVersion(id, versionId, status.name, expectedVersion)
            ?: throw WorkOpsNotFoundException("DependencyDeclaration", id.toString())
        if (status == DependencyStatus.OUTDATED) {
            val scope = resolveScope(projectRepository, programRepository, updated.consumerProjectId)
            try {
                dispatcher.fireDependencyOutdated(updated.consumerProjectId, scope.programId, scope.portfolioId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Failed to fire DependencyOutdated for {}: {}", id, e.message, e)
            }
        }
        return updated
    }

    override suspend fun remove(id: UUID) = repository.delete(id)
}

// ── Artifact Publication Service ──────────────────────────────────────

@ServiceImplementation
class ArtifactPublicationServiceImpl(
    private val repository: ArtifactPublicationRepository,
    private val projectRepository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val dispatcher: AutomationDispatcher,
) : ArtifactPublicationService {

    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun listByVersion(versionId: UUID) = repository.listByVersion(versionId)
    override suspend fun listByProject(projectId: UUID) = repository.listByProject(projectId)

    override suspend fun register(input: RegisterArtifactInput): ArtifactPublication = transaction {
        val added = repository.add(
            ArtifactPublication(
                versionId = input.versionId,
                projectId = input.projectId,
                artifactType = input.artifactType,
                coordinates = input.coordinates,
                repositoryUrl = input.repositoryUrl,
                checksumSha256 = input.checksumSha256,
                externalUrl = input.externalUrl,
                namespace = input.namespace,
                environments = input.environments,
            ),
        )
        val publication = added ?: repository.getByCoordinates(input.coordinates)
            ?: error("Artifact publication '${input.coordinates}' conflicted but could not be read")
        check(
            publication.versionId == input.versionId &&
                publication.projectId == input.projectId &&
                publication.artifactType == input.artifactType
        ) {
            "Artifact coordinate '${input.coordinates}' is already registered to another project version"
        }
        publication
    }

    override suspend fun markPublished(id: UUID, publishedByPrincipalId: UUID, expectedVersion: Long): ArtifactPublication {
        val published = repository.updateStatus(id, PublicationStatus.PUBLISHED.name, OffsetDateTime.now(), publishedByPrincipalId, expectedVersion)
            ?: throw WorkOpsNotFoundException("ArtifactPublication", id.toString())
        val scope = resolveScope(projectRepository, programRepository, published.projectId)
        try {
            dispatcher.fireArtifactPublished(
                published.projectId, scope.programId, scope.portfolioId,
                published.artifactType.name, published.coordinates,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fire ArtifactPublished for {}: {}", id, e.message, e)
        }
        return published
    }

    override suspend fun remove(id: UUID) = repository.delete(id)

    override suspend fun markFailed(id: UUID, expectedVersion: Long): ArtifactPublication =
        repository.updateStatus(id, PublicationStatus.FAILED.name, null, null, expectedVersion)
            ?: throw WorkOpsNotFoundException("ArtifactPublication", id.toString())

    override suspend fun yank(id: UUID, expectedVersion: Long): ArtifactPublication =
        repository.updateStatus(id, PublicationStatus.YANKED.name, null, null, expectedVersion)
            ?: throw WorkOpsNotFoundException("ArtifactPublication", id.toString())
}

// ── Pipeline Run Service ──────────────────────────────────────────────

@ServiceImplementation
class PipelineRunServiceImpl(
    private val runRepository: PipelineRunRepository,
    private val stageRepository: PipelineStageRunRepository,
    private val projectRepository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val dispatcher: AutomationDispatcher,
) : PipelineRunService {

    override suspend fun getById(id: UUID) = runRepository.getById(id)
    override suspend fun getStageById(id: UUID) = stageRepository.getById(id)
    override suspend fun listByProject(projectId: UUID) = runRepository.listByProject(projectId)

    override suspend fun create(input: CreatePipelineRunInput): PipelineRun = transaction {
        runRepository.add(
            projectId = input.projectId,
            versionId = input.versionId,
            pipelineId = input.pipelineId,
            pipelineName = input.pipelineName,
            triggerType = input.triggerType.name,
            triggerRef = input.triggerRef,
            externalUrl = input.externalUrl,
        )
    }

    override suspend fun complete(id: UUID, status: PipelineStatus, expectedVersion: Long): PipelineRun {
        val completed = runRepository.updateStatus(id, status.name, OffsetDateTime.now(), expectedVersion)
            ?: throw WorkOpsNotFoundException("PipelineRun", id.toString())
        val scope = resolveScope(projectRepository, programRepository, completed.projectId)
        try {
            when (status) {
                PipelineStatus.FAILED -> dispatcher.firePipelineFailed(
                    completed.projectId, scope.programId, scope.portfolioId, completed.pipelineId,
                )

                PipelineStatus.PASSED -> dispatcher.firePipelineCompleted(
                    completed.projectId, scope.programId, scope.portfolioId, completed.pipelineId,
                )

                else -> {}
            }
        } catch (e: Exception) {
            log.error("Failed to fire pipeline trigger for {}: {}", id, e.message, e)
        }
        return completed
    }

    override suspend fun listStages(pipelineRunId: UUID) = stageRepository.listByPipelineRun(pipelineRunId)

    override suspend fun addStage(pipelineRunId: UUID, stageName: String, externalUrl: String?): PipelineStageRun =
        stageRepository.add(pipelineRunId, stageName, PipelineStatus.RUNNING.name, OffsetDateTime.now(), externalUrl)

    override suspend fun completeStage(stageId: UUID, status: PipelineStatus): PipelineStageRun =
        stageRepository.updateStatus(stageId, status.name, OffsetDateTime.now())
            ?: throw WorkOpsNotFoundException("PipelineStageRun", stageId.toString())
}

// ── Environment Type Service ──────────────────────────────────────────

@ServiceImplementation
class EnvironmentTypeServiceImpl(
    private val typeRepository: EnvironmentTypeRepository,
) : EnvironmentTypeService {

    override suspend fun list(): List<EnvironmentType> = typeRepository.list()
    override suspend fun getById(id: UUID): EnvironmentType? = typeRepository.getById(id)

    override suspend fun getByName(name: String): EnvironmentType? =
        typeRepository.getByName(name) ?: typeRepository.list().find { it.name.equals(name, ignoreCase = true) }

    override suspend fun create(name: String, description: String?, displayOrder: Int): EnvironmentType {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "An environment type needs a name" }
        getByName(trimmed)?.let { error("An environment type named '${it.name}' already exists") }
        return typeRepository.add(trimmed, description, displayOrder)
    }

    override suspend fun update(id: UUID, name: String, description: String?, displayOrder: Int, expectedVersion: Long): EnvironmentType {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "An environment type needs a name" }
        getByName(trimmed)?.takeIf { it.id != id }?.let { error("An environment type named '${it.name}' already exists") }
        return typeRepository.update(id, trimmed, description, displayOrder, expectedVersion)
            ?: throw OptimisticLockFailedException("EnvironmentType", id)
    }

    override suspend fun delete(id: UUID) {
        val inUse = typeRepository.usageCount(id)
        if (inUse > 0) error("This environment type is used by $inUse environment(s) — change their type first")
        typeRepository.delete(id)
    }
}

// ── Environment Service ───────────────────────────────────────────────

/** Artifact types that only ever ship through a store channel — never to an infrastructure environment. */
private val storeOnlyArtifactTypes = setOf(ArtifactType.ANDROID_AAR, ArtifactType.IOS_FRAMEWORK)

@ServiceImplementation
class EnvironmentServiceImpl(
    private val envRepository: EnvironmentRepository,
    private val deployRepository: EnvironmentDeploymentRepository,
    private val releaseRepository: ReleaseRepository,
    private val projectRepository: ProjectRepository,
    private val programRepository: ProgramRepository,
    private val permissionRepository: EnvironmentPermissionRepository,
    private val programPermissionEvaluator: ProgramPermissionEvaluator,
    private val dispatcher: AutomationDispatcher,
    private val releasePipelines: ObjectProvider<ReleasePipelineService>,
) : EnvironmentService {

    override suspend fun getPermissions(entity: Environment): List<EntityPermission> =
        permissionRepository.getByEnvironmentId(entity.id)

    override suspend fun addPermissionsToBatch(batch: Batch<UUID, List<EntityPermission>>) {
        val permissions = permissionRepository.getByEnvironmentIds(batch.keys)
        val grouped = permissions.groupBy { it.environmentId }
        for (key in batch.keys) {
            batch.setData(key, grouped[key]?.let { ArrayList(it) } ?: emptyList())
        }
    }

    override suspend fun isParentAllowed(
        authentication: AuthenticationContext?,
        entity: Environment,
        action: PermissionAction,
    ): Boolean {
        val program = programRepository.getById(entity.programId) ?: return false
        return programPermissionEvaluator.isAllowed(authentication, program, action)
    }

    override suspend fun getById(id: UUID) = envRepository.getById(id)
    override suspend fun getDeployment(deploymentId: UUID) = deployRepository.getById(deploymentId)
    override suspend fun listByProgram(programId: UUID) = envRepository.listByProgram(programId)

    override suspend fun getByProgramAndName(programId: UUID, name: String): Environment? =
        envRepository.listByProgram(programId).find { it.name.equals(name, ignoreCase = true) }

    override suspend fun getByProgramAndKey(programId: UUID, key: String): Environment? =
        envRepository.getByProgramAndKey(programId, key)

    override suspend fun addPermission(environmentId: UUID, groupId: UUID, action: PermissionAction) =
        permissionRepository.add(environmentId, groupId, action)

    override suspend fun removePermission(environmentId: UUID, groupId: UUID, action: PermissionAction) =
        permissionRepository.delete(environmentId, groupId, action)

    override suspend fun listByProgramAndType(programId: UUID, typeId: UUID): List<Environment> =
        envRepository.listByProgram(programId).filter { it.typeId == typeId }

    override suspend fun create(input: CreateEnvironmentInput): Environment = transaction {
        val env = envRepository.add(
            programId = input.programId,
            key = normalizeKey(input.key),
            name = input.name,
            description = input.description,
            displayOrder = input.displayOrder,
            requiresApproval = input.requiresApproval,
            autoPromote = input.autoPromote,
            typeId = input.typeId,
            targetType = input.targetType,
            targetRef = input.targetRef,
            ephemeral = input.ephemeral,
        )
        input.promotionSourceIds.distinct().forEach { envRepository.addSource(env.id, it) }
        env
    }

    override suspend fun update(id: UUID, input: UpdateEnvironmentInput, expectedVersion: Long): Environment = transaction {
        val env = envRepository.update(
            id = id,
            name = input.name,
            description = input.description,
            displayOrder = input.displayOrder,
            requiresApproval = input.requiresApproval,
            autoPromote = input.autoPromote,
            typeId = input.typeId,
            targetType = input.targetType,
            targetRef = input.targetRef,
            ephemeral = input.ephemeral,
            expectedVersion = expectedVersion,
        ) ?: throw OptimisticLockFailedException("Environment", id)
        // Replace the promotion-source set wholesale.
        envRepository.clearSources(id)
        input.promotionSourceIds.distinct().forEach { envRepository.addSource(id, it) }
        env
    }

    override suspend fun promotionSourceIds(environmentId: UUID): List<UUID> =
        envRepository.listSources(environmentId).map { it.sourceEnvironmentId }

    override suspend fun delete(id: UUID) = envRepository.delete(id)

    /** Keys are slugs — the stable YAML link — so they normalize to lowercase and reject anything else. */
    private fun normalizeKey(key: String): String {
        val normalized = key.trim().lowercase()
        require(normalized.matches(KEY_PATTERN)) {
            "Environment key '$key' must be a slug: lowercase letters, digits, and single hyphens"
        }
        return normalized
    }

    override suspend fun createDeployment(input: DeployInput, createdByPrincipalId: UUID): EnvironmentDeployment = transaction {
        val current = deployRepository.currentState(input.environmentId)
            .find { it.projectId == input.projectId && it.targetKind == input.targetKind }
        deployRepository.add(
            environmentId = input.environmentId,
            projectId = input.projectId,
            targetKind = input.targetKind,
            versionId = input.versionId,
            releaseId = input.releaseId,
            artifactPublicationId = input.artifactPublicationId,
            appBuildNumberAllocationId = input.appBuildNumberAllocationId,
            healthCheckUrl = input.healthCheckUrl,
            previousDeploymentId = current?.id,
        )
    }

    override suspend fun markDeployed(deploymentId: UUID, deployedByPrincipalId: UUID, expectedVersion: Long): EnvironmentDeployment =
        deployRepository.updateStatus(
            deploymentId,
            EnvironmentDeploymentStatus.DEPLOYED.name,
            OffsetDateTime.now(),
            deployedByPrincipalId,
            expectedVersion,
        ) ?: throw WorkOpsNotFoundException("EnvironmentDeployment", deploymentId.toString())

    override suspend fun markFailed(deploymentId: UUID, expectedVersion: Long): EnvironmentDeployment =
        deployRepository.updateStatus(
            deploymentId,
            EnvironmentDeploymentStatus.FAILED.name,
            null,
            null,
            expectedVersion,
        ) ?: throw WorkOpsNotFoundException("EnvironmentDeployment", deploymentId.toString())

    override suspend fun markRolledBack(deploymentId: UUID, expectedVersion: Long): EnvironmentDeployment =
        deployRepository.updateStatus(
            deploymentId,
            EnvironmentDeploymentStatus.ROLLED_BACK.name,
            null,
            null,
            expectedVersion,
        ) ?: throw WorkOpsNotFoundException("EnvironmentDeployment", deploymentId.toString())

    override suspend fun updateHealthCheck(deploymentId: UUID, status: HealthCheckStatus, expectedVersion: Long): EnvironmentDeployment {
        val updated = deployRepository.updateHealthCheck(deploymentId, status.name, expectedVersion)
            ?: throw WorkOpsNotFoundException("EnvironmentDeployment", deploymentId.toString())
        val scope = resolveScope(projectRepository, programRepository, updated.projectId)
        try {
            when (status) {
                HealthCheckStatus.UNHEALTHY -> dispatcher.fireEnvironmentUnhealthy(
                    updated.projectId, scope.programId, scope.portfolioId, updated.environmentId,
                )

                HealthCheckStatus.HEALTHY -> {
                    val allDeployed = deployRepository.currentState(updated.environmentId)
                    if (allDeployed.all { it.healthCheckStatus == HealthCheckStatus.HEALTHY }) {
                        val env = envRepository.getById(updated.environmentId)
                        if (env != null) {
                            dispatcher.fireEnvironmentPromotionReady(
                                updated.projectId, scope.programId, scope.portfolioId, updated.environmentId,
                            )
                        }
                    }
                }

                else -> {}
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("Failed to fire environment trigger for deployment {}: {}", deploymentId, e.message, e)
        }
        return updated
    }

    override suspend fun probeHealth(deploymentId: UUID, probe: suspend (String) -> Boolean): HealthCheckStatus {
        val deployment = deployRepository.getById(deploymentId)
            ?: throw WorkOpsNotFoundException("EnvironmentDeployment", deploymentId.toString())
        if (deployment.healthCheckStatus == HealthCheckStatus.HEALTHY) return HealthCheckStatus.HEALTHY
        val url = deployment.healthCheckUrl
        if (url.isNullOrBlank()) return deployment.healthCheckStatus
        val healthy = try {
            probe(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("Health probe of {} failed ({}) — status unchanged", url, e.message)
            false
        }
        if (!healthy) return deployment.healthCheckStatus
        updateHealthCheck(deploymentId, HealthCheckStatus.HEALTHY, deployment.version)
        return HealthCheckStatus.HEALTHY
    }

    override suspend fun currentState(environmentId: UUID) = deployRepository.currentState(environmentId)

    override suspend fun channelCompatibleProjects(environmentId: UUID, releaseId: UUID): List<UUID> {
        val env = envRepository.getById(environmentId) ?: return emptyList()
        val declaredByProject = releasePipelines.get().releaseDeclaredArtifacts(releaseId).groupBy { it.projectId }
        return releaseRepository.listVersions(releaseId)
            .map { it.projectId }
            .distinct()
            .filter { channelCompatible(env, declaredByProject[it].orEmpty()) }
    }

    /**
     * Whether a project with [declared] CI artifacts can ship to [env]'s channel at all. Store channels
     * take ONLY their platform artifact — a server project has nothing to publish to a Play track, so
     * listing it there as "not deployed" would be permanent noise. Generic (infrastructure) environments
     * take anything that isn't store-only; a project with NO declarations stays visible there, because
     * hiding a repository that simply lacks CI declarations would silently drop real drift. An artifact
     * scoped to specific environments serves only those; unscoped serves all — the same rule deploys
     * resolve values files by.
     */
    private fun channelCompatible(env: Environment, declared: List<ReleaseDeclaredArtifact>): Boolean {
        fun servesEnvironment(artifact: ReleaseDeclaredArtifact) =
            artifact.environments.isEmpty() || artifact.environments.any { it.equals(env.name, ignoreCase = true) }
        return when (env.targetType) {
            EnvironmentTargetType.PLAY_TRACK ->
                declared.any { it.type == ArtifactType.ANDROID_AAR && servesEnvironment(it) }

            EnvironmentTargetType.TESTFLIGHT, EnvironmentTargetType.APP_STORE ->
                declared.any { it.type == ArtifactType.IOS_FRAMEWORK && servesEnvironment(it) }

            EnvironmentTargetType.GENERIC ->
                declared.isEmpty() || declared.any { it.type !in storeOnlyArtifactTypes && servesEnvironment(it) }
        }
    }

    override suspend fun environmentDrift(environmentId: UUID, releaseId: UUID): List<EnvironmentDrift> {
        val env = envRepository.getById(environmentId) ?: return emptyList()
        val declaredByProject = releasePipelines.get().releaseDeclaredArtifacts(releaseId).groupBy { it.projectId }
        val bundled = releaseRepository.listVersions(releaseId)
            .filter { channelCompatible(env, declaredByProject[it.projectId].orEmpty()) }
        val deployed = deployRepository.currentState(environmentId).groupBy { it.projectId }
        return bundled.mapNotNull { rcv ->
            val projectDeployments = deployed[rcv.projectId].orEmpty()
            val mismatched = projectDeployments.firstOrNull { it.versionId != rcv.versionId }
            when {
                // Every target for the project must run the release's version; one stale target is drift.
                mismatched != null -> EnvironmentDrift(
                    projectId = rcv.projectId,
                    deployedVersionId = mismatched.versionId,
                    expectedVersionId = rcv.versionId,
                    driftType = EnvironmentDriftType.VERSION_MISMATCH,
                )
                // The environment runs the release's version — in sync.
                projectDeployments.isNotEmpty() -> null
                // No environment record, but the release itself has this project deployed (the relay's Mark
                // Deployed sets ReleaseProjectVersion.deploymentStatus) — count it as deployed, not drift.
                rcv.deploymentStatus == RELEASE_DEPLOYED_STATUS -> null
                // Neither the environment nor the release considers this project deployed.
                else -> EnvironmentDrift(
                    projectId = rcv.projectId,
                    deployedVersionId = null,
                    expectedVersionId = rcv.versionId,
                    driftType = EnvironmentDriftType.NOT_DEPLOYED,
                )
            }
        }
    }

    override suspend fun createPromotionDeployment(
        sourceEnvironmentId: UUID,
        targetEnvironmentId: UUID,
        releaseId: UUID?,
        promotedByPrincipalId: UUID,
    ): List<EnvironmentDeployment> = transaction {
        val source = envRepository.getById(sourceEnvironmentId)
            ?: throw WorkOpsNotFoundException("Environment", sourceEnvironmentId.toString())
        val target = envRepository.getById(targetEnvironmentId)
            ?: throw WorkOpsNotFoundException("Environment", targetEnvironmentId.toString())
        // Enforce the promotion graph — you can promote staging → production only if that edge exists.
        val allowedSources = envRepository.listSources(targetEnvironmentId).map { it.sourceEnvironmentId }
        require(sourceEnvironmentId in allowedSources) {
            "Environment '${target.name}' cannot be promoted from '${source.name}' — no promotion edge is configured"
        }
        // Snapshot the target's current deployments up front so chaining previousDeploymentId is stable
        // while we add rows within the same transaction.
        val targetCurrent = deployRepository.currentState(targetEnvironmentId)
            .associateBy { it.projectId to it.targetKind }
        deployRepository.currentState(sourceEnvironmentId).map { deployment ->
            // A promotion is a new PENDING deployment on the target — the same version that is DEPLOYED in
            // the source. It is NOT marked DEPLOYED here: promoting records intent, not a confirmed target
            // deployment. markDeployed records the real outcome once the target deployment actually lands.
            deployRepository.add(
                environmentId = targetEnvironmentId,
                projectId = deployment.projectId,
                targetKind = deployment.targetKind,
                versionId = deployment.versionId,
                // Carry the release forward so the release's "what's where" view tracks the promotion.
                releaseId = releaseId ?: deployment.releaseId,
                artifactPublicationId = deployment.artifactPublicationId,
                appBuildNumberAllocationId = deployment.appBuildNumberAllocationId,
                healthCheckUrl = null,
                previousDeploymentId = targetCurrent[deployment.projectId to deployment.targetKind]?.id,
            )
        }
    }

    override suspend fun deploymentsByRelease(releaseId: UUID): List<EnvironmentDeployment> =
        deployRepository.listByRelease(releaseId)

    private companion object {
        /** `ReleaseProjectVersion.deploymentStatus` value set by the relay's Mark Deployed node. */
        const val RELEASE_DEPLOYED_STATUS = "DEPLOYED"

        /** Program-unique environment key shape: the YAML link, e.g. `production`, `qa-eu`. */
        val KEY_PATTERN = Regex("[a-z0-9]+(-[a-z0-9]+)*")
    }
}

// ── Compatibility Test Result Service ─────────────────────────────────

@ServiceImplementation
class CompatibilityTestResultServiceImpl(
    private val repository: CompatibilityTestResultRepository,
) : CompatibilityTestResultService {

    override suspend fun getById(id: UUID) = repository.getById(id)

    override suspend fun listByConsumerVersion(consumerProjectId: UUID, consumerVersionId: UUID) =
        repository.listByConsumerVersion(consumerProjectId, consumerVersionId)

    override suspend fun record(input: RecordCompatibilityResultInput): CompatibilityTestResult = transaction {
        repository.add(
            consumerProjectId = input.consumerProjectId,
            consumerVersionId = input.consumerVersionId,
            providerProjectId = input.providerProjectId,
            providerVersionId = input.providerVersionId,
            testSuite = input.testSuite,
            status = input.status.name,
            breakingChangesDetected = input.breakingChangesDetected,
            pipelineRunId = input.pipelineRunId,
            resultUrl = input.resultUrl,
        )
    }
}

// ── API Surface Report Service ────────────────────────────────────────

@ServiceImplementation
class ApiSurfaceReportServiceImpl(
    private val repository: ApiSurfaceReportRepository,
    private val depRepository: DependencyDeclarationRepository,
) : ApiSurfaceReportService {

    override suspend fun getById(id: UUID) = repository.getById(id)
    override suspend fun listByVersion(versionId: UUID) = repository.listByVersion(versionId)

    override suspend fun register(report: ApiSurfaceReport, json: Json): ApiSurfaceReport = transaction {
        val saved = repository.add(
            projectId = report.projectId,
            versionId = report.versionId,
            previousVersionId = report.previousVersionId,
            artifactPublicationId = report.artifactPublicationId,
            breakingChangeLevel = report.breakingChangeLevel.name,
            changes = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), report.changes),
            analyzerTool = report.analyzerTool,
            reportUrl = report.reportUrl,
        )
        if (report.breakingChangeLevel >= BreakingChangeLevel.MINOR_BREAKING) {
            val consumers = depRepository.listByProvider(report.projectId)
            for (dep in consumers) {
                if (dep.status != DependencyStatus.INCOMPATIBLE) {
                    depRepository.updateStatus(dep.id, DependencyStatus.INCOMPATIBLE.name, dep.version)
                }
            }
        }
        saved
    }
}

// ── Release Deployment Service ────────────────────────────────────────

@ServiceImplementation
class ReleaseDeploymentServiceImpl(
    private val repository: ReleaseProjectVersionDeploymentRepository,
) : ReleaseDeploymentService {

    override suspend fun setDeploymentOrder(releaseId: UUID, projectId: UUID, versionId: UUID, order: Int) =
        repository.setDeploymentOrder(releaseId, projectId, versionId, order)
            ?: throw WorkOpsNotFoundException("ReleaseProjectVersion", "$releaseId/$projectId/$versionId")

    override suspend fun markDeploying(releaseId: UUID, projectId: UUID, versionId: UUID, principalId: UUID) =
        repository.updateDeploymentStatus(releaseId, projectId, versionId, "DEPLOYING", OffsetDateTime.now(), principalId)
            ?: throw WorkOpsNotFoundException("ReleaseProjectVersion", "$releaseId/$projectId/$versionId")

    override suspend fun markDeployed(releaseId: UUID, projectId: UUID, versionId: UUID, principalId: UUID) =
        repository.updateDeploymentStatus(releaseId, projectId, versionId, "DEPLOYED", OffsetDateTime.now(), principalId)
            ?: throw WorkOpsNotFoundException("ReleaseProjectVersion", "$releaseId/$projectId/$versionId")

    override suspend fun markRolledBack(releaseId: UUID, projectId: UUID, versionId: UUID, rollbackVersionId: UUID) =
        repository.setRollbackVersion(releaseId, projectId, versionId, rollbackVersionId)
            ?: throw WorkOpsNotFoundException("ReleaseProjectVersion", "$releaseId/$projectId/$versionId")
}

// ── Release Notes Service ─────────────────────────────────────────────

@ServiceImplementation
class ReleaseNotesServiceImpl(
    private val repository: ReleaseNotesRepository,
    private val releaseRepository: ReleaseRepository,
    private val releaseNotesTaskRepository: ReleaseNotesTaskRepository,
    private val projectRepository: ProjectRepository,
    private val projectRepositories: ProjectRepositoryRepository,
    private val versionRepository: VersionRepository,
    private val gitRepositories: RepositoryService,
    private val browseService: RepositoryBrowseService,
    private val diffService: DiffService,
    private val localizationService: LocalizationService,
    private val releaseNotesAIService: ReleaseNotesAIService,
    private val json: Json,
) : ReleaseNotesService {

    override suspend fun getByRelease(releaseId: UUID) = repository.getByRelease(releaseId)

    override suspend fun generate(releaseId: UUID, sections: String): ReleaseNotes =
        repository.upsert(releaseId, sections)

    override suspend fun autoGenerate(releaseId: UUID): ReleaseNotes {
        val bundled = releaseRepository.listVersions(releaseId)
        val projectNames = mutableMapOf<UUID, String>()
        val categorized = mutableMapOf<String, MutableList<kotlinx.serialization.json.JsonObject>>()

        for (rcv in bundled) {
            val projectName = projectNames.getOrPut(rcv.projectId) {
                val project = projectRepository.getById(rcv.projectId)
                if (project == null) rcv.projectId.toString() else project.name
            }
            val tasks = releaseNotesTaskRepository.listTasksByFixVersion(rcv.versionId)
            for (task in tasks) {
                val category = classifyTask(task)
                categorized.getOrPut(category) { mutableListOf() }.add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("taskId", kotlinx.serialization.json.JsonPrimitive(task.id.toString()))
                        put("taskKey", kotlinx.serialization.json.JsonPrimitive(task.key))
                        put("summary", kotlinx.serialization.json.JsonPrimitive(task.summary))
                        put("projectName", kotlinx.serialization.json.JsonPrimitive(projectName))
                    }
                )
            }
        }

        val sections = kotlinx.serialization.json.JsonArray(
            categorized.map { (category, entries) ->
                kotlinx.serialization.json.buildJsonObject {
                    put("category", kotlinx.serialization.json.JsonPrimitive(category))
                    put("entries", kotlinx.serialization.json.JsonArray(entries))
                }
            }
        )

        return repository.upsert(releaseId, json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), sections))
    }

    private fun classifyTask(task: bosca.workops.model.task.Task): String {
        val summary = task.summary.lowercase()
        return when {
            summary.startsWith("fix") || summary.contains("bug") -> "BUG_FIX"
            summary.startsWith("add") || summary.contains("new") -> "NEW_FEATURE"
            summary.contains("breaking") -> "BREAKING_CHANGE"
            summary.contains("deprecat") -> "DEPRECATION"
            summary.contains("security") || summary.contains("vuln") -> "SECURITY"
            summary.contains("perf") -> "PERFORMANCE"
            summary.contains("doc") -> "DOCUMENTATION"
            summary.contains("refactor") || summary.contains("clean") -> "ENHANCEMENT"
            else -> "OTHER"
        }
    }

    override suspend fun editManually(releaseId: UUID, sections: String, expectedVersion: Long): ReleaseNotes =
        repository.editManually(releaseId, sections, expectedVersion)
            ?: throw WorkOpsNotFoundException("ReleaseNotes", releaseId.toString())

    override suspend fun listVersionNotes(releaseId: UUID): List<VersionReleaseNotes> {
        val localizationProjects = localizationService.getProjects()
        return releaseRepository.listVersions(releaseId).mapNotNull { bundledVersion ->
            val version = versionRepository.getById(bundledVersion.versionId)
                ?: throw WorkOpsNotFoundException("Version", bundledVersion.versionId.toString())
            readVersionNotes(version, localizationProjects)
        }
    }

    override suspend fun autoGenerateLocalized(
        releaseId: UUID,
        createdByPrincipalId: UUID,
    ): List<VersionReleaseNotes> {
        val bundled = releaseRepository.listVersions(releaseId)
        require(bundled.isNotEmpty()) { "Release $releaseId has no bundled versions to generate notes for" }
        val localizationProjects = localizationService.getProjects()
        val generated = bundled.map { bundledVersion ->
            val version = versionRepository.getById(bundledVersion.versionId)
                ?: throw WorkOpsNotFoundException("Version", bundledVersion.versionId.toString())
            val project = projectRepository.getById(bundledVersion.projectId)
                ?: throw WorkOpsNotFoundException("Project", bundledVersion.projectId.toString())
            val localizationProject = localizationProjects.singleOrNull {
                workOpsProjectId(it) == project.id.toString()
            } ?: error(
                "Version ${version.name} (${version.id}) has no localization project with " +
                    "attributes.$WORKOPS_PROJECT_ATTRIBUTE='${project.id}'",
            )
            val locales = buildList {
                add(localizationProject.sourceLanguage)
                addAll(localizationService.getProjectLanguages(localizationProject.id).map { it.languageTag })
            }.filter { it.isNotBlank() }.distinct()
            require(locales.isNotEmpty()) { "Version ${version.name} has no configured release-note locales" }

            val projectVersions = versionRepository.listByProject(project.id)
            val previous = projectVersions.lastOrNull {
                it.sequenceNumber < version.sequenceNumber && it.released
            }
            val commits = mutableListOf<ReleaseNotesCommit>()
            val changes = mutableListOf<ReleaseNotesChange>()
            val repositories = projectRepositories.list(project.id)
            require(repositories.isNotEmpty()) { "Project ${project.name} has no Git repositories for release-note generation" }
            repositories.forEach { projectRepositoryLink ->
                val gitRepository = gitRepositories.findById(projectRepositoryLink.repositoryId)
                    ?: error("Git repository ${projectRepositoryLink.repositoryId} linked to ${project.name} was not found")
                val tags = browseService.listTags(gitRepository.id)
                val currentTag = tags.singleOrNull { it.name == version.name }
                    ?: error("Git repository ${gitRepository.slug} is missing release tag '${version.name}'")
                if (previous == null) {
                    browseService.listCommits(gitRepository.id, currentTag.name, null, MAX_COMMITS, 0)
                        .forEach { commits += ReleaseNotesCommit(gitRepository.slug, it.sha, it.message) }
                } else {
                    val previousTag = tags.singleOrNull { it.name == previous.name }
                        ?: error("Git repository ${gitRepository.slug} is missing prior release tag '${previous.name}'")
                    val comparison = browseService.compare(gitRepository.id, previousTag.name, currentTag.name, diffService)
                    comparison.commits.take(MAX_COMMITS).forEach {
                        commits += ReleaseNotesCommit(gitRepository.slug, it.sha, it.message)
                    }
                    comparison.files.take(MAX_FILES).forEach { file ->
                        val lines = file.hunks.flatMap { it.lines }
                        changes += ReleaseNotesChange(
                            repository = gitRepository.slug,
                            path = file.newPath ?: file.oldPath ?: "unknown",
                            changeType = file.changeType.name,
                            additions = lines.asSequence().filter { it.type == DiffLineType.ADD }
                                .map { it.content.take(MAX_LINE_LENGTH) }.take(MAX_DIFF_LINES_PER_FILE).toList(),
                            deletions = lines.asSequence().filter { it.type == DiffLineType.DELETE }
                                .map { it.content.take(MAX_LINE_LENGTH) }.take(MAX_DIFF_LINES_PER_FILE).toList(),
                        )
                    }
                }
            }
            val sourceVariant = validateVariants(
                listOf(
                    releaseNotesAIService.generate(
                        ReleaseNotesGenerationInput(
                            projectName = project.name,
                            versionName = version.name,
                            previousVersionName = previous?.name,
                            sourceLocale = localizationProject.sourceLanguage,
                            commits = commits.take(MAX_COMMITS),
                            changes = changes.take(MAX_FILES),
                        ),
                    ),
                ),
                listOf(localizationProject.sourceLanguage),
                version.name,
            ).single()
            val strings = syncLocalization(
                localizationProject.id,
                version.id,
                listOf(sourceVariant),
                TranslationOrigin.AI,
                createdByPrincipalId,
            )
            val targetLocales = locales.filter { it != localizationProject.sourceLanguage }
            if (targetLocales.isNotEmpty()) {
                localizationService.generateAITranslations(
                    projectId = localizationProject.id,
                    stringIds = strings.values.map { it.id },
                    targetLanguageTags = targetLocales,
                    createdBy = createdByPrincipalId,
                    originDetail = "WorkOps version ${version.id}",
                )
            }
            readVersionNotes(version, localizationProjects)
                ?: error("Localization did not persist release notes for version ${version.name} (${version.id})")
        }
        return generated
    }

    override suspend fun editVersionNotes(
        releaseId: UUID,
        versionId: UUID,
        variants: String,
        editedByPrincipalId: UUID,
    ): VersionReleaseNotes {
        require(releaseRepository.listVersions(releaseId).any { it.versionId == versionId }) {
            "Version $versionId is not bundled in release $releaseId"
        }
        val version = versionRepository.getById(versionId) ?: throw WorkOpsNotFoundException("Version", versionId.toString())
        val localizationProjects = localizationService.getProjects()
        val localizationProject = findLocalizationProject(version, localizationProjects)
            ?: error("Version ${version.name} has no linked localization project")
        val locales = configuredLocales(localizationProject)
        val parsed = validateVariants(
            json.decodeFromString(ListSerializer(LocalizedReleaseNotes.serializer()), variants),
            expectedLocales = locales,
            versionName = version.name,
        )
        syncLocalization(localizationProject.id, versionId, parsed, TranslationOrigin.HUMAN, editedByPrincipalId)
        return readVersionNotes(version, localizationProjects)
            ?: error("Localization did not persist edited release notes for version ${version.name} (${version.id})")
    }

    override suspend fun requireLocalized(versionId: UUID, locales: List<String>): List<LocalizedReleaseNotes> {
        require(locales.isNotEmpty()) { "Store submission for version $versionId requires at least one release-note locale" }
        val version = versionRepository.getById(versionId)
            ?: throw WorkOpsNotFoundException("Version", versionId.toString())
        val notes = readVersionNotes(
            version,
            localizationService.getProjects(),
            requiredStates = locales.distinct().associateWith { TranslationState.PUBLISHED },
        )
            ?: error("Store submission for version $versionId is missing release notes")
        val variants = json.decodeFromJsonElement(ListSerializer(LocalizedReleaseNotes.serializer()), notes.variants)
            .associateBy { it.locale }
        return locales.distinct().map { locale ->
            val localized = variants[locale]
                ?: error("Store submission for version $versionId is missing release notes locale '$locale'")
            require(localized.playReleaseNotes.isNotBlank()) {
                "Store submission for version $versionId locale '$locale' is missing field 'playReleaseNotes'"
            }
            require(localized.appStoreWhatsNew.isNotBlank()) {
                "Store submission for version $versionId locale '$locale' is missing field 'appStoreWhatsNew'"
            }
            require(localized.testFlightWhatToTest.isNotBlank()) {
                "Store submission for version $versionId locale '$locale' is missing field 'testFlightWhatToTest'"
            }
            localized
        }
    }

    private fun validateVariants(
        variants: List<LocalizedReleaseNotes>,
        expectedLocales: List<String>,
        versionName: String,
    ): List<LocalizedReleaseNotes> {
        require(variants.map { it.locale }.distinct().size == variants.size) {
            "Release notes for version $versionName contain duplicate locales"
        }
        variants.forEach { localized ->
            require(localized.locale.isNotBlank()) { "Release notes for version $versionName contain a blank locale" }
            require(localized.playReleaseNotes.isNotBlank()) {
                "Release notes for version $versionName locale '${localized.locale}' are missing field 'playReleaseNotes'"
            }
            require(localized.appStoreWhatsNew.isNotBlank()) {
                "Release notes for version $versionName locale '${localized.locale}' are missing field 'appStoreWhatsNew'"
            }
            require(localized.testFlightWhatToTest.isNotBlank()) {
                "Release notes for version $versionName locale '${localized.locale}' are missing field 'testFlightWhatToTest'"
            }
        }
        expectedLocales.forEach { locale ->
            require(variants.any { it.locale == locale }) {
                "Release notes for version $versionName are missing configured locale '$locale'"
            }
        }
        val unexpected = variants.map { it.locale }.toSet() - expectedLocales.toSet()
        require(unexpected.isEmpty()) {
            "Release notes for version $versionName contain unconfigured locales $unexpected"
        }
        return expectedLocales.map { locale -> variants.single { it.locale == locale } }
    }

    private suspend fun readVersionNotes(
        version: bosca.workops.model.version.Version,
        localizationProjects: List<LocalizationProject>,
        requiredStates: Map<String, TranslationState> = emptyMap(),
    ): VersionReleaseNotes? {
        val localizationProject = findLocalizationProject(version, localizationProjects) ?: return null
        val strings = RELEASE_NOTE_FIELDS.associateWith { field ->
            localizationService.getStringByKey(localizationProject.id, releaseNoteKey(version.id, field))
        }
        if (strings.values.all { it == null }) return null
        val missingStrings = strings.filterValues { it == null }.keys
        require(missingStrings.isEmpty()) {
            "Release notes for version ${version.name} are missing localization fields $missingStrings"
        }
        val translationsByField = strings.mapValues { (_, string) ->
            localizationService.getTranslations(requireNotNull(string).id)
        }
        val usedTranslations = mutableListOf<LocalizationTranslation>()
        val variants = configuredLocales(localizationProject).mapNotNull { locale ->
            val translations = RELEASE_NOTE_FIELDS.associateWith { field ->
                translationsByField.getValue(field).singleOrNull { it.languageTag == locale }
            }
            if (translations.values.all { it == null }) return@mapNotNull null
            translations.forEach { (field, translation) ->
                require(translation != null && translation.text.isNotBlank()) {
                    "Release notes for version ${version.name} locale '$locale' are missing field '${apiFieldName(field)}'"
                }
                requiredStates[locale]?.let { state ->
                    check(translation.state == state) {
                        "Release notes for version ${version.name} locale '$locale' field '${apiFieldName(field)}' " +
                            "must be ${state.name} before store submission (was ${translation.state.name})"
                    }
                }
                usedTranslations += translation
            }
            val playReleaseNotes = requireNotNull(translations.getValue(PLAY_RELEASE_NOTES)).text
            val appStoreWhatsNew = requireNotNull(translations.getValue(APP_STORE_WHATS_NEW)).text
            val testFlightWhatToTest = requireNotNull(translations.getValue(TESTFLIGHT_WHAT_TO_TEST)).text
            LocalizedReleaseNotes(
                locale = locale,
                playReleaseNotes = playReleaseNotes,
                appStoreWhatsNew = appStoreWhatsNew,
                testFlightWhatToTest = testFlightWhatToTest,
            )
        }
        if (variants.isEmpty()) return null
        require(variants.any { it.locale == localizationProject.sourceLanguage }) {
            "Release notes for version ${version.name} are missing source locale '${localizationProject.sourceLanguage}'"
        }
        val generatedAt = usedTranslations.asSequence()
            .filter { it.languageTag == localizationProject.sourceLanguage }
            .mapNotNull { it.created }
            .maxOrNull()
        return VersionReleaseNotes(
            id = version.id,
            versionId = version.id,
            sourceLocale = localizationProject.sourceLanguage,
            generatedAt = generatedAt,
            manuallyEdited = usedTranslations.any { it.origin == TranslationOrigin.HUMAN },
            variants = json.encodeToJsonElement(ListSerializer(LocalizedReleaseNotes.serializer()), variants),
        )
    }

    private fun findLocalizationProject(
        version: bosca.workops.model.version.Version,
        localizationProjects: List<LocalizationProject>,
    ): LocalizationProject? = localizationProjects.singleOrNull {
        workOpsProjectId(it) == version.projectId.toString()
    }

    private fun workOpsProjectId(project: LocalizationProject): String? {
        val attributes = project.attributes as? JsonObject ?: return null
        val value = attributes[WORKOPS_PROJECT_ATTRIBUTE] as? JsonPrimitive ?: return null
        return value.contentOrNull
    }

    private suspend fun configuredLocales(project: LocalizationProject): List<String> = buildList {
        add(project.sourceLanguage)
        addAll(localizationService.getProjectLanguages(project.id).map { it.languageTag })
    }.filter { it.isNotBlank() }.distinct()

    private fun releaseNoteKey(versionId: UUID, field: String) = "workops.release-notes.$versionId.$field"

    private fun apiFieldName(field: String) = when (field) {
        PLAY_RELEASE_NOTES -> "playReleaseNotes"
        APP_STORE_WHATS_NEW -> "appStoreWhatsNew"
        TESTFLIGHT_WHAT_TO_TEST -> "testFlightWhatToTest"
        else -> error("Unknown release-note field '$field'")
    }

    private suspend fun syncLocalization(
        localizationProjectId: UUID,
        versionId: UUID,
        variants: List<LocalizedReleaseNotes>,
        origin: TranslationOrigin,
        principalId: UUID,
    ): Map<String, LocalizationString> {
        val fields = listOf(
            PLAY_RELEASE_NOTES to { notes: LocalizedReleaseNotes -> notes.playReleaseNotes },
            APP_STORE_WHATS_NEW to { notes: LocalizedReleaseNotes -> notes.appStoreWhatsNew },
            TESTFLIGHT_WHAT_TO_TEST to { notes: LocalizedReleaseNotes -> notes.testFlightWhatToTest },
        )
        return fields.associate { (field, text) ->
            val key = releaseNoteKey(versionId, field)
            val string = localizationService.getStringByKey(localizationProjectId, key)
                ?: localizationService.addString(
                    LocalizationStringInput(
                        projectId = localizationProjectId,
                        key = key,
                        context = "WorkOps version $versionId store release notes: $field",
                        tags = listOf("workops", "release-notes", field),
                    ),
                )
            variants.forEach { localized ->
                localizationService.setTranslation(
                    LocalizationTranslationInput(
                        stringId = string.id,
                        languageTag = localized.locale,
                        text = text(localized),
                        origin = origin,
                        originDetail = "WorkOps version $versionId",
                    ),
                    principalId,
                )
            }
            field to string
        }
    }

    private companion object {
        private const val WORKOPS_PROJECT_ATTRIBUTE = "workopsProjectId"
        private const val MAX_COMMITS = 100
        private const val MAX_FILES = 60
        private const val MAX_DIFF_LINES_PER_FILE = 20
        private const val MAX_LINE_LENGTH = 300
        private const val PLAY_RELEASE_NOTES = "play-release-notes"
        private const val APP_STORE_WHATS_NEW = "app-store-whats-new"
        private const val TESTFLIGHT_WHAT_TO_TEST = "testflight-what-to-test"
        private val RELEASE_NOTE_FIELDS = listOf(
            PLAY_RELEASE_NOTES,
            APP_STORE_WHATS_NEW,
            TESTFLIGHT_WHAT_TO_TEST,
        )
    }
}
