package bosca.workops.controller

import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentType
import bosca.workops.model.pipeline.PipelineRun
import bosca.workops.model.pipeline.PipelineStageRun
import bosca.workops.model.pipeline.PipelineTriggerType
import bosca.workops.model.project.Project
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.model.version.Version
import bosca.workops.service.EnvironmentDrift
import bosca.workops.service.EnvironmentDriftType
import bosca.workops.service.EnvironmentPermissionEvaluator
import bosca.workops.service.EnvironmentService
import bosca.workops.service.EnvironmentTypeService
import bosca.workops.service.PipelineRunService
import bosca.workops.service.ProjectService
import bosca.workops.service.VersionService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class EnvironmentDeploymentTypeControllerTest {

    @Test
    fun `deployment exposes its target kind and durable build allocation`() {
        val allocationId = UUID.random()
        val deployment = EnvironmentDeployment(
            environmentId = UUID.random(),
            projectId = UUID.random(),
            targetKind = DeployTargetKind.APP_STORE,
            versionId = UUID.random(),
            appBuildNumberAllocationId = allocationId,
        )
        val controller = EnvironmentDeploymentTypeController(
            mockk<ProjectService>(),
            mockk<VersionService>(),
        )

        assertEquals(DeployTargetKind.APP_STORE, controller.targetKind(deployment))
        assertEquals(allocationId, controller.appBuildNumberAllocationId(deployment))
    }

    @Test
    fun `deployment resolves its project and deployed version`() = runTest {
        val projectService = mockk<ProjectService>()
        val versionService = mockk<VersionService>()
        val project = mockk<Project>()
        val version = mockk<Version>()
        val deployment = EnvironmentDeployment(
            environmentId = UUID.random(),
            projectId = UUID.random(),
            versionId = UUID.random(),
        )
        coEvery { projectService.getById(deployment.projectId) } returns project
        coEvery { versionService.getById(deployment.versionId) } returns version
        val controller = EnvironmentDeploymentTypeController(projectService, versionService)

        assertSame(project, controller.project(deployment))
        assertSame(version, controller.deployedVersion(deployment))
    }

    @Test
    fun `drift resolves project and available versions`() = runTest {
        val projectService = mockk<ProjectService>()
        val versionService = mockk<VersionService>()
        val project = mockk<Project>()
        val deployedVersion = mockk<Version>()
        val expectedVersion = mockk<Version>()
        val drift = EnvironmentDrift(
            projectId = UUID.random(),
            deployedVersionId = UUID.random(),
            expectedVersionId = UUID.random(),
            driftType = EnvironmentDriftType.VERSION_MISMATCH,
        )
        coEvery { projectService.getById(drift.projectId) } returns project
        coEvery { versionService.getById(drift.deployedVersionId ?: error("missing deployed version id")) } returns deployedVersion
        coEvery { versionService.getById(drift.expectedVersionId) } returns expectedVersion
        val controller = EnvironmentDriftTypeController(projectService, versionService)

        assertSame(project, controller.project(drift))
        assertSame(deployedVersion, controller.deployedVersion(drift))
        assertSame(expectedVersion, controller.expectedVersion(drift))
        assertNull(controller.deployedVersion(drift.copy(deployedVersionId = null)))
    }

    @Test
    fun `environment resolves its type and reports a broken reference`() = runTest {
        val environmentService = mockk<EnvironmentService>()
        val typeService = mockk<EnvironmentTypeService>()
        val type = EnvironmentType(id = UUID.random(), name = "production")
        val environment = Environment(
            programId = UUID.random(),
            key = "production",
            name = "Production",
            typeId = type.id,
        )
        val missingTypeId = UUID.random()
        val promotionSourceIds = listOf(UUID.random(), UUID.random())
        coEvery { typeService.getById(type.id) } returns type
        coEvery { typeService.getById(missingTypeId) } returns null
        coEvery { environmentService.promotionSourceIds(environment.id) } returns promotionSourceIds
        val controller = EnvironmentTypeController(
            environmentService,
            typeService,
            mockk<bosca.workops.service.EnvironmentPermissionEvaluator>(relaxed = true),
        )

        assertSame(type, controller.type(environment))
        assertEquals(promotionSourceIds, controller.promotionSourceIds(environment))
        val failure = assertFailsWith<IllegalStateException> {
            controller.type(environment.copy(typeId = missingTypeId))
        }
        assertEquals(
            "Environment ${environment.id} references a missing environment type $missingTypeId",
            failure.message,
        )
    }

    @Test
    fun `environment permissions require manage access`() = runTest {
        val environmentService = mockk<EnvironmentService>()
        val permissionEvaluator = mockk<EnvironmentPermissionEvaluator>()
        val authentication = mockk<AuthenticationContext>()
        val environment = Environment(
            programId = UUID.random(),
            key = "production",
            name = "Production",
            typeId = UUID.random(),
        )
        val permission = mockk<EntityPermission>()
        coEvery {
            permissionEvaluator.isAllowed(authentication, environment, PermissionAction.MANAGE)
        } returnsMany listOf(false, true)
        coEvery { environmentService.getPermissions(environment) } returns listOf(permission)
        val controller = EnvironmentTypeController(environmentService, mockk(), permissionEvaluator)

        assertEquals(emptyList(), controller.permissions(authentication, environment))
        assertEquals(listOf(permission), controller.permissions(authentication, environment))
    }

    @Test
    fun `pipeline run resolves its stages`() = runTest {
        val service = mockk<PipelineRunService>()
        val run = PipelineRun(
            id = UUID.random(),
            projectId = UUID.random(),
            pipelineId = "release",
            pipelineName = "Release",
            triggerType = PipelineTriggerType.TAG,
        )
        val stages = listOf(PipelineStageRun(pipelineRunId = run.id, stageName = "test"))
        coEvery { service.listStages(run.id) } returns stages

        assertSame(stages, PipelineRunTypeController(service).stages(run))
    }

    @Test
    fun `release project version resolves project and version`() = runTest {
        val projectService = mockk<ProjectService>()
        val versionService = mockk<VersionService>()
        val project = mockk<Project>()
        val version = mockk<Version>()
        val releaseVersion = ReleaseProjectVersion(
            releaseId = UUID.random(),
            projectId = UUID.random(),
            versionId = UUID.random(),
        )
        coEvery { projectService.getById(releaseVersion.projectId) } returns project
        coEvery { versionService.getById(releaseVersion.versionId) } returns version
        val controller = ReleaseProjectVersionTypeController(projectService, versionService)

        assertSame(project, controller.project(releaseVersion))
        assertSame(version, controller.version(releaseVersion))
    }
}
