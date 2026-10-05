package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.CreatedDeployConfig
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.model.artifact.ApiSurfaceReport
import bosca.workops.model.artifact.ArtifactPublication
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.BreakingChangeLevel
import bosca.workops.model.artifact.PublicationStatus
import bosca.workops.model.artifact.RegisterArtifactInput
import bosca.workops.model.compatibility.CompatibilityStatus
import bosca.workops.model.compatibility.CompatibilityTestResult
import bosca.workops.model.compatibility.RecordCompatibilityResultInput
import bosca.workops.model.dependency.BuildReadiness
import bosca.workops.model.dependency.CreateDependencyDeclarationInput
import bosca.workops.model.dependency.DependencyDeclaration
import bosca.workops.model.dependency.DependencyStatus
import bosca.workops.model.dependency.DependencyType
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
import bosca.workops.model.pipeline.PipelineTriggerType
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.release.Release
import bosca.workops.model.release.LocalizedReleaseNotes
import bosca.workops.model.release.ReleaseNotes
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.release.VersionReleaseNotes
import bosca.workops.service.ApiSurfaceReportService
import bosca.workops.service.ArtifactPublicationService
import bosca.workops.service.BuildReadinessService
import bosca.workops.service.CompatibilityTestResultService
import bosca.workops.service.DependencyDeclarationService
import bosca.workops.service.EnvironmentDrift
import bosca.workops.service.EnvironmentDriftType
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Permission-focused tests for MultiRepoController.
 *
 * Pre-fix MultiRepoController had ~15 query endpoints and ~20 mutation
 * endpoints with no permission checks at all (cross-project SDLC data leak;
 * anyone could waive release gates, yank artifacts, fake deployments, etc.).
 *
 * These tests cover representative endpoints in each category: project-scoped
 * read, project-scoped mutation, program-scoped, release-scoped. Each
 * endpoint's permission gate is the same shape, so verifying a handful is
 * sufficient to catch regressions in the protective helpers.
 */
class MultiRepoControllerTest {

    private val depService = mockk<DependencyDeclarationService>(relaxed = true)
    private val artifactService = mockk<ArtifactPublicationService>()
    private val apiSurfaceService = mockk<ApiSurfaceReportService>(relaxed = true)
    private val pipelineService = mockk<PipelineRunService>(relaxed = true)
    private val envService = mockk<EnvironmentService>(relaxed = true)
    private val envTypeService = mockk<EnvironmentTypeService>(relaxed = true)
    private val deployConfigService = mockk<bosca.workops.deploy.DeployConfigService>(relaxed = true)
    private val compatService = mockk<CompatibilityTestResultService>(relaxed = true)
    private val releaseNotesService = mockk<ReleaseNotesService>(relaxed = true)
    private val releaseDeploymentService = mockk<ReleaseDeploymentService>(relaxed = true)
    private val releaseService = mockk<ReleaseService>(relaxed = true)
    private val buildReadinessService = mockk<BuildReadinessService>(relaxed = true)
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>()
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun administrator(groupName: String = "administrators"): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profileId)
        val groups = listOf(Group(id = UUID.random(), name = groupName, description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun queryController() = MultiRepoQueryController(
        depService = depService,
        artifactService = artifactService,
        apiSurfaceService = apiSurfaceService,
        pipelineService = pipelineService,
        envService = envService,
        envTypeService = envTypeService,
        compatService = compatService,
        releaseNotesService = releaseNotesService,
        buildReadinessService = buildReadinessService,
        releaseService = releaseService,
        projectService = projectService,
        projectPermissions = projectPermissions,
        programPermissions = programPermissions,
        programService = programService,
    )

    private fun mutationController() = MultiRepoMutationController(
        depService = depService,
        artifactService = artifactService,
        apiSurfaceService = apiSurfaceService,
        pipelineService = pipelineService,
        envService = envService,
        envTypeService = envTypeService,
        deployConfigService = deployConfigService,
        compatService = compatService,
        releaseNotesService = releaseNotesService,
        releaseDeploymentService = releaseDeploymentService,
        releaseService = releaseService,
        projectService = projectService,
        projectPermissions = projectPermissions,
        programPermissions = programPermissions,
        programService = programService,
        json = json,
    )

    // --- Query: artifact (project-scoped) ---

    @Test
    fun `artifact returns null when caller lacks VIEW on project`() = runTest {
        val artifact = sampleArtifact()
        val project = sampleProject(artifact.projectId)
        val missingArtifactId = UUID.random()
        val missingProjectArtifact = sampleArtifact()
        coEvery { artifactService.getById(artifact.id) } returns artifact
        coEvery { artifactService.getById(missingArtifactId) } returns null
        coEvery { artifactService.getById(missingProjectArtifact.id) } returns missingProjectArtifact
        coEvery { projectService.getById(artifact.projectId) } returns project
        coEvery { projectService.getById(missingProjectArtifact.projectId) } returns null
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returns false

        assertNull(queryController().artifact(authenticated(), missingArtifactId))
        assertNull(queryController().artifact(authenticated(), missingProjectArtifact.id))
        assertNull(queryController().artifact(authenticated(), artifact.id))
    }

    @Test
    fun `artifact returns data when caller has VIEW on project`() = runTest {
        val artifact = sampleArtifact()
        val project = sampleProject(artifact.projectId)
        coEvery { artifactService.getById(artifact.id) } returns artifact
        coEvery { projectService.getById(artifact.projectId) } returns project
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returns true

        assertEquals(artifact, queryController().artifact(authenticated(), artifact.id))
    }

    // --- Mutation: yankArtifact (project-scoped MANAGE) ---

    @Test
    fun `yankArtifact throws when caller lacks MANAGE on project`() = runTest {
        val artifact = sampleArtifact()
        val project = sampleProject(artifact.projectId)
        coEvery { artifactService.getById(artifact.id) } returns artifact
        coEvery { projectService.getById(artifact.projectId) } returns project
        coEvery {
            projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            mutationController().yankArtifact(authenticated(), artifact.id, expectedVersion = 0L)
        }
        coVerify(exactly = 0) { artifactService.yank(any(), any()) }
    }

    @Test
    fun `yankArtifact errors when artifact not found`() = runTest {
        val artifactId = UUID.random()
        coEvery { artifactService.getById(artifactId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().yankArtifact(authenticated(), artifactId, expectedVersion = 0L)
        }
    }

    // --- Query: environmentState (program-scoped) ---

    @Test
    fun `environmentState returns empty when caller lacks VIEW on program`() = runTest {
        val environmentId = UUID.random()
        val programId = UUID.random()
        val env = bosca.workops.model.environment.Environment(
            id = environmentId,
            programId = programId,
            key = "prod",
            name = "prod",
            description = null,
            displayOrder = 0,
            requiresApproval = false,
            autoPromote = false,
        )
        val program = bosca.workops.model.project.Program(
            id = programId, portfolioId = UUID.random(),
            key = "PROG", name = "Program", ownerProfileId = profileId,
        )
        coEvery { envService.getById(environmentId) } returns env
        coEvery { programService.getById(programId) } returns program
        coEvery { programPermissions.isAllowed(any<AuthenticationContext>(), program, PermissionAction.VIEW) } returns false

        assertTrue(queryController().environmentState(authenticated(), environmentId).isEmpty())
    }

    @Test
    fun `deploy preserves the target key when recording independent target state`() = runTest {
        val environmentId = UUID.random()
        val projectId = UUID.random()
        val versionId = UUID.random()
        val appBuildNumberAllocationId = UUID.random()
        val programId = UUID.random()
        val project = sampleProject(projectId).copy(programId = programId)
        val environment = bosca.workops.model.environment.Environment(
            id = environmentId,
            programId = programId,
            key = "production",
            name = "Production",
        )
        val input = DeployInput(
            environmentId = environmentId,
            projectId = projectId,
            targetKind = DeployTargetKind.GOOGLE_PLAY,
            versionId = versionId,
            appBuildNumberAllocationId = appBuildNumberAllocationId,
        )
        val captured = slot<DeployInput>()
        val deployment = EnvironmentDeployment(
            environmentId = environmentId,
            projectId = projectId,
            targetKind = DeployTargetKind.GOOGLE_PLAY,
            versionId = versionId,
        )
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { envService.getById(environmentId) } returns environment
        coEvery { envService.createDeployment(capture(captured), principalId) } returns deployment

        val result = mutationController().deploy(authenticated(), input)

        assertEquals(deployment, result)
        assertEquals(DeployTargetKind.GOOGLE_PLAY, captured.captured.targetKind)
        assertEquals(appBuildNumberAllocationId, captured.captured.appBuildNumberAllocationId)
        assertEquals(input, captured.captured)
        assertEquals(DeployTargetKind.HELM, input.copy(targetKind = DeployTargetKind.HELM).targetKind)
    }

    @Test
    fun `canonical dependency input reaches the service unchanged`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        val input = CreateDependencyDeclarationInput(
            consumerProjectId = projectId,
            consumerVersionId = UUID.random(),
            providerProjectId = UUID.random(),
            providerVersionConstraint = "^2.0",
            resolvedProviderVersionId = UUID.random(),
            dependencyType = DependencyType.RUNTIME,
            artifactCoordinates = "io.bosca:core:2.0",
        )
        val declared = mockk<DependencyDeclaration>()
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { depService.declare(input) } returns declared

        assertEquals(declared, mutationController().declareDependency(authenticated(), input))
        coVerify(exactly = 1) { depService.declare(input) }
    }

    @Test
    fun `canonical artifact input preserves registry routing fields`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        val input = RegisterArtifactInput(
            versionId = UUID.random(),
            projectId = projectId,
            artifactType = ArtifactType.ANDROID_AAR,
            coordinates = "mobile/1.4.0/mobile.aab",
            namespace = "bosca-raw",
            environments = listOf("internal", "production"),
        )
        val publication = sampleArtifact().copy(projectId = projectId)
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { artifactService.register(input) } returns publication

        assertEquals(publication, mutationController().registerArtifact(authenticated(), input))
        coVerify(exactly = 1) { artifactService.register(input) }
    }

    @Test
    fun `canonical pipeline and compatibility inputs reach their services unchanged`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit

        val pipelineInput = CreatePipelineRunInput(
            projectId = projectId,
            versionId = UUID.random(),
            pipelineId = "build-and-test",
            pipelineName = "Build and test",
            triggerType = PipelineTriggerType.TAG,
            triggerRef = "1.4.0",
            externalUrl = "https://ci.example/run/42",
        )
        val pipelineRun = mockk<PipelineRun>()
        coEvery { pipelineService.create(pipelineInput) } returns pipelineRun
        assertEquals(pipelineRun, mutationController().createPipelineRun(authenticated(), pipelineInput))

        val compatibilityInput = RecordCompatibilityResultInput(
            consumerProjectId = projectId,
            consumerVersionId = UUID.random(),
            providerProjectId = UUID.random(),
            providerVersionId = UUID.random(),
            testSuite = "binary-api",
            status = CompatibilityStatus.PASSED,
            breakingChangesDetected = true,
            pipelineRunId = UUID.random(),
            resultUrl = "https://ci.example/result/42",
        )
        val compatibility = mockk<CompatibilityTestResult>()
        coEvery { compatService.record(compatibilityInput) } returns compatibility
        assertEquals(
            compatibility,
            mutationController().recordCompatibilityResult(authenticated(), compatibilityInput),
        )

        coVerify(exactly = 1) { pipelineService.create(pipelineInput) }
        coVerify(exactly = 1) { compatService.record(compatibilityInput) }
    }

    @Test
    fun `dependency lifecycle verifies the consumer project and delegates typed statuses`() = runTest {
        val project = sampleProject(UUID.random())
        val dependency = DependencyDeclaration(
            id = UUID.random(),
            consumerProjectId = project.id,
            providerProjectId = UUID.random(),
            providerVersionConstraint = "^1.0",
            dependencyType = DependencyType.RUNTIME,
        )
        val resolvedVersionId = UUID.random()
        coEvery { depService.getById(dependency.id) } returns dependency
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { depService.updateStatus(dependency.id, DependencyStatus.OUTDATED, 4) } returns
            dependency.copy(status = DependencyStatus.OUTDATED, version = 5)
        coEvery {
            depService.updateResolvedVersion(dependency.id, resolvedVersionId, DependencyStatus.CURRENT, 5)
        } returns dependency.copy(resolvedProviderVersionId = resolvedVersionId, version = 6)

        assertEquals(
            DependencyStatus.OUTDATED,
            mutationController().updateDependencyStatus(authenticated(), dependency.id, "OUTDATED", 4).status,
        )
        assertEquals(
            resolvedVersionId,
            mutationController().updateResolvedVersion(
                authenticated(), dependency.id, resolvedVersionId, "CURRENT", 5,
            ).resolvedProviderVersionId,
        )
        assertTrue(mutationController().removeDependency(authenticated(), dependency.id))

        coVerify(exactly = 1) { depService.remove(dependency.id) }
    }

    @Test
    fun `artifact transitions and API report registration preserve caller data`() = runTest {
        val project = sampleProject(UUID.random())
        val artifact = sampleArtifact().copy(projectId = project.id)
        coEvery { artifactService.getById(artifact.id) } returns artifact
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { artifactService.markPublished(artifact.id, principalId, 2) } returns
            artifact.copy(status = PublicationStatus.PUBLISHED, version = 3)
        coEvery { artifactService.markFailed(artifact.id, 3) } returns artifact.copy(status = PublicationStatus.FAILED, version = 4)
        coEvery { artifactService.yank(artifact.id, 4) } returns artifact.copy(status = PublicationStatus.YANKED, version = 5)

        assertEquals(PublicationStatus.PUBLISHED, mutationController().markArtifactPublished(authenticated(), artifact.id, 2).status)
        assertEquals(PublicationStatus.FAILED, mutationController().markArtifactFailed(authenticated(), artifact.id, 3).status)
        assertEquals(PublicationStatus.YANKED, mutationController().yankArtifact(authenticated(), artifact.id, 4).status)

        val changes = json.parseToJsonElement("[{\"symbol\":\"Example\"}]")
        val input = RegisterApiSurfaceReportInput(
            projectId = project.id,
            versionId = UUID.random(),
            previousVersionId = UUID.random(),
            artifactPublicationId = artifact.id,
            breakingChangeLevel = BreakingChangeLevel.MINOR_BREAKING,
            changes = changes,
            analyzerTool = "japicmp",
            reportUrl = "https://ci.example/report",
        )
        val report = ApiSurfaceReport(
            projectId = input.projectId,
            versionId = input.versionId,
            previousVersionId = input.previousVersionId,
            artifactPublicationId = input.artifactPublicationId,
            breakingChangeLevel = input.breakingChangeLevel,
            changes = input.changes,
            analyzerTool = input.analyzerTool,
            reportUrl = input.reportUrl,
        )
        val capturedReport = slot<ApiSurfaceReport>()
        coEvery { apiSurfaceService.register(capture(capturedReport), json) } returns report

        assertEquals(report, mutationController().registerApiSurfaceReport(authenticated(), input))
        assertEquals(input.projectId, capturedReport.captured.projectId)
        assertEquals(input.versionId, capturedReport.captured.versionId)
        assertEquals(input.previousVersionId, capturedReport.captured.previousVersionId)
        assertEquals(input.artifactPublicationId, capturedReport.captured.artifactPublicationId)
        assertEquals(input.breakingChangeLevel, capturedReport.captured.breakingChangeLevel)
        assertEquals(input.changes, capturedReport.captured.changes)
        assertEquals(input.analyzerTool, capturedReport.captured.analyzerTool)
        assertEquals(input.reportUrl, capturedReport.captured.reportUrl)
    }

    @Test
    fun `pipeline lifecycle resolves project ownership before each transition`() = runTest {
        val project = sampleProject(UUID.random())
        val run = PipelineRun(
            id = UUID.random(),
            projectId = project.id,
            pipelineId = "release",
            pipelineName = "Release",
            triggerType = PipelineTriggerType.TAG,
        )
        val stage = PipelineStageRun(
            id = UUID.random(),
            pipelineRunId = run.id,
            stageName = "test",
        )
        coEvery { pipelineService.getById(run.id) } returns run
        coEvery { pipelineService.getStageById(stage.id) } returns stage
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { pipelineService.complete(run.id, PipelineStatus.PASSED, 3) } returns run.copy(status = PipelineStatus.PASSED)
        coEvery { pipelineService.addStage(run.id, "publish", null) } returns stage.copy(stageName = "publish")
        coEvery { pipelineService.completeStage(stage.id, PipelineStatus.FAILED) } returns stage.copy(status = PipelineStatus.FAILED)

        assertEquals(PipelineStatus.PASSED, mutationController().completePipelineRun(authenticated(), run.id, "PASSED", 3).status)
        assertEquals("publish", mutationController().addPipelineStage(authenticated(), run.id, "publish", null).stageName)
        assertEquals(PipelineStatus.FAILED, mutationController().completePipelineStage(authenticated(), stage.id, "FAILED").status)
    }

    @Test
    fun `administrators manage the global environment type catalog`() = runTest {
        val type = EnvironmentType(id = UUID.random(), name = "production", displayOrder = 7)
        coEvery { envTypeService.create("production", null, 0) } returns type
        coEvery { envTypeService.getById(type.id) } returns type
        coEvery { envTypeService.update(type.id, "prod", "Production", 7, 2) } returns
            type.copy(name = "prod", description = "Production", version = 3)

        assertEquals(type, mutationController().createEnvironmentType(administrator("sa"), "production", null, null))
        assertEquals(
            "prod",
            mutationController().updateEnvironmentType(
                administrator(), type.id, "prod", "Production", null, 2,
            ).name,
        )
        assertTrue(mutationController().deleteEnvironmentType(administrator(), type.id))
        coVerify(exactly = 1) { envTypeService.delete(type.id) }
    }

    @Test
    fun `environment lifecycle applies patch defaults and delegates permissions`() = runTest {
        val program = sampleProgram()
        val project = sampleProject(UUID.random()).copy(programId = program.id)
        val type = EnvironmentType(id = UUID.random(), name = "production")
        val environment = sampleEnvironment(program.id, type.id)
        val createInput = CreateEnvironmentInput(
            programId = program.id,
            key = "production",
            name = "Production",
            typeId = type.id,
        )
        val patch = PatchEnvironmentInput(
            name = "Production renamed",
            description = "Primary",
            typeId = type.id,
        )
        val expectedUpdate = UpdateEnvironmentInput(
            name = patch.name,
            description = patch.description,
            displayOrder = environment.displayOrder,
            promotionSourceIds = patch.promotionSourceIds,
            requiresApproval = environment.requiresApproval,
            autoPromote = environment.autoPromote,
            typeId = type.id,
            targetType = environment.targetType,
            targetRef = environment.targetRef,
            ephemeral = environment.ephemeral,
        )
        val groupId = UUID.random()
        val createdConfig = CreatedDeployConfig(UUID.random(), "main", ".bosca/deploy.yaml", "abc123", "environments: {}")
        coEvery { programService.getById(program.id) } returns program
        coEvery { programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE) } returns Unit
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { envTypeService.getById(type.id) } returns type
        coEvery { envService.getById(environment.id) } returns environment
        coEvery { envService.create(createInput) } returns environment
        coEvery { envService.update(environment.id, expectedUpdate, 6) } returns environment.copy(name = patch.name, version = 7)
        coEvery {
            deployConfigService.createDeployConfig(project.id, createdConfig.repositoryId, "Bosca", "bosca@example.com")
        } returns createdConfig

        assertEquals(environment, mutationController().createEnvironment(authenticated(), createInput))
        assertEquals(patch.name, mutationController().updateEnvironment(authenticated(), environment.id, patch, 6).name)
        assertTrue(mutationController().addEnvironmentPermission(authenticated(), environment.id, groupId, PermissionAction.EXECUTE))
        assertTrue(mutationController().removeEnvironmentPermission(authenticated(), environment.id, groupId, PermissionAction.EXECUTE))
        assertTrue(mutationController().deleteEnvironment(authenticated(), environment.id))
        assertEquals(
            createdConfig,
            mutationController().createProjectDeployConfig(
                authenticated(), project.id, createdConfig.repositoryId, "Bosca", "bosca@example.com",
            ),
        )

        coVerify(exactly = 1) { envService.addPermission(environment.id, groupId, PermissionAction.EXECUTE) }
        coVerify(exactly = 1) { envService.removePermission(environment.id, groupId, PermissionAction.EXECUTE) }
        coVerify(exactly = 1) { envService.delete(environment.id) }
    }

    @Test
    fun `deployment transitions attribute the authenticated principal and use typed health status`() = runTest {
        val project = sampleProject(UUID.random())
        val deployment = EnvironmentDeployment(
            id = UUID.random(),
            environmentId = UUID.random(),
            projectId = project.id,
            versionId = UUID.random(),
        )
        coEvery { envService.getDeployment(deployment.id) } returns deployment
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { envService.markDeployed(deployment.id, principalId, 8) } returns deployment.copy(version = 9)
        coEvery { envService.updateHealthCheck(deployment.id, HealthCheckStatus.HEALTHY, 9) } returns deployment.copy(version = 10)

        assertEquals(9, mutationController().markDeployed(authenticated(), deployment.id, 8).version)
        assertEquals(10, mutationController().updateHealthCheck(authenticated(), deployment.id, "HEALTHY", 9).version)
    }

    @Test
    fun `release deployment and notes operations verify the release program`() = runTest {
        val program = sampleProgram()
        val release = Release(id = UUID.random(), programId = program.id, name = "1.4.0")
        val projectId = UUID.random()
        val versionId = UUID.random()
        val rollbackVersionId = UUID.random()
        val releaseProjectVersion = ReleaseProjectVersion(release.id, projectId, versionId)
        val notes = ReleaseNotes(releaseId = release.id)
        val localizedVariants = listOf(LocalizedReleaseNotes("en-US", "Play", "App Store", "TestFlight"))
        val localized = VersionReleaseNotes(
            versionId = versionId,
            sourceLocale = "en-US",
            variants = json.encodeToJsonElement(ListSerializer(LocalizedReleaseNotes.serializer()), localizedVariants),
        )
        val sections = json.parseToJsonElement("{\"summary\":\"ready\"}")
        val encodedSections = json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), sections)
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { programService.getById(program.id) } returns program
        coEvery { programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE) } returns Unit
        coEvery { releaseDeploymentService.setDeploymentOrder(release.id, projectId, versionId, 3) } returns releaseProjectVersion
        coEvery { releaseDeploymentService.markDeploying(release.id, projectId, versionId, principalId) } returns releaseProjectVersion
        coEvery { releaseDeploymentService.markDeployed(release.id, projectId, versionId, principalId) } returns releaseProjectVersion
        coEvery {
            releaseDeploymentService.markRolledBack(release.id, projectId, versionId, rollbackVersionId)
        } returns releaseProjectVersion
        coEvery { releaseNotesService.generate(release.id, encodedSections) } returns notes
        coEvery { releaseNotesService.autoGenerate(release.id) } returns notes
        coEvery { releaseNotesService.editManually(release.id, encodedSections, 2) } returns notes.copy(manuallyEdited = true)
        coEvery { releaseNotesService.autoGenerateLocalized(release.id, principalId) } returns listOf(localized)
        coEvery {
            releaseNotesService.editVersionNotes(
                release.id, versionId, any(), principalId,
            )
        } returns localized.copy(manuallyEdited = true)

        assertEquals(releaseProjectVersion, mutationController().setDeploymentOrder(authenticated(), release.id, projectId, versionId, 3))
        assertEquals(releaseProjectVersion, mutationController().markProjectVersionDeploying(authenticated(), release.id, projectId, versionId))
        assertEquals(releaseProjectVersion, mutationController().markProjectVersionDeployed(authenticated(), release.id, projectId, versionId))
        assertEquals(
            releaseProjectVersion,
            mutationController().markProjectVersionRolledBack(
                authenticated(), release.id, projectId, versionId, rollbackVersionId,
            ),
        )
        assertEquals(notes, mutationController().generateReleaseNotes(authenticated(), release.id, sections))
        assertEquals(notes, mutationController().autoGenerateReleaseNotes(authenticated(), release.id))
        assertTrue(mutationController().editReleaseNotes(authenticated(), release.id, sections, 2).manuallyEdited)
        assertEquals(
            listOf(localized),
            mutationController().autoGenerateVersionReleaseNotes(authenticated(), release.id),
        )
        assertTrue(
            mutationController().editVersionReleaseNotes(
                authenticated(), release.id, versionId, localized.variants,
            ).manuallyEdited,
        )
    }

    @Test
    fun `mutation scope helpers fail closed for missing resources and unauthenticated callers`() = runTest {
        val missingId = UUID.random()
        coEvery { projectService.getById(missingId) } returns null
        coEvery { releaseService.getById(missingId) } returns null

        assertFailsWith<IllegalStateException> {
            mutationController().createProjectDeployConfig(authenticated(), missingId, UUID.random(), "Bosca", "bosca@example.com")
        }
        assertFailsWith<IllegalStateException> {
            mutationController().setDeploymentOrder(authenticated(), missingId, UUID.random(), UUID.random(), 1)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().createEnvironmentType(AuthenticationContext(null, null), "prod", null, null)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().createEnvironmentType(authenticated(), "prod", null, null)
        }
    }

    @Test
    fun `mutation resource lookups fail closed before invoking transition services`() = runTest {
        val missingProgramId = UUID.random()
        val typeId = UUID.random()
        val createInput = CreateEnvironmentInput(
            programId = missingProgramId,
            key = "production",
            name = "Production",
            typeId = typeId,
        )
        coEvery { programService.getById(missingProgramId) } returns null
        assertFailsWith<IllegalStateException> { mutationController().createEnvironment(authenticated(), createInput) }

        val project = sampleProject(UUID.random())
        val program = sampleProgram()
        val environment = sampleEnvironment(program.id, typeId)
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { programService.getById(program.id) } returns program
        coEvery { programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE) } returns Unit
        coEvery { envTypeService.getById(typeId) } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().createEnvironment(authenticated(), createInput.copy(programId = program.id))
        }

        val missingDependencyIds = List(3) { UUID.random() }
        missingDependencyIds.forEach { coEvery { depService.getById(it) } returns null }
        assertFailsWith<IllegalStateException> {
            mutationController().updateDependencyStatus(authenticated(), missingDependencyIds[0], "CURRENT", 0)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().updateResolvedVersion(authenticated(), missingDependencyIds[1], null, "CURRENT", 0)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().removeDependency(authenticated(), missingDependencyIds[2])
        }

        val missingArtifactIds = List(2) { UUID.random() }
        missingArtifactIds.forEach { coEvery { artifactService.getById(it) } returns null }
        assertFailsWith<IllegalStateException> {
            mutationController().markArtifactPublished(authenticated(), missingArtifactIds[0], 0)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().markArtifactFailed(authenticated(), missingArtifactIds[1], 0)
        }

        val missingRunIds = List(3) { UUID.random() }
        coEvery { pipelineService.getById(missingRunIds[0]) } returns null
        coEvery { pipelineService.getById(missingRunIds[1]) } returns null
        coEvery { pipelineService.getStageById(missingRunIds[2]) } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().completePipelineRun(authenticated(), missingRunIds[0], "PASSED", 0)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().addPipelineStage(authenticated(), missingRunIds[1], "test", null)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().completePipelineStage(authenticated(), missingRunIds[2], "PASSED")
        }
        val orphanStage = PipelineStageRun(pipelineRunId = UUID.random(), stageName = "test")
        coEvery { pipelineService.getStageById(orphanStage.id) } returns orphanStage
        coEvery { pipelineService.getById(orphanStage.pipelineRunId) } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().completePipelineStage(authenticated(), orphanStage.id, "PASSED")
        }

        val missingEnvironmentIds = List(5) { UUID.random() }
        missingEnvironmentIds.forEach { coEvery { envService.getById(it) } returns null }
        assertFailsWith<IllegalStateException> {
            mutationController().updateEnvironment(
                authenticated(),
                missingEnvironmentIds[0],
                PatchEnvironmentInput(name = "Production", typeId = typeId),
                0,
            )
        }
        assertFailsWith<IllegalStateException> { mutationController().deleteEnvironment(authenticated(), missingEnvironmentIds[1]) }
        assertFailsWith<IllegalStateException> {
            mutationController().addEnvironmentPermission(
                authenticated(), missingEnvironmentIds[2], UUID.random(), PermissionAction.EXECUTE,
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().removeEnvironmentPermission(
                authenticated(), missingEnvironmentIds[3], UUID.random(), PermissionAction.EXECUTE,
            )
        }
        assertFailsWith<IllegalStateException> {
            mutationController().deploy(
                authenticated(),
                DeployInput(missingEnvironmentIds[4], project.id, versionId = UUID.random()),
            )
        }

        coEvery { envService.getById(environment.id) } returns environment
        assertFailsWith<IllegalStateException> {
            mutationController().updateEnvironment(
                authenticated(),
                environment.id,
                PatchEnvironmentInput(name = "Production", typeId = typeId),
                environment.version,
            )
        }

        val missingDeploymentIds = List(2) { UUID.random() }
        missingDeploymentIds.forEach { coEvery { envService.getDeployment(it) } returns null }
        assertFailsWith<IllegalStateException> {
            mutationController().markDeployed(authenticated(), missingDeploymentIds[0], 0)
        }
        assertFailsWith<IllegalStateException> {
            mutationController().updateHealthCheck(authenticated(), missingDeploymentIds[1], "HEALTHY", 0)
        }

        coEvery { envTypeService.getById(typeId) } returns null
        assertFailsWith<IllegalStateException> {
            mutationController().updateEnvironmentType(administrator(), typeId, "prod", null, null, 0)
        }
    }

    @Test
    fun `explicit environment patch values replace every optional setting`() = runTest {
        val program = sampleProgram()
        val type = EnvironmentType(id = UUID.random(), name = "preview")
        val environment = sampleEnvironment(program.id, type.id)
        val sourceIds = listOf(UUID.random(), UUID.random())
        val patch = PatchEnvironmentInput(
            name = "Preview",
            description = "On demand",
            displayOrder = 2,
            promotionSourceIds = sourceIds,
            requiresApproval = false,
            autoPromote = false,
            typeId = type.id,
            targetType = EnvironmentTargetType.GENERIC,
            targetRef = "preview-42",
            ephemeral = false,
        )
        val expected = UpdateEnvironmentInput(
            name = patch.name,
            description = patch.description,
            displayOrder = 2,
            promotionSourceIds = sourceIds,
            requiresApproval = false,
            autoPromote = false,
            typeId = type.id,
            targetType = EnvironmentTargetType.GENERIC,
            targetRef = "preview-42",
            ephemeral = false,
        )
        coEvery { envService.getById(environment.id) } returns environment
        coEvery { programService.getById(program.id) } returns program
        coEvery { programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE) } returns Unit
        coEvery { envTypeService.getById(type.id) } returns type
        coEvery { envService.update(environment.id, expected, environment.version) } returns
            environment.copy(
                name = patch.name,
                description = patch.description,
                displayOrder = 2,
                requiresApproval = false,
                autoPromote = false,
                targetType = EnvironmentTargetType.GENERIC,
                targetRef = "preview-42",
                ephemeral = false,
            )

        val updated = mutationController().updateEnvironment(authenticated(), environment.id, patch, environment.version)

        assertEquals("Preview", updated.name)
        assertEquals(2, updated.displayOrder)
        assertEquals(EnvironmentTargetType.GENERIC, updated.targetType)
        assertEquals("preview-42", updated.targetRef)
    }

    @Test
    fun `explicit environment type order is used and attribution requires a principal`() = runTest {
        val type = EnvironmentType(id = UUID.random(), name = "production", displayOrder = 4)
        coEvery { envTypeService.create("production", null, 4) } returns type
        coEvery { envTypeService.getById(type.id) } returns type
        coEvery { envTypeService.update(type.id, "production", null, 11, 0) } returns type.copy(displayOrder = 11)
        assertEquals(4, mutationController().createEnvironmentType(administrator(), "production", null, 4).displayOrder)
        assertEquals(
            11,
            mutationController().updateEnvironmentType(administrator(), type.id, "production", null, 11, 0).displayOrder,
        )

        val project = sampleProject(UUID.random())
        val artifact = sampleArtifact().copy(projectId = project.id)
        coEvery { artifactService.getById(artifact.id) } returns artifact
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        assertFailsWith<IllegalStateException> {
            mutationController().markArtifactPublished(AuthenticationContext(null, null), artifact.id, 0)
        }
    }

    @Test
    fun `project scoped queries verify view access and delegate to each service`() = runTest {
        val project = sampleProject(UUID.random())
        val dependency = DependencyDeclaration(
            consumerProjectId = project.id,
            providerProjectId = UUID.random(),
            providerVersionConstraint = "^1.0",
            dependencyType = DependencyType.BUILD,
        )
        val pipelineRun = PipelineRun(
            projectId = project.id,
            pipelineId = "verify",
            pipelineName = "Verify",
            triggerType = PipelineTriggerType.PR,
        )
        val compatibility = mockk<CompatibilityTestResult>()
        val readiness = mockk<BuildReadiness>()
        val versionId = UUID.random()
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.VIEW) } returns Unit
        coEvery { depService.listByConsumer(project.id) } returns listOf(dependency)
        coEvery { depService.listByProvider(project.id) } returns listOf(dependency)
        coEvery { pipelineService.listByProject(project.id) } returns listOf(pipelineRun)
        coEvery { compatService.listByConsumerVersion(project.id, versionId) } returns listOf(compatibility)
        coEvery { buildReadinessService.check(project.id) } returns readiness

        assertEquals(listOf(dependency), queryController().dependencies(authenticated(), project.id))
        assertEquals(listOf(dependency), queryController().consumers(authenticated(), project.id))
        assertEquals(listOf(pipelineRun), queryController().pipelineRuns(authenticated(), project.id))
        assertEquals(listOf(compatibility), queryController().compatibilityResults(authenticated(), project.id, versionId))
        assertEquals(readiness, queryController().buildReadiness(authenticated(), project.id))
    }

    @Test
    fun `nullable project queries fail closed and return authorized records`() = runTest {
        val project = sampleProject(UUID.random())
        val dependency = DependencyDeclaration(
            id = UUID.random(),
            consumerProjectId = project.id,
            providerProjectId = UUID.random(),
            providerVersionConstraint = "^1.0",
            dependencyType = DependencyType.CONTRACT,
        )
        val pipelineRun = PipelineRun(
            id = UUID.random(),
            projectId = project.id,
            pipelineId = "verify",
            pipelineName = "Verify",
            triggerType = PipelineTriggerType.PUSH,
        )
        val missingDependencyId = UUID.random()
        val missingPipelineId = UUID.random()
        coEvery { depService.getById(missingDependencyId) } returns null
        coEvery { pipelineService.getById(missingPipelineId) } returns null
        coEvery { depService.getById(dependency.id) } returns dependency
        coEvery { pipelineService.getById(pipelineRun.id) } returns pipelineRun
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returnsMany listOf(false, true, false, true)

        assertNull(queryController().dependency(authenticated(), missingDependencyId))
        assertNull(queryController().pipelineRun(authenticated(), missingPipelineId))
        assertNull(queryController().dependency(authenticated(), dependency.id))
        assertEquals(dependency, queryController().dependency(authenticated(), dependency.id))
        assertNull(queryController().pipelineRun(authenticated(), pipelineRun.id))
        assertEquals(pipelineRun, queryController().pipelineRun(authenticated(), pipelineRun.id))

        val orphanProjectId = UUID.random()
        val orphanDependency = dependency.copy(id = UUID.random(), consumerProjectId = orphanProjectId)
        val orphanPipeline = pipelineRun.copy(id = UUID.random(), projectId = orphanProjectId)
        coEvery { depService.getById(orphanDependency.id) } returns orphanDependency
        coEvery { pipelineService.getById(orphanPipeline.id) } returns orphanPipeline
        coEvery { projectService.getById(orphanProjectId) } returns null
        assertNull(queryController().dependency(authenticated(), orphanDependency.id))
        assertNull(queryController().pipelineRun(authenticated(), orphanPipeline.id))
    }

    @Test
    fun `artifact and API report collections are empty unless their project is viewable`() = runTest {
        val project = sampleProject(UUID.random())
        val artifact = sampleArtifact().copy(projectId = project.id)
        val report = ApiSurfaceReport(
            projectId = project.id,
            versionId = artifact.versionId,
            previousVersionId = UUID.random(),
            breakingChangeLevel = BreakingChangeLevel.NONE,
            analyzerTool = "japicmp",
        )
        val emptyArtifactVersion = UUID.random()
        val missingArtifactProjectVersion = UUID.random()
        val deniedArtifactVersion = UUID.random()
        val emptyReportVersion = UUID.random()
        val missingReportProjectVersion = UUID.random()
        val deniedReportVersion = UUID.random()
        val missingProjectArtifact = artifact.copy(projectId = UUID.random())
        val missingProjectReport = report.copy(projectId = UUID.random())
        coEvery { artifactService.listByVersion(emptyArtifactVersion) } returns emptyList()
        coEvery { artifactService.listByVersion(missingArtifactProjectVersion) } returns listOf(missingProjectArtifact)
        coEvery { artifactService.listByVersion(deniedArtifactVersion) } returns listOf(artifact)
        coEvery { artifactService.listByVersion(artifact.versionId) } returns listOf(artifact)
        coEvery { apiSurfaceService.listByVersion(emptyReportVersion) } returns emptyList()
        coEvery { apiSurfaceService.listByVersion(missingReportProjectVersion) } returns listOf(missingProjectReport)
        coEvery { apiSurfaceService.listByVersion(deniedReportVersion) } returns listOf(report)
        coEvery { apiSurfaceService.listByVersion(report.versionId) } returns listOf(report)
        coEvery { projectService.getById(missingProjectArtifact.projectId) } returns null
        coEvery { projectService.getById(missingProjectReport.projectId) } returns null
        coEvery { projectService.getById(project.id) } returns project
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returnsMany
            listOf(false, true, false, true)

        assertTrue(queryController().artifacts(authenticated(), emptyArtifactVersion).isEmpty())
        assertTrue(queryController().artifacts(authenticated(), missingArtifactProjectVersion).isEmpty())
        assertTrue(queryController().artifacts(authenticated(), deniedArtifactVersion).isEmpty())
        assertEquals(listOf(artifact), queryController().artifacts(authenticated(), artifact.versionId))
        assertTrue(queryController().apiSurfaceReports(authenticated(), emptyReportVersion).isEmpty())
        assertTrue(queryController().apiSurfaceReports(authenticated(), missingReportProjectVersion).isEmpty())
        assertTrue(queryController().apiSurfaceReports(authenticated(), deniedReportVersion).isEmpty())
        assertEquals(listOf(report), queryController().apiSurfaceReports(authenticated(), report.versionId))
    }

    @Test
    fun `program and release queries verify visibility before returning operational state`() = runTest {
        val program = sampleProgram()
        val type = EnvironmentType(id = UUID.random(), name = "production")
        val environment = sampleEnvironment(program.id, type.id)
        val deployment = EnvironmentDeployment(
            environmentId = environment.id,
            projectId = UUID.random(),
            versionId = UUID.random(),
        )
        val release = Release(id = UUID.random(), programId = program.id, name = "1.4.0")
        val notes = ReleaseNotes(releaseId = release.id)
        val versionNotes = VersionReleaseNotes(
            versionId = UUID.random(),
            sourceLocale = "en-US",
            variants = json.parseToJsonElement("[]"),
        )
        val drift = EnvironmentDrift(
            projectId = deployment.projectId,
            deployedVersionId = deployment.versionId,
            expectedVersionId = UUID.random(),
            driftType = EnvironmentDriftType.VERSION_MISMATCH,
        )
        coEvery { programService.getById(program.id) } returns program
        coEvery { programPermissions.verifyAllowed(any(), program, PermissionAction.VIEW) } returns Unit
        coEvery { programPermissions.isAllowed(any<AuthenticationContext>(), program, PermissionAction.VIEW) } returns true
        coEvery { envService.listByProgram(program.id) } returns listOf(environment)
        coEvery { envTypeService.list() } returns listOf(type)
        coEvery { envService.getById(environment.id) } returns environment
        coEvery { envService.currentState(environment.id) } returns listOf(deployment)
        coEvery { envService.environmentDrift(environment.id, release.id) } returns listOf(drift)
        coEvery { envService.channelCompatibleProjects(environment.id, release.id) } returns listOf(deployment.projectId)
        coEvery { releaseService.getById(release.id) } returns release
        coEvery { releaseNotesService.getByRelease(release.id) } returns notes
        coEvery { releaseNotesService.listVersionNotes(release.id) } returns listOf(versionNotes)

        assertEquals(listOf(environment), queryController().environments(authenticated(), program.id))
        assertEquals(listOf(type), queryController().environmentTypes(authenticated()))
        assertEquals(listOf(deployment), queryController().environmentState(authenticated(), environment.id))
        assertEquals(listOf(drift), queryController().environmentDrift(authenticated(), environment.id, release.id))
        assertEquals(
            listOf(deployment.projectId),
            queryController().environmentCompatibleProjects(authenticated(), environment.id, release.id),
        )
        assertEquals(notes, queryController().releaseNotes(authenticated(), release.id))
        assertEquals(listOf(versionNotes), queryController().versionReleaseNotes(authenticated(), release.id))
    }

    @Test
    fun `query scope helpers and environment lookups fail closed when parents are absent`() = runTest {
        val missingId = UUID.random()
        val environmentWithMissingProgram = sampleEnvironment(UUID.random(), UUID.random())
        coEvery { projectService.getById(missingId) } returns null
        coEvery { programService.getById(missingId) } returns null
        coEvery { releaseService.getById(missingId) } returns null
        coEvery { envService.getById(missingId) } returns null
        coEvery { envService.getById(environmentWithMissingProgram.id) } returns environmentWithMissingProgram
        coEvery { programService.getById(environmentWithMissingProgram.programId) } returns null

        assertFailsWith<IllegalStateException> { queryController().dependencies(authenticated(), missingId) }
        assertFailsWith<IllegalStateException> { queryController().environments(authenticated(), missingId) }
        assertFailsWith<IllegalStateException> { queryController().releaseNotes(authenticated(), missingId) }
        assertTrue(queryController().environmentState(authenticated(), missingId).isEmpty())
        assertTrue(queryController().environmentDrift(authenticated(), missingId, UUID.random()).isEmpty())
        assertTrue(queryController().environmentCompatibleProjects(authenticated(), missingId, UUID.random()).isEmpty())
        assertTrue(queryController().environmentState(authenticated(), environmentWithMissingProgram.id).isEmpty())
        assertTrue(queryController().environmentDrift(authenticated(), environmentWithMissingProgram.id, UUID.random()).isEmpty())
        assertTrue(
            queryController().environmentCompatibleProjects(
                authenticated(), environmentWithMissingProgram.id, UUID.random(),
            ).isEmpty(),
        )
    }

    // --- helpers ---

    private fun sampleProject(id: UUID): Project = Project(
        id = id, programId = UUID.random(),
        key = "P", name = "Project", ownerProfileId = profileId,
    )

    private fun sampleProgram(): Program = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = profileId,
    )

    private fun sampleEnvironment(programId: UUID, typeId: UUID): Environment = Environment(
        id = UUID.random(),
        programId = programId,
        key = "production",
        name = "Production",
        description = "Primary environment",
        displayOrder = 9,
        requiresApproval = true,
        autoPromote = true,
        typeId = typeId,
        targetType = EnvironmentTargetType.APP_STORE,
        targetRef = "production",
        ephemeral = true,
        version = 6,
    )

    private fun sampleArtifact(): ArtifactPublication {
        val now = bosca.serialization.OffsetDateTime.now()
        return ArtifactPublication(
            id = UUID.random(),
            versionId = UUID.random(),
            projectId = UUID.random(),
            artifactType = ArtifactType.MAVEN,
            coordinates = "com.example:lib:1.0",
            repositoryUrl = null,
            publishedAt = now,
            publishedByPrincipalId = principalId,
            checksumSha256 = null,
            status = PublicationStatus.PUBLISHED,
            externalUrl = null,
            version = 0,
        )
    }
}
