package bosca.workops.service

import bosca.di.ObjectProvider
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.UUID
import bosca.workops.deploy.DeployTargetKind
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.artifact.ArtifactType
import bosca.workops.model.artifact.ReleaseDeclaredArtifact
import bosca.workops.model.environment.CreateEnvironmentInput
import bosca.workops.model.environment.DeployInput
import bosca.workops.model.environment.Environment
import bosca.workops.model.environment.EnvironmentDeployment
import bosca.workops.model.environment.EnvironmentPromotionSource
import bosca.workops.model.environment.EnvironmentTargetType
import bosca.workops.model.environment.HealthCheckStatus
import bosca.workops.model.environment.UpdateEnvironmentInput
import bosca.workops.model.permission.EnvironmentPermission
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.release.ReleaseProjectVersion
import bosca.workops.repository.EnvironmentDeploymentRepository
import bosca.workops.repository.EnvironmentPermissionRepository
import bosca.workops.repository.EnvironmentRepository
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ReleaseRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnvironmentServiceTest {

    private val environmentRepository = mockk<EnvironmentRepository>()
    private val deploymentRepository = mockk<EnvironmentDeploymentRepository>()
    private val releaseRepository = mockk<ReleaseRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val programRepository = mockk<ProgramRepository>()
    private val permissionRepository = mockk<EnvironmentPermissionRepository>()
    private val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>()
    private val dispatcher = mockk<AutomationDispatcher>()
    private val releasePipelines = mockk<ReleasePipelineService>()
    private val releasePipelineProvider = mockk<ObjectProvider<ReleasePipelineService>> {
        coEvery { get() } returns releasePipelines
    }
    private val service = EnvironmentServiceImpl(
        environmentRepository,
        deploymentRepository,
        releaseRepository,
        projectRepository,
        programRepository,
        permissionRepository,
        programPermissionEvaluator,
        dispatcher,
        releasePipelineProvider,
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `permissions parent fallback and direct lookups delegate correctly`() = runTest {
        val programId = UUID.random()
        val typeId = UUID.random()
        val first = environment(programId = programId, typeId = typeId, name = "Development")
        val second = environment(programId = programId, name = "Production")
        val groupId = UUID.random()
        val permission = EnvironmentPermission(first.id, groupId, PermissionAction.EXECUTE)
        val batch = Batch<UUID, List<EntityPermission>>(listOf(first.id, second.id))
        val program = Program(
            id = programId,
            portfolioId = UUID.random(),
            key = "PLATFORM",
            name = "Platform",
            ownerProfileId = UUID.random(),
        )
        val deployment = deployment(environmentId = first.id)

        coEvery { permissionRepository.getByEnvironmentId(first.id) } returns listOf(permission)
        coEvery { permissionRepository.getByEnvironmentIds(listOf(first.id, second.id)) } returns listOf(permission)
        coEvery { permissionRepository.add(first.id, groupId, PermissionAction.EXECUTE) } just Runs
        coEvery { permissionRepository.delete(first.id, groupId, PermissionAction.EXECUTE) } just Runs
        coEvery { environmentRepository.getById(first.id) } returns first
        coEvery { deploymentRepository.getById(deployment.id) } returns deployment
        coEvery { environmentRepository.listByProgram(programId) } returns listOf(first, second)
        coEvery { environmentRepository.getByProgramAndKey(programId, "development") } returns first
        coEvery { environmentRepository.listSources(first.id) } returns listOf(
            EnvironmentPromotionSource(first.id, second.id),
        )
        coEvery { environmentRepository.delete(first.id) } just Runs
        coEvery { deploymentRepository.currentState(first.id) } returns listOf(deployment)
        coEvery { deploymentRepository.listByRelease(deployment.releaseId ?: error("missing release id")) } returns listOf(deployment)
        coEvery { programRepository.getById(programId) } returns null andThen program
        coEvery { programPermissionEvaluator.isAllowed(null, program, PermissionAction.EXECUTE) } returns true

        assertEquals(listOf(permission), service.getPermissions(first))
        service.addPermissionsToBatch(batch)
        assertEquals(listOf(permission), batch.getData(first.id))
        assertEquals(emptyList(), batch.getData(second.id))
        assertEquals(false, service.isParentAllowed(null, first, PermissionAction.EXECUTE))
        assertEquals(true, service.isParentAllowed(null, first, PermissionAction.EXECUTE))
        assertEquals(first, service.getById(first.id))
        assertEquals(deployment, service.getDeployment(deployment.id))
        assertEquals(listOf(first, second), service.listByProgram(programId))
        assertEquals(second, service.getByProgramAndName(programId, "production"))
        assertNull(service.getByProgramAndName(programId, "missing"))
        assertEquals(first, service.getByProgramAndKey(programId, "development"))
        assertEquals(listOf(first), service.listByProgramAndType(programId, typeId))
        assertEquals(listOf(second.id), service.promotionSourceIds(first.id))
        assertEquals(listOf(deployment), service.currentState(first.id))
        assertEquals(listOf(deployment), service.deploymentsByRelease(deployment.releaseId ?: error("missing release id")))
        service.addPermission(first.id, groupId, PermissionAction.EXECUTE)
        service.removePermission(first.id, groupId, PermissionAction.EXECUTE)
        service.delete(first.id)
    }

    @Test
    fun `create and update normalize keys deduplicate sources and reject invalid or stale writes`() = runTest {
        val programId = UUID.random()
        val typeId = UUID.random()
        val sourceId = UUID.random()
        val created = environment(programId = programId, typeId = typeId, key = "qa-west")
        val create = CreateEnvironmentInput(
            programId = programId,
            key = " QA-West ",
            name = "QA West",
            promotionSourceIds = listOf(sourceId, sourceId),
            typeId = typeId,
        )
        val update = UpdateEnvironmentInput(
            name = "QA West 2",
            promotionSourceIds = listOf(sourceId, sourceId),
            typeId = typeId,
        )
        coEvery {
            environmentRepository.add(
                programId, "qa-west", "QA West", null, 0, false, false,
                typeId, EnvironmentTargetType.GENERIC, null, false,
            )
        } returns created
        coEvery { environmentRepository.addSource(created.id, sourceId) } just Runs
        coEvery {
            environmentRepository.update(
                created.id, "QA West 2", null, 0, false, false,
                typeId, EnvironmentTargetType.GENERIC, null, false, 4,
            )
        } returns created.copy(name = "QA West 2", version = 5)
        coEvery { environmentRepository.clearSources(created.id) } just Runs

        assertEquals(created, service.create(create))
        assertEquals("QA West 2", service.update(created.id, update, 4).name)
        coVerify(exactly = 2) { environmentRepository.addSource(created.id, sourceId) }

        assertFailsWith<IllegalArgumentException> {
            service.create(create.copy(key = "not--a-slug"))
        }
        val staleId = UUID.random()
        coEvery { environmentRepository.update(staleId, any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.update(staleId, update, 9)
        }
    }

    @Test
    fun `deployment creation chains the matching target and status transitions report missing rows`() = runTest {
        val environmentId = UUID.random()
        val projectId = UUID.random()
        val previous = deployment(environmentId = environmentId, projectId = projectId, targetKind = DeployTargetKind.HELM)
        val unrelated = deployment(environmentId = environmentId, projectId = projectId, targetKind = DeployTargetKind.APP_STORE)
        val input = DeployInput(
            environmentId = environmentId,
            projectId = projectId,
            targetKind = DeployTargetKind.HELM,
            versionId = UUID.random(),
            releaseId = UUID.random(),
            healthCheckUrl = "https://service/health",
        )
        val added = deployment(environmentId = environmentId, projectId = projectId, versionId = input.versionId)
        coEvery { deploymentRepository.currentState(environmentId) } returns listOf(unrelated, previous)
        coEvery {
            deploymentRepository.add(
                environmentId, projectId, DeployTargetKind.HELM, input.versionId, input.releaseId,
                null, null, input.healthCheckUrl, previous.id,
            )
        } returns added
        assertEquals(added, service.createDeployment(input, UUID.random()))

        val withoutPrevious = input.copy(projectId = UUID.random(), targetKind = DeployTargetKind.GOOGLE_PLAY)
        val addedWithoutPrevious = added.copy(id = UUID.random(), projectId = withoutPrevious.projectId)
        coEvery {
            deploymentRepository.add(
                environmentId, withoutPrevious.projectId, DeployTargetKind.GOOGLE_PLAY,
                withoutPrevious.versionId, withoutPrevious.releaseId, null, null,
                withoutPrevious.healthCheckUrl, null,
            )
        } returns addedWithoutPrevious
        assertEquals(addedWithoutPrevious, service.createDeployment(withoutPrevious, UUID.random()))

        val principalId = UUID.random()
        coEvery { deploymentRepository.updateStatus(added.id, "DEPLOYED", any(), principalId, 1) } returns added
        coEvery { deploymentRepository.updateStatus(added.id, "FAILED", null, null, 2) } returns added
        coEvery { deploymentRepository.updateStatus(added.id, "ROLLED_BACK", null, null, 3) } returns added
        assertEquals(added, service.markDeployed(added.id, principalId, 1))
        assertEquals(added, service.markFailed(added.id, 2))
        assertEquals(added, service.markRolledBack(added.id, 3))

        listOf("DEPLOYED", "FAILED", "ROLLED_BACK").forEachIndexed { index, status ->
            val missingId = UUID.random()
            coEvery { deploymentRepository.updateStatus(missingId, status, any(), any(), index.toLong()) } returns null
            assertFailsWith<WorkOpsNotFoundException> {
                when (status) {
                    "DEPLOYED" -> service.markDeployed(missingId, principalId, index.toLong())
                    "FAILED" -> service.markFailed(missingId, index.toLong())
                    else -> service.markRolledBack(missingId, index.toLong())
                }
            }
        }
    }

    @Test
    fun `health updates fire appropriate triggers preserve ordinary failures and propagate cancellation`() = runTest {
        val projectId = UUID.random()
        val environmentId = UUID.random()
        val deployment = deployment(environmentId = environmentId, projectId = projectId)
        val environment = environment(id = environmentId)
        val project = Project(
            id = projectId,
            programId = environment.programId,
            key = "APP",
            name = "Application",
            ownerProfileId = UUID.random(),
        )
        val program = Program(
            id = environment.programId,
            portfolioId = UUID.random(),
            key = "PLATFORM",
            name = "Platform",
            ownerProfileId = UUID.random(),
        )
        coEvery { projectRepository.getById(projectId) } returns project
        coEvery { programRepository.getById(program.id) } returns program
        coEvery { environmentRepository.getById(environmentId) } returns environment
        coEvery { dispatcher.fireEnvironmentUnhealthy(any(), any(), any(), any()) } just Runs
        coEvery { dispatcher.fireEnvironmentPromotionReady(any(), any(), any(), any()) } just Runs

        val missingId = UUID.random()
        coEvery { deploymentRepository.updateHealthCheck(missingId, "HEALTHY", 0) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.updateHealthCheck(missingId, HealthCheckStatus.HEALTHY, 0)
        }

        coEvery { deploymentRepository.updateHealthCheck(deployment.id, "UNHEALTHY", 1) } returns deployment
        assertEquals(deployment, service.updateHealthCheck(deployment.id, HealthCheckStatus.UNHEALTHY, 1))
        coVerify(exactly = 1) {
            dispatcher.fireEnvironmentUnhealthy(projectId, program.id, program.portfolioId, environmentId)
        }

        val failing = deployment.copy(id = UUID.random())
        coEvery { deploymentRepository.updateHealthCheck(failing.id, "UNHEALTHY", 2) } returns failing
        coEvery { dispatcher.fireEnvironmentUnhealthy(projectId, program.id, program.portfolioId, environmentId) } throws
            IllegalStateException("trigger unavailable")
        assertEquals(failing, service.updateHealthCheck(failing.id, HealthCheckStatus.UNHEALTHY, 2))

        val cancelled = deployment.copy(id = UUID.random())
        coEvery { deploymentRepository.updateHealthCheck(cancelled.id, "UNHEALTHY", 3) } returns cancelled
        coEvery { dispatcher.fireEnvironmentUnhealthy(projectId, program.id, program.portfolioId, environmentId) } throws
            CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            service.updateHealthCheck(cancelled.id, HealthCheckStatus.UNHEALTHY, 3)
        }

        val healthy = deployment.copy(id = UUID.random(), healthCheckStatus = HealthCheckStatus.HEALTHY)
        coEvery { deploymentRepository.updateHealthCheck(healthy.id, "HEALTHY", 4) } returns healthy
        coEvery { deploymentRepository.currentState(environmentId) } returns listOf(healthy)
        assertEquals(healthy, service.updateHealthCheck(healthy.id, HealthCheckStatus.HEALTHY, 4))
        coVerify(exactly = 1) {
            dispatcher.fireEnvironmentPromotionReady(projectId, program.id, program.portfolioId, environmentId)
        }

        val notReady = deployment.copy(id = UUID.random())
        coEvery { deploymentRepository.updateHealthCheck(notReady.id, "HEALTHY", 5) } returns notReady
        coEvery { deploymentRepository.currentState(environmentId) } returns listOf(notReady)
        assertEquals(notReady, service.updateHealthCheck(notReady.id, HealthCheckStatus.HEALTHY, 5))

        val noEnvironment = deployment.copy(id = UUID.random(), environmentId = UUID.random())
        coEvery { deploymentRepository.updateHealthCheck(noEnvironment.id, "HEALTHY", 6) } returns noEnvironment
        coEvery { deploymentRepository.currentState(noEnvironment.environmentId) } returns emptyList()
        coEvery { environmentRepository.getById(noEnvironment.environmentId) } returns null
        assertEquals(noEnvironment, service.updateHealthCheck(noEnvironment.id, HealthCheckStatus.HEALTHY, 6))

        val unknown = deployment.copy(id = UUID.random())
        coEvery { deploymentRepository.updateHealthCheck(unknown.id, "UNKNOWN", 7) } returns unknown
        assertEquals(unknown, service.updateHealthCheck(unknown.id, HealthCheckStatus.UNKNOWN, 7))
    }

    @Test
    fun `probe health propagates cancellation`() = runTest {
        val deployment = deployment(healthCheckUrl = "https://service/health")
        coEvery { deploymentRepository.getById(deployment.id) } returns deployment

        assertFailsWith<CancellationException> {
            service.probeHealth(deployment.id) { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `unrelated environment reads do not resolve release pipelines`() = runTest {
        val environment = environment()
        coEvery { environmentRepository.getById(environment.id) } returns environment

        assertEquals(environment, service.getById(environment.id))

        coVerify(exactly = 0) { releasePipelineProvider.get() }
    }

    @Test
    fun `channel filtering handles store infrastructure scoped and missing environments`() = runTest {
        val releaseId = UUID.random()
        val androidId = UUID.random()
        val iosId = UUID.random()
        val serverId = UUID.random()
        val undeclaredId = UUID.random()
        val versions = listOf(androidId, iosId, serverId, undeclaredId).map { projectId ->
            ReleaseProjectVersion(releaseId, projectId, UUID.random())
        }
        val declared = listOf(
            ReleaseDeclaredArtifact(androidId, ArtifactType.ANDROID_AAR, "mobile", "android", listOf("Production")),
            ReleaseDeclaredArtifact(iosId, ArtifactType.IOS_FRAMEWORK, "mobile", "ios"),
            ReleaseDeclaredArtifact(serverId, ArtifactType.DOCKER, "server", "api", listOf("Production")),
        )
        coEvery { releaseRepository.listVersions(releaseId) } returns versions
        coEvery { releasePipelines.releaseDeclaredArtifacts(releaseId) } returns declared

        val play = environment(targetType = EnvironmentTargetType.PLAY_TRACK, name = "production")
        val testFlight = environment(targetType = EnvironmentTargetType.TESTFLIGHT, name = "production")
        val appStore = environment(targetType = EnvironmentTargetType.APP_STORE, name = "production")
        val generic = environment(targetType = EnvironmentTargetType.GENERIC, name = "production")
        listOf(play, testFlight, appStore, generic).forEach { environment ->
            coEvery { environmentRepository.getById(environment.id) } returns environment
        }

        assertEquals(listOf(androidId), service.channelCompatibleProjects(play.id, releaseId))
        assertEquals(listOf(iosId), service.channelCompatibleProjects(testFlight.id, releaseId))
        assertEquals(listOf(iosId), service.channelCompatibleProjects(appStore.id, releaseId))
        assertEquals(listOf(serverId, undeclaredId), service.channelCompatibleProjects(generic.id, releaseId))

        val missingId = UUID.random()
        coEvery { environmentRepository.getById(missingId) } returns null
        assertEquals(emptyList(), service.channelCompatibleProjects(missingId, releaseId))
        assertEquals(emptyList(), service.environmentDrift(missingId, releaseId))
    }

    @Test
    fun `promotion reports a missing source or target`() = runTest {
        val sourceId = UUID.random()
        val targetId = UUID.random()
        coEvery { environmentRepository.getById(sourceId) } returns null andThen environment(id = sourceId)
        coEvery { environmentRepository.getById(targetId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.createPromotionDeployment(sourceId, targetId, null, UUID.random())
        }
        assertFailsWith<WorkOpsNotFoundException> {
            service.createPromotionDeployment(sourceId, targetId, null, UUID.random())
        }
    }

    private fun environment(
        id: UUID = UUID.random(),
        programId: UUID = UUID.random(),
        typeId: UUID = UUID.random(),
        key: String = "production",
        name: String = "Production",
        targetType: EnvironmentTargetType = EnvironmentTargetType.GENERIC,
    ) = Environment(
        id = id,
        programId = programId,
        key = key,
        name = name,
        typeId = typeId,
        targetType = targetType,
    )

    private fun deployment(
        id: UUID = UUID.random(),
        environmentId: UUID = UUID.random(),
        projectId: UUID = UUID.random(),
        targetKind: DeployTargetKind = DeployTargetKind.HELM,
        versionId: UUID = UUID.random(),
        healthCheckUrl: String? = null,
    ) = EnvironmentDeployment(
        id = id,
        environmentId = environmentId,
        projectId = projectId,
        targetKind = targetKind,
        versionId = versionId,
        releaseId = UUID.random(),
        healthCheckUrl = healthCheckUrl,
        version = 1,
    )
}
