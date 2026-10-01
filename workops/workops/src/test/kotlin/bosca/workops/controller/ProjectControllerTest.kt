package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.model.project.ProjectAnalyticsApplication
import bosca.workops.model.project.ProjectAnalyticsService
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.project.ProjectRepository
import bosca.workops.repository.ProjectPermissionRepository
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectAnalyticsConfigService
import bosca.workops.service.ProjectPermissionEvaluator
import bosca.workops.service.ProjectRepositoryService
import bosca.workops.service.ProjectService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ProjectControllerTest {

    private val service = mockk<ProjectService>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val permissionEvaluator = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val permissionRepo = mockk<ProjectPermissionRepository>(relaxed = true)
    private val analyticsConfig = mockk<ProjectAnalyticsConfigService>(relaxed = true)
    private val repositoryConfig = mockk<ProjectRepositoryService>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val ownerProfileId = UUID.random()

    private fun typeController() = ProjectTypeController(
        programService,
        service,
        profileService,
        profilePermissions,
        permissionEvaluator,
        analyticsConfig,
        repositoryConfig,
    )

    private fun mutationController() = ProjectMutationController(
        service,
        programService,
        programPermissions,
        permissionEvaluator,
        permissionRepo,
        analyticsConfig,
        repositoryConfig,
    )

    @Test
    fun `project fields resolve owner program permissions analytics and repositories`() = runTest {
        val project = sampleProject()
        val program = sampleProgram(project.programId)
        val owner = mockk<Profile>()
        val permissions = listOf(mockk<EntityPermission>())
        val application = ProjectAnalyticsApplication(projectId = project.id, applicationId = "mobile")
        val analyticsService = ProjectAnalyticsService(projectId = project.id, service = "bosca")
        val repository = ProjectRepository(projectId = project.id, repositoryId = UUID.random())
        coEvery { profileService.getById(ownerProfileId) } returns owner
        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns true
        coEvery { programService.getById(project.programId) } returns program
        coEvery { permissionEvaluator.isAllowed(authentication, project, PermissionAction.MANAGE) } returns true
        coEvery { service.getPermissions(project) } returns permissions
        coEvery { analyticsConfig.listApplications(project.id) } returns listOf(application)
        coEvery { analyticsConfig.listServices(project.id) } returns listOf(analyticsService)
        coEvery { repositoryConfig.list(project.id) } returns listOf(repository)

        val controller = typeController()
        assertEquals(project.id, controller.id(project))
        assertEquals(project.key, controller.key(project))
        assertEquals(project.name, controller.name(project))
        assertEquals(project.description, controller.description(project))
        assertEquals(project.ownerProfileId, controller.ownerProfileId(project))
        assertEquals(project.defaultTaskTypeSchemeId, controller.defaultTaskTypeSchemeId(project))
        assertEquals(project.defaultWorkflowSchemeId, controller.defaultWorkflowSchemeId(project))
        assertEquals(project.taskCreationFormSchemaKey, controller.taskCreationFormSchemaKey(project))
        assertEquals(project.archivedAt, controller.archivedAt(project))
        assertEquals(project.createdAt, controller.createdAt(project))
        assertEquals(project.modifiedAt, controller.modifiedAt(project))
        assertEquals(project.version, controller.version(project))
        assertSame(owner, controller.owner(authentication, project))
        assertSame(program, controller.program(authentication, project))
        assertEquals(permissions, controller.permissions(authentication, project))
        assertEquals(listOf(application), controller.analyticsApplications(project))
        assertEquals(listOf(analyticsService), controller.analyticsServices(project))
        assertEquals(listOf(repository), controller.repositories(project))

        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns false
        coEvery { permissionEvaluator.isAllowed(authentication, project, PermissionAction.MANAGE) } returns false
        assertNull(controller.owner(authentication, project))
        assertTrue(controller.permissions(authentication, project).isEmpty())
    }

    @Test
    fun `project program relation fails when its parent disappears`() = runTest {
        val project = sampleProject()
        coEvery { programService.getById(project.programId) } returns null

        assertFailsWith<IllegalStateException> { typeController().program(authentication, project) }
    }

    @Test
    fun `project queries filter collections and individual records`() = runTest {
        val project = sampleProject()
        val denied = sampleProject().copy(key = "DENIED")
        val projects = listOf(project, denied)
        coEvery { service.listAll() } returns projects
        coEvery { service.listByProgram(project.programId, 2, 3) } returns projects
        coEvery { permissionEvaluator.filterAllowed(authentication, projects, PermissionAction.VIEW) } returns listOf(project)
        coEvery { service.getById(project.id) } returns project
        coEvery { service.getById(denied.id) } returns denied
        coEvery { service.getByKey(project.key) } returns project
        coEvery { service.getByKey(denied.key) } returns denied
        coEvery { permissionEvaluator.isAllowed(authentication, project, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val controller = ProjectQueryController(service, permissionEvaluator)
        assertEquals(listOf(project), controller.all(authentication))
        assertEquals(listOf(project), controller.byProgram(authentication, project.programId, 2, 3))
        assertSame(project, controller.project(authentication, project.id))
        assertNull(controller.project(authentication, denied.id))
        assertSame(project, controller.projectByKey(authentication, project.key))
        assertNull(controller.projectByKey(authentication, denied.key))

        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        coEvery { service.getByKey("MISSING") } returns null
        assertNull(controller.project(authentication, missingId))
        assertNull(controller.projectByKey(authentication, "MISSING"))
    }

    @Test
    fun `project mutations cover lifecycle permissions analytics and repositories`() = runTest {
        val project = sampleProject()
        val program = sampleProgram(project.programId)
        val targetProgram = sampleProgram(UUID.random())
        val input = sampleInput(project.programId)
        val groupId = UUID.random()
        val applicationId = UUID.random()
        val analyticsServiceId = UUID.random()
        val repositoryLinkId = UUID.random()
        val repositoryId = UUID.random()
        val application = ProjectAnalyticsApplication(projectId = project.id, applicationId = "mobile")
        val analyticsService = ProjectAnalyticsService(projectId = project.id, service = "bosca")
        val repository = ProjectRepository(projectId = project.id, repositoryId = repositoryId)
        coEvery { programService.getById(program.id) } returns program
        coEvery { service.getById(project.id) } returns project
        coEvery { service.create(input) } returns project
        coEvery { service.update(project.id, input, 1) } returns project.copy(version = 2)
        coEvery { programService.getById(targetProgram.id) } returns targetProgram
        coEvery { service.move(project.id, targetProgram.id, 2) } returns
            project.copy(programId = targetProgram.id, version = 3)
        coEvery { service.archive(project.id, 3) } returns project.copy(version = 4)
        coEvery { service.unarchive(project.id, 4) } returns project.copy(version = 5)
        coEvery { analyticsConfig.addApplication(project.id, "mobile") } returns application
        coEvery { analyticsConfig.addService(project.id, "bosca") } returns analyticsService
        coEvery { repositoryConfig.add(project.id, repositoryId) } returns repository

        val controller = mutationController()
        assertSame(project, controller.create(authentication, input))
        assertEquals(2, controller.update(authentication, project.id, input, 1).version)
        assertEquals(targetProgram.id, controller.move(authentication, project.id, targetProgram.id, 2).programId)
        assertEquals(4, controller.archive(authentication, project.id, 3).version)
        assertEquals(5, controller.unarchive(authentication, project.id, 4).version)
        assertTrue(controller.addPermission(authentication, project.id, groupId, PermissionAction.VIEW))
        assertTrue(controller.removePermission(authentication, project.id, groupId, PermissionAction.VIEW))
        assertSame(application, controller.addAnalyticsApplication(authentication, project.id, "mobile"))
        assertTrue(controller.removeAnalyticsApplication(authentication, project.id, applicationId))
        assertSame(analyticsService, controller.addAnalyticsService(authentication, project.id, "bosca"))
        assertTrue(controller.removeAnalyticsService(authentication, project.id, analyticsServiceId))
        assertSame(repository, controller.addProjectRepository(authentication, project.id, repositoryId))
        assertTrue(controller.removeProjectRepository(authentication, project.id, repositoryLinkId))

        coVerify { programPermissions.verifyAllowed(authentication, program, PermissionAction.MANAGE) }
        coVerify { permissionEvaluator.verifyAllowed(authentication, project, PermissionAction.MANAGE) }
        coVerify { programPermissions.verifyAllowed(authentication, targetProgram, PermissionAction.MANAGE) }
        coVerify { permissionRepo.add(project.id, groupId, PermissionAction.VIEW) }
        coVerify { permissionRepo.delete(project.id, groupId, PermissionAction.VIEW) }
        coVerify { analyticsConfig.removeApplication(project.id, applicationId) }
        coVerify { analyticsConfig.removeService(project.id, analyticsServiceId) }
        coVerify { repositoryConfig.remove(project.id, repositoryLinkId) }
    }

    @Test
    fun `project mutations fail closed for missing parents and projects`() = runTest {
        val missingId = UUID.random()
        val input = sampleInput(missingId)
        coEvery { programService.getById(missingId) } returns null
        coEvery { service.getById(missingId) } returns null

        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.create(authentication, input) }
        assertFailsWith<IllegalStateException> { controller.update(authentication, missingId, input, 0) }
        assertFailsWith<IllegalStateException> { controller.move(authentication, missingId, UUID.random(), 0) }
        assertFailsWith<IllegalStateException> { controller.archive(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> { controller.unarchive(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller.addPermission(authentication, missingId, UUID.random(), PermissionAction.VIEW)
        }
        assertFailsWith<IllegalStateException> {
            controller.removePermission(authentication, missingId, UUID.random(), PermissionAction.VIEW)
        }
        assertFailsWith<IllegalStateException> {
            controller.addAnalyticsApplication(authentication, missingId, "missing")
        }

        val project = sampleProject()
        coEvery { service.getById(project.id) } returns project
        assertFailsWith<IllegalStateException> {
            controller.move(authentication, project.id, missingId, 0)
        }
    }

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "GIT",
        name = "Git",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleProgram(id: UUID) = Program(
        id = id,
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleInput(programId: UUID) = ProjectInput(
        programId = programId,
        key = "GIT",
        name = "Git",
        ownerProfileId = ownerProfileId,
    )
}
