package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.Project
import bosca.workops.repository.ProgramPermissionRepository
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
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

class ProgramControllerTest {

    private val service = mockk<ProgramService>(relaxed = true)
    private val portfolioService = mockk<PortfolioService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val permissionEvaluator = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val permissionRepo = mockk<ProgramPermissionRepository>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val ownerProfileId = UUID.random()

    private fun typeController() = ProgramTypeController(
        portfolioService,
        projectService,
        service,
        profileService,
        profilePermissions,
        permissionEvaluator,
    )

    private fun mutationController() = ProgramMutationController(
        service,
        portfolioService,
        portfolioPermissions,
        permissionEvaluator,
        permissionRepo,
    )

    @Test
    fun `program fields resolve owner portfolio projects and permissions`() = runTest {
        val program = sampleProgram()
        val portfolio = samplePortfolio(program.portfolioId)
        val project = sampleProject(program.id)
        val owner = mockk<Profile>()
        val permissions = listOf(mockk<EntityPermission>())
        coEvery { profileService.getById(program.ownerProfileId) } returns owner
        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns true
        coEvery { portfolioService.getById(program.portfolioId) } returns portfolio
        coEvery { projectService.listByProgram(program.id, 2, 3) } returns listOf(project)
        coEvery { projectService.listByProgram(program.id, 0, 50) } returns listOf(project)
        coEvery { permissionEvaluator.isAllowed(authentication, program, PermissionAction.MANAGE) } returns true
        coEvery { service.getPermissions(program) } returns permissions

        val controller = typeController()
        assertEquals(program.id, controller.id(program))
        assertEquals(program.key, controller.key(program))
        assertEquals(program.name, controller.name(program))
        assertEquals(program.description, controller.description(program))
        assertEquals(program.ownerProfileId, controller.ownerProfileId(program))
        assertEquals(program.startDate, controller.startDate(program))
        assertEquals(program.targetDate, controller.targetDate(program))
        assertEquals(program.archivedAt, controller.archivedAt(program))
        assertEquals(program.createdAt, controller.createdAt(program))
        assertEquals(program.modifiedAt, controller.modifiedAt(program))
        assertEquals(program.version, controller.version(program))
        assertSame(owner, controller.owner(authentication, program))
        assertSame(portfolio, controller.portfolio(authentication, program))
        assertEquals(listOf(project), controller.projects(program, 2, 3))
        assertEquals(listOf(project), controller.projects(program))
        assertEquals(permissions, controller.permissions(authentication, program))

        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns false
        coEvery { permissionEvaluator.isAllowed(authentication, program, PermissionAction.MANAGE) } returns false
        assertNull(controller.owner(authentication, program))
        assertTrue(controller.permissions(authentication, program).isEmpty())
    }

    @Test
    fun `program portfolio relation fails when its parent disappears`() = runTest {
        val program = sampleProgram()
        coEvery { portfolioService.getById(program.portfolioId) } returns null

        assertFailsWith<IllegalStateException> { typeController().portfolio(authentication, program) }
    }

    @Test
    fun `program queries filter collections and individual records`() = runTest {
        val program = sampleProgram()
        val denied = sampleProgram().copy(key = "DENIED")
        val programs = listOf(program, denied)
        coEvery { service.listAll() } returns programs
        coEvery { service.listByPortfolio(program.portfolioId, 2, 3) } returns programs
        coEvery { permissionEvaluator.filterAllowed(authentication, programs, PermissionAction.VIEW) } returns listOf(program)
        coEvery { service.getById(program.id) } returns program
        coEvery { service.getById(denied.id) } returns denied
        coEvery { permissionEvaluator.isAllowed(authentication, program, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val controller = ProgramQueryController(service, permissionEvaluator)
        assertEquals(listOf(program), controller.all(authentication))
        assertEquals(listOf(program), controller.byPortfolio(authentication, program.portfolioId, 2, 3))
        assertSame(program, controller.program(authentication, program.id))
        assertNull(controller.program(authentication, denied.id))

        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        assertNull(controller.program(authentication, missingId))
    }

    @Test
    fun `program mutations cover lifecycle and permissions`() = runTest {
        val program = sampleProgram()
        val portfolio = samplePortfolio(program.portfolioId)
        val input = sampleInput(portfolio.id)
        val groupId = UUID.random()
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.getById(program.id) } returns program
        coEvery { service.create(input) } returns program
        coEvery { service.update(program.id, input, 1) } returns program.copy(version = 2)
        coEvery { service.archive(program.id, 2) } returns program.copy(version = 3)
        coEvery { service.unarchive(program.id, 3) } returns program.copy(version = 4)

        val controller = mutationController()
        assertSame(program, controller.create(authentication, input))
        assertEquals(2, controller.update(authentication, program.id, input, 1).version)
        assertEquals(3, controller.archive(authentication, program.id, 2).version)
        assertEquals(4, controller.unarchive(authentication, program.id, 3).version)
        assertTrue(controller.addPermission(authentication, program.id, groupId, PermissionAction.VIEW))
        assertTrue(controller.removePermission(authentication, program.id, groupId, PermissionAction.VIEW))

        coVerify(exactly = 1) {
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE)
        }
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.EDIT) }
        coVerify(exactly = 4) { permissionEvaluator.verifyAllowed(authentication, program, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { permissionRepo.add(program.id, groupId, PermissionAction.VIEW) }
        coVerify(exactly = 1) { permissionRepo.delete(program.id, groupId, PermissionAction.VIEW) }
    }

    @Test
    fun `program mutations fail closed for missing portfolio and programs`() = runTest {
        val missingId = UUID.random()
        val input = sampleInput(missingId)
        coEvery { portfolioService.getById(missingId) } returns null
        coEvery { service.getById(missingId) } returns null

        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.create(authentication, input) }
        assertFailsWith<IllegalStateException> { controller.update(authentication, missingId, input, 0) }
        assertFailsWith<IllegalStateException> { controller.archive(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> { controller.unarchive(authentication, missingId, 0) }
        assertFailsWith<IllegalStateException> {
            controller.addPermission(authentication, missingId, UUID.random(), PermissionAction.VIEW)
        }
        assertFailsWith<IllegalStateException> {
            controller.removePermission(authentication, missingId, UUID.random(), PermissionAction.VIEW)
        }
    }

    private fun sampleProgram() = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        description = "Program description",
        ownerProfileId = ownerProfileId,
        version = 1,
    )

    private fun samplePortfolio(id: UUID) = Portfolio(
        id = id,
        key = "PORTFOLIO",
        name = "Portfolio",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleProject(programId: UUID) = Project(
        id = UUID.random(),
        programId = programId,
        key = "PROJECT",
        name = "Project",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleInput(portfolioId: UUID) = ProgramInput(
        portfolioId = portfolioId,
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = ownerProfileId,
    )
}
