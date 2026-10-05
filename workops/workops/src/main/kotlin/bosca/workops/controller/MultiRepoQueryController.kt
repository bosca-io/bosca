package bosca.workops.controller

import bosca.graphql.GraphQLController
import bosca.graphql.annotations.Field
import bosca.graphql.annotations.TypeController
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.dependency.BuildReadiness
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.service.ApiSurfaceReportService
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.BuildReadinessService
import bosca.workops.service.CompatibilityTestResultService
import bosca.workops.service.DependencyDeclarationService
import bosca.workops.service.EnvironmentDrift
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import bosca.workops.service.PipelineRunService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectService
import bosca.workops.service.ReleaseNotesService
import bosca.workops.service.ReleaseService

// ── Marker objects ────────────────────────────────────────────────────

@TypeController
class MultiRepoQueryController(
    private val depService: DependencyDeclarationService,
    private val artifactService: ArtifactPublicationService,
    private val apiSurfaceService: ApiSurfaceReportService,
    private val pipelineService: PipelineRunService,
    private val envService: EnvironmentService,
    private val envTypeService: EnvironmentTypeService,
    private val compatService: CompatibilityTestResultService,
    private val releaseNotesService: ReleaseNotesService,
    private val buildReadinessService: BuildReadinessService,
    private val releaseService: ReleaseService,
    private val projectService: ProjectService,
    private val projectPermissions: ProjectPermissionEvaluator,
    private val programPermissions: ProgramPermissionEvaluator,
    private val programService: ProgramService,
) : GraphQLController<WorkOpsMultiRepoQuery> {

    private suspend fun verifyProjectView(authentication: AuthenticationContext, projectId: UUID) {
        val project = projectService.getById(projectId)
            ?: error("Project $projectId not found")
        projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
    }

    private suspend fun verifyProgramView(authentication: AuthenticationContext, programId: UUID) {
        val program = programService.getById(programId)
            ?: error("Program $programId not found")
        programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
    }

    private suspend fun verifyReleaseView(authentication: AuthenticationContext, releaseId: UUID) {
        val release = releaseService.getById(releaseId)
            ?: error("Release $releaseId not found")
        verifyProgramView(authentication, release.programId)
    }

    @Field
    suspend fun dependencies(authentication: AuthenticationContext, projectId: UUID): List<DependencyDeclaration> {
        verifyProjectView(authentication, projectId)
        return depService.listByConsumer(projectId)
    }

    @Field
    suspend fun consumers(authentication: AuthenticationContext, projectId: UUID): List<DependencyDeclaration> {
        verifyProjectView(authentication, projectId)
        return depService.listByProvider(projectId)
    }

    @Field
    suspend fun dependency(authentication: AuthenticationContext, id: UUID): DependencyDeclaration? {
        val dep = depService.getById(id) ?: return null
        val project = projectService.getById(dep.consumerProjectId) ?: return null
        if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return null
        return dep
    }

    @Field
    suspend fun artifacts(authentication: AuthenticationContext, versionId: UUID): List<ArtifactPublication> {
        val results = artifactService.listByVersion(versionId)
        if (results.isEmpty()) return emptyList()
        val project = projectService.getById(results.first().projectId) ?: return emptyList()
        if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return emptyList()
        return results
    }

    @Field
    suspend fun artifact(authentication: AuthenticationContext, id: UUID): ArtifactPublication? {
        val artifact = artifactService.getById(id) ?: return null
        val project = projectService.getById(artifact.projectId) ?: return null
        if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return null
        return artifact
    }

    @Field
    suspend fun apiSurfaceReports(authentication: AuthenticationContext, versionId: UUID): List<ApiSurfaceReport> {
        val results = apiSurfaceService.listByVersion(versionId)
        if (results.isEmpty()) return emptyList()
        val project = projectService.getById(results.first().projectId) ?: return emptyList()
        if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return emptyList()
        return results
    }

    @Field
    suspend fun pipelineRuns(authentication: AuthenticationContext, projectId: UUID): List<PipelineRun> {
        verifyProjectView(authentication, projectId)
        return pipelineService.listByProject(projectId)
    }

    @Field
    suspend fun pipelineRun(authentication: AuthenticationContext, id: UUID): PipelineRun? {
        val run = pipelineService.getById(id) ?: return null
        val project = projectService.getById(run.projectId) ?: return null
        if (!projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW)) return null
        return run
    }

    @Field
    suspend fun environments(authentication: AuthenticationContext, programId: UUID): List<Environment> {
        verifyProgramView(authentication, programId)
        return envService.listByProgram(programId)
    }

    /** The global environment type catalog — what pipelines reference and environments instantiate. */
    @Field
    suspend fun environmentTypes(authentication: AuthenticationContext): List<EnvironmentType> = envTypeService.list()

    @Field
    suspend fun environmentState(authentication: AuthenticationContext, environmentId: UUID): List<EnvironmentDeployment> {
        val env = envService.getById(environmentId) ?: return emptyList()
        if (!programPermissions.isAllowed(authentication, programService.getById(env.programId) ?: return emptyList(), PermissionAction.VIEW)) return emptyList()
        return envService.currentState(environmentId)
    }

    @Field
    suspend fun environmentDrift(
        authentication: AuthenticationContext,
        environmentId: UUID,
        releaseId: UUID,
    ): List<EnvironmentDrift> {
        val env = envService.getById(environmentId) ?: return emptyList()
        val program = programService.getById(env.programId) ?: return emptyList()
        if (!programPermissions.isAllowed(authentication, program, PermissionAction.VIEW)) return emptyList()
        return envService.environmentDrift(environmentId, releaseId)
    }

    @Field
    suspend fun environmentCompatibleProjects(
        authentication: AuthenticationContext,
        environmentId: UUID,
        releaseId: UUID,
    ): List<UUID> {
        val env = envService.getById(environmentId) ?: return emptyList()
        val program = programService.getById(env.programId) ?: return emptyList()
        if (!programPermissions.isAllowed(authentication, program, PermissionAction.VIEW)) return emptyList()
        return envService.channelCompatibleProjects(environmentId, releaseId)
    }

    @Field
    suspend fun compatibilityResults(
        authentication: AuthenticationContext,
        consumerProjectId: UUID,
        consumerVersionId: UUID,
    ): List<CompatibilityTestResult> {
        verifyProjectView(authentication, consumerProjectId)
        return compatService.listByConsumerVersion(consumerProjectId, consumerVersionId)
    }

    @Field
    suspend fun releaseNotes(authentication: AuthenticationContext, releaseId: UUID): ReleaseNotes? {
        verifyReleaseView(authentication, releaseId)
        return releaseNotesService.getByRelease(releaseId)
    }

    @Field
    suspend fun versionReleaseNotes(authentication: AuthenticationContext, releaseId: UUID): List<VersionReleaseNotes> {
        verifyReleaseView(authentication, releaseId)
        return releaseNotesService.listVersionNotes(releaseId)
    }

    @Field
    suspend fun buildReadiness(authentication: AuthenticationContext, projectId: UUID): BuildReadiness {
        verifyProjectView(authentication, projectId)
        return buildReadinessService.check(projectId)
    }
}
