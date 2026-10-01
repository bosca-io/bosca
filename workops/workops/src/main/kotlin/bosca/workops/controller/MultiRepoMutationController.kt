package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.CreatedDeployConfig
import bosca.workops.deploy.DeployConfigService
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.ArtifactPublication
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
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.model.pipeline.CreatePipelineRunInput
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.pipeline.PipelineStatus
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.service.ApiSurfaceReportService
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.CompatibilityTestResultService
import bosca.workops.service.DependencyDeclarationService
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import bosca.workops.service.PipelineRunService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.ReleaseDeploymentService
import bosca.workops.service.ReleaseNotesService
import bosca.workops.service.ReleaseService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// ── Marker objects ────────────────────────────────────────────────────

@TypeController
class MultiRepoMutationController(
    private val depService: DependencyDeclarationService,
    private val artifactService: ArtifactPublicationService,
    private val apiSurfaceService: ApiSurfaceReportService,
    private val pipelineService: PipelineRunService,
    private val envService: EnvironmentService,
    private val envTypeService: EnvironmentTypeService,
    private val deployConfigService: DeployConfigService,
    private val compatService: CompatibilityTestResultService,
    private val releaseNotesService: ReleaseNotesService,
    private val releaseDeploymentService: ReleaseDeploymentService,
    private val releaseService: ReleaseService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val programService: ProgramService,
    private val json: Json,
) : GraphQLController<WorkOpsMultiRepoMutation> {

    private fun requirePrincipalId(authentication: AuthenticationContext): UUID {
        return authentication.principal()?.id ?: error("authentication required")
    }

    private suspend fun verifyProjectManage(authentication: AuthenticationContext, projectId: UUID) {
        val project = projectService.getById(projectId)
            ?: error("Project $projectId not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
    }

    private suspend fun verifyProgramManage(authentication: AuthenticationContext, programId: UUID) {
        val program = programService.getById(programId)
            ?: error("Program $programId not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE)
    }

    private suspend fun verifyReleaseManage(authentication: AuthenticationContext, releaseId: UUID) {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        verifyProgramManage(authentication, release.programId)
    }

    // ── Dependencies ──────────────────────────────────────────────────

    @Field
    suspend fun declareDependency(
        authentication: AuthenticationContext,
        input: CreateDependencyDeclarationInput,
    ): DependencyDeclaration {
        val project = projectService.getById(input.consumerProjectId) ?: error("Consumer project not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.MANAGE)
        return depService.declare(input)
    }

    @Field
    suspend fun updateDependencyStatus(
        authentication: AuthenticationContext,
        id: UUID,
        status: String,
        expectedVersion: Long,
    ): DependencyDeclaration {
        val dep = depService.getById(id) ?: error("DependencyDeclaration $id not found")
        verifyProjectManage(authentication, dep.consumerProjectId)
        return depService.updateStatus(id, DependencyStatus.valueOf(status), expectedVersion)
    }

    @Field
    suspend fun updateResolvedVersion(
        authentication: AuthenticationContext,
        id: UUID,
        versionId: UUID?,
        status: String,
        expectedVersion: Long,
    ): DependencyDeclaration {
        val dep = depService.getById(id) ?: error("DependencyDeclaration $id not found")
        verifyProjectManage(authentication, dep.consumerProjectId)
        return depService.updateResolvedVersion(id, versionId, DependencyStatus.valueOf(status), expectedVersion)
    }

    @Field
    suspend fun removeDependency(authentication: AuthenticationContext, id: UUID): Boolean {
        val dep = depService.getById(id) ?: error("DependencyDeclaration $id not found")
        verifyProjectManage(authentication, dep.consumerProjectId)
        depService.remove(id)
        return true
    }

    // ── Artifacts ─────────────────────────────────────────────────────

    @Field
    suspend fun registerArtifact(
        authentication: AuthenticationContext,
        input: RegisterArtifactInput,
    ): ArtifactPublication {
        verifyProjectManage(authentication, input.projectId)
        return artifactService.register(input)
    }

    @Field
    suspend fun markArtifactPublished(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): ArtifactPublication {
        val artifact = artifactService.getById(id) ?: error("ArtifactPublication $id not found")
        verifyProjectManage(authentication, artifact.projectId)
        return artifactService.markPublished(id, requirePrincipalId(authentication), expectedVersion)
    }

    @Field
    suspend fun markArtifactFailed(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): ArtifactPublication {
        val artifact = artifactService.getById(id) ?: error("ArtifactPublication $id not found")
        verifyProjectManage(authentication, artifact.projectId)
        return artifactService.markFailed(id, expectedVersion)
    }

    @Field
    suspend fun yankArtifact(authentication: AuthenticationContext, id: UUID, expectedVersion: Long): ArtifactPublication {
        val artifact = artifactService.getById(id) ?: error("ArtifactPublication $id not found")
        verifyProjectManage(authentication, artifact.projectId)
        return artifactService.yank(id, expectedVersion)
    }

    // ── API Surface Reports ───────────────────────────────────────────

    @Field
    suspend fun registerApiSurfaceReport(
        authentication: AuthenticationContext,
        input: RegisterApiSurfaceReportInput,
    ): ApiSurfaceReport {
        verifyProjectManage(authentication, input.projectId)
        return apiSurfaceService.register(
            ApiSurfaceReport(
                projectId = input.projectId,
                versionId = input.versionId,
                previousVersionId = input.previousVersionId,
                artifactPublicationId = input.artifactPublicationId,
                breakingChangeLevel = input.breakingChangeLevel,
                changes = input.changes,
                analyzerTool = input.analyzerTool,
                reportUrl = input.reportUrl,
            ),
            json,
        )
    }

    // ── Pipeline Runs ─────────────────────────────────────────────────

    @Field
    suspend fun createPipelineRun(
        authentication: AuthenticationContext,
        input: CreatePipelineRunInput,
    ): PipelineRun {
        verifyProjectManage(authentication, input.projectId)
        return pipelineService.create(input)
    }

    @Field
    suspend fun completePipelineRun(authentication: AuthenticationContext, id: UUID, status: String, expectedVersion: Long): PipelineRun {
        val run = pipelineService.getById(id) ?: error("PipelineRun $id not found")
        verifyProjectManage(authentication, run.projectId)
        return pipelineService.complete(id, PipelineStatus.valueOf(status), expectedVersion)
    }

    @Field
    suspend fun addPipelineStage(authentication: AuthenticationContext, pipelineRunId: UUID, stageName: String, externalUrl: String?): PipelineStageRun {
        val run = pipelineService.getById(pipelineRunId) ?: error("PipelineRun $pipelineRunId not found")
        verifyProjectManage(authentication, run.projectId)
        return pipelineService.addStage(pipelineRunId, stageName, externalUrl)
    }

    @Field
    suspend fun completePipelineStage(authentication: AuthenticationContext, stageId: UUID, status: String): PipelineStageRun {
        val stage = pipelineService.getStageById(stageId) ?: error("PipelineStageRun $stageId not found")
        val run = pipelineService.getById(stage.pipelineRunId) ?: error("PipelineRun ${stage.pipelineRunId} not found")
        verifyProjectManage(authentication, run.projectId)
        return pipelineService.completeStage(stageId, PipelineStatus.valueOf(status))
    }

    // ── Environments ──────────────────────────────────────────────────

    /** Verifies the caller belongs to the `sa` or `administrators` group — the global environment-type
     *  catalog spans every program, so no program-scoped permission can gate it. */
    private fun requireAdmin(authentication: AuthenticationContext) {
        val principal = authentication.principal() ?: error("Authentication required")
        if (!principal.hasGroup("sa") && !principal.hasGroup("administrators")) {
            error("Administrative access required")
        }
    }

    @Field
    suspend fun createEnvironmentType(
        authentication: AuthenticationContext,
        name: String,
        description: String?,
        displayOrder: Int?,
    ): EnvironmentType {
        requireAdmin(authentication)
        return envTypeService.create(name, description, displayOrder ?: 0)
    }

    @Field
    suspend fun updateEnvironmentType(
        authentication: AuthenticationContext,
        id: UUID,
        name: String,
        description: String?,
        displayOrder: Int?,
        expectedVersion: Long,
    ): EnvironmentType {
        requireAdmin(authentication)
        val existing = envTypeService.getById(id) ?: error("Environment type $id not found")
        return envTypeService.update(id, name, description, displayOrder ?: existing.displayOrder, expectedVersion)
    }

    @Field
    suspend fun deleteEnvironmentType(authentication: AuthenticationContext, id: UUID): Boolean {
        requireAdmin(authentication)
        envTypeService.delete(id)
        return true
    }

    /** Generates + commits a starter `.bosca/deploy.yaml` for the project (never overwrites). */
    @Field
    suspend fun createProjectDeployConfig(
        authentication: AuthenticationContext,
        projectId: UUID,
        repositoryId: UUID,
        authorName: String,
        authorEmail: String,
    ): CreatedDeployConfig {
        verifyProjectManage(authentication, projectId)
        return deployConfigService.createDeployConfig(projectId, repositoryId, authorName, authorEmail)
    }

    @Field
    suspend fun createEnvironment(
        authentication: AuthenticationContext,
        input: CreateEnvironmentInput,
    ): Environment {
        verifyProgramManage(authentication, input.programId)
        envTypeService.getById(input.typeId) ?: error("Environment type ${input.typeId} not found")
        return envService.create(input)
    }

    @Field
    suspend fun updateEnvironment(
        authentication: AuthenticationContext,
        id: UUID,
        input: PatchEnvironmentInput,
        expectedVersion: Long,
    ): Environment {
        val env = envService.getById(id) ?: error("Environment $id not found")
        verifyProgramManage(authentication, env.programId)
        envTypeService.getById(input.typeId) ?: error("Environment type ${input.typeId} not found")
        return envService.update(
            id,
            UpdateEnvironmentInput(
                name = input.name,
                description = input.description,
                displayOrder = input.displayOrder ?: env.displayOrder,
                promotionSourceIds = input.promotionSourceIds,
                requiresApproval = input.requiresApproval ?: env.requiresApproval,
                autoPromote = input.autoPromote ?: env.autoPromote,
                typeId = input.typeId,
                targetType = input.targetType ?: env.targetType,
                targetRef = input.targetRef ?: env.targetRef,
                ephemeral = input.ephemeral ?: env.ephemeral,
            ),
            expectedVersion,
        )
    }

    @Field
    suspend fun deleteEnvironment(authentication: AuthenticationContext, id: UUID): Boolean {
        val env = envService.getById(id) ?: error("Environment $id not found")
        verifyProgramManage(authentication, env.programId)
        envService.delete(id)
        return true
    }

    @Field
    suspend fun addEnvironmentPermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val env = envService.getById(id) ?: error("Environment $id not found")
        verifyProgramManage(authentication, env.programId)
        envService.addPermission(id, groupId, action)
        return true
    }

    @Field
    suspend fun removeEnvironmentPermission(
        authentication: AuthenticationContext,
        id: UUID,
        groupId: UUID,
        action: PermissionAction,
    ): Boolean {
        val env = envService.getById(id) ?: error("Environment $id not found")
        verifyProgramManage(authentication, env.programId)
        envService.removePermission(id, groupId, action)
        return true
    }

    @Field
    suspend fun deploy(authentication: AuthenticationContext, input: DeployInput): EnvironmentDeployment {
        verifyProjectManage(authentication, input.projectId)
        val env = envService.getById(input.environmentId) ?: error("Environment ${input.environmentId} not found")
        verifyProgramManage(authentication, env.programId)
        return envService.createDeployment(input, createdByPrincipalId = requirePrincipalId(authentication))
    }

    @Field
    suspend fun markDeployed(authentication: AuthenticationContext, deploymentId: UUID, expectedVersion: Long): EnvironmentDeployment {
        val deployment = envService.getDeployment(deploymentId)
            ?: error("EnvironmentDeployment $deploymentId not found")
        verifyProjectManage(authentication, deployment.projectId)
        return envService.markDeployed(deploymentId, requirePrincipalId(authentication), expectedVersion)
    }

    @Field
    suspend fun updateHealthCheck(authentication: AuthenticationContext, deploymentId: UUID, status: String, expectedVersion: Long): EnvironmentDeployment {
        val deployment = envService.getDeployment(deploymentId)
            ?: error("EnvironmentDeployment $deploymentId not found")
        verifyProjectManage(authentication, deployment.projectId)
        return envService.updateHealthCheck(deploymentId, HealthCheckStatus.valueOf(status), expectedVersion)
    }

    // ── Release Gates ─────────────────────────────────────────────────

    // ── Compatibility ─────────────────────────────────────────────────

    @Field
    suspend fun recordCompatibilityResult(
        authentication: AuthenticationContext,
        input: RecordCompatibilityResultInput,
    ): CompatibilityTestResult {
        verifyProjectManage(authentication, input.consumerProjectId)
        return compatService.record(input)
    }

    // ── Release Component Deployment ────────────────────────────────────

    @Field
    suspend fun setDeploymentOrder(authentication: AuthenticationContext, releaseId: UUID, projectId: UUID, versionId: UUID, order: Int): bosca.workops.model.release.ReleaseProjectVersion {
        verifyReleaseManage(authentication, releaseId)
        return releaseDeploymentService.setDeploymentOrder(releaseId, projectId, versionId, order)
    }

    @Field
    suspend fun markProjectVersionDeploying(authentication: AuthenticationContext, releaseId: UUID, projectId: UUID, versionId: UUID): bosca.workops.model.release.ReleaseProjectVersion {
        verifyReleaseManage(authentication, releaseId)
        return releaseDeploymentService.markDeploying(releaseId, projectId, versionId, requirePrincipalId(authentication))
    }

    @Field
    suspend fun markProjectVersionDeployed(authentication: AuthenticationContext, releaseId: UUID, projectId: UUID, versionId: UUID): bosca.workops.model.release.ReleaseProjectVersion {
        verifyReleaseManage(authentication, releaseId)
        return releaseDeploymentService.markDeployed(releaseId, projectId, versionId, requirePrincipalId(authentication))
    }

    @Field
    suspend fun markProjectVersionRolledBack(authentication: AuthenticationContext, releaseId: UUID, projectId: UUID, versionId: UUID, rollbackVersionId: UUID): bosca.workops.model.release.ReleaseProjectVersion {
        verifyReleaseManage(authentication, releaseId)
        return releaseDeploymentService.markRolledBack(releaseId, projectId, versionId, rollbackVersionId)
    }

    // ── Release Notes ─────────────────────────────────────────────────

    @Field
    suspend fun generateReleaseNotes(authentication: AuthenticationContext, releaseId: UUID, sections: JsonElement): ReleaseNotes {
        verifyReleaseManage(authentication, releaseId)
        return releaseNotesService.generate(releaseId, json.encodeToString(JsonElement.serializer(), sections))
    }

    @Field
    suspend fun autoGenerateReleaseNotes(authentication: AuthenticationContext, releaseId: UUID): ReleaseNotes {
        verifyReleaseManage(authentication, releaseId)
        return releaseNotesService.autoGenerate(releaseId)
    }

    @Field
    suspend fun editReleaseNotes(authentication: AuthenticationContext, releaseId: UUID, sections: JsonElement, expectedVersion: Long): ReleaseNotes {
        verifyReleaseManage(authentication, releaseId)
        return releaseNotesService.editManually(releaseId, json.encodeToString(JsonElement.serializer(), sections), expectedVersion)
    }

    @Field
    suspend fun autoGenerateVersionReleaseNotes(
        authentication: AuthenticationContext,
        releaseId: UUID,
    ): List<VersionReleaseNotes> {
        verifyReleaseManage(authentication, releaseId)
        return releaseNotesService.autoGenerateLocalized(releaseId, requirePrincipalId(authentication))
    }

    @Field
    suspend fun editVersionReleaseNotes(
        authentication: AuthenticationContext,
        releaseId: UUID,
        versionId: UUID,
        variants: JsonElement,
    ): VersionReleaseNotes {
        verifyReleaseManage(authentication, releaseId)
        return releaseNotesService.editVersionNotes(
            releaseId = releaseId,
            versionId = versionId,
            variants = json.encodeToString(JsonElement.serializer(), variants),
            editedByPrincipalId = requirePrincipalId(authentication),
        )
    }
}
