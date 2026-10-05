package bosca.workops.controller

import bosca.profile.model.Profile
import bosca.profile.profile.service.ProfileService
import bosca.profile.security.ProfilePermissionEvaluator
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.Program
import bosca.workops.repository.PortfolioPermissionRepository
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramService
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

class PortfolioControllerTest {

    private val service = mockk<PortfolioService>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val profileService = mockk<ProfileService>(relaxed = true)
    private val profilePermissions = mockk<ProfilePermissionEvaluator>(relaxed = true)
    private val permissionEvaluator = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val permissionRepo = mockk<PortfolioPermissionRepository>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>(relaxed = true)
    private val ownerProfileId = UUID.random()

    private fun typeController() = PortfolioTypeController(
        service,
        programService,
        profileService,
        profilePermissions,
        permissionEvaluator,
    )

    private fun mutationController() = PortfolioMutationController(
        service,
        groupEvaluator,
        permissionEvaluator,
        permissionRepo,
    )

    @Test
    fun `portfolio fields resolve owner programs and permissions`() = runTest {
        val portfolio = samplePortfolio()
        val program = sampleProgram(portfolio.id)
        val owner = mockk<Profile>()
        val permissions = listOf(mockk<EntityPermission>())
        coEvery { profileService.getById(portfolio.ownerProfileId) } returns owner
        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns true
        coEvery { programService.listByPortfolio(portfolio.id, 2, 3) } returns listOf(program)
        coEvery { programService.listByPortfolio(portfolio.id, 0, 50) } returns listOf(program)
        coEvery { permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.MANAGE) } returns true
        coEvery { service.getPermissions(portfolio) } returns permissions

        val controller = typeController()
        assertEquals(portfolio.id, controller.id(portfolio))
        assertEquals(portfolio.key, controller.key(portfolio))
        assertEquals(portfolio.name, controller.name(portfolio))
        assertEquals(portfolio.description, controller.description(portfolio))
        assertEquals(portfolio.ownerProfileId, controller.ownerProfileId(portfolio))
        assertEquals(portfolio.archivedAt, controller.archivedAt(portfolio))
        assertEquals(portfolio.createdAt, controller.createdAt(portfolio))
        assertEquals(portfolio.modifiedAt, controller.modifiedAt(portfolio))
        assertEquals(portfolio.version, controller.version(portfolio))
        assertSame(owner, controller.owner(authentication, portfolio))
        assertEquals(listOf(program), controller.programs(portfolio, 2, 3))
        assertEquals(listOf(program), controller.programs(portfolio))
        assertEquals(permissions, controller.permissions(authentication, portfolio))

        coEvery { profilePermissions.isAllowed(authentication, owner, PermissionAction.VIEW) } returns false
        coEvery { permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.MANAGE) } returns false
        assertNull(controller.owner(authentication, portfolio))
        assertTrue(controller.permissions(authentication, portfolio).isEmpty())
    }

    @Test
    fun `portfolio queries filter collections ids and keys`() = runTest {
        val portfolio = samplePortfolio()
        val denied = samplePortfolio().copy(key = "DENIED")
        val portfolios = listOf(portfolio, denied)
        coEvery { service.list(2, 3) } returns portfolios
        coEvery { permissionEvaluator.filterAllowed(authentication, portfolios, PermissionAction.VIEW) } returns listOf(portfolio)
        coEvery { service.getById(portfolio.id) } returns portfolio
        coEvery { service.getById(denied.id) } returns denied
        coEvery { service.getByKey(portfolio.key) } returns portfolio
        coEvery { service.getByKey(denied.key) } returns denied
        coEvery { permissionEvaluator.isAllowed(authentication, portfolio, PermissionAction.VIEW) } returns true
        coEvery { permissionEvaluator.isAllowed(authentication, denied, PermissionAction.VIEW) } returns false

        val controller = PortfolioQueryController(service, permissionEvaluator, groupEvaluator)
        assertEquals(listOf(portfolio), controller.all(authentication, 2, 3))
        assertSame(portfolio, controller.portfolio(authentication, portfolio.id))
        assertNull(controller.portfolio(authentication, denied.id))
        assertSame(portfolio, controller.portfolioByKey(authentication, portfolio.key))
        assertNull(controller.portfolioByKey(authentication, denied.key))

        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        coEvery { service.getByKey("MISSING") } returns null
        assertNull(controller.portfolio(authentication, missingId))
        assertNull(controller.portfolioByKey(authentication, "MISSING"))
    }

    @Test
    fun `portfolio mutations cover admin create lifecycle and permissions`() = runTest {
        val portfolio = samplePortfolio()
        val input = sampleInput()
        val groupId = UUID.random()
        coEvery { service.getById(portfolio.id) } returns portfolio
        coEvery { service.create(input) } returns portfolio
        coEvery { service.update(portfolio.id, input, 1) } returns portfolio.copy(version = 2)
        coEvery { service.archive(portfolio.id, 2) } returns portfolio.copy(version = 3)
        coEvery { service.unarchive(portfolio.id, 3) } returns portfolio.copy(version = 4)

        val controller = mutationController()
        assertSame(portfolio, controller.create(authentication, input))
        assertEquals(2, controller.update(authentication, portfolio.id, input, 1).version)
        assertEquals(3, controller.archive(authentication, portfolio.id, 2).version)
        assertEquals(4, controller.unarchive(authentication, portfolio.id, 3).version)
        assertTrue(controller.addPermission(authentication, portfolio.id, groupId, PermissionAction.VIEW))
        assertTrue(controller.removePermission(authentication, portfolio.id, groupId, PermissionAction.VIEW))

        coVerify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify(exactly = 1) { permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.EDIT) }
        coVerify(exactly = 4) { permissionEvaluator.verifyAllowed(authentication, portfolio, PermissionAction.MANAGE) }
        coVerify(exactly = 1) { permissionRepo.add(portfolio.id, groupId, PermissionAction.VIEW) }
        coVerify(exactly = 1) { permissionRepo.delete(portfolio.id, groupId, PermissionAction.VIEW) }
    }

    @Test
    fun `portfolio mutations fail closed for missing portfolios`() = runTest {
        val missingId = UUID.random()
        val input = sampleInput()
        coEvery { service.getById(missingId) } returns null

        val controller = mutationController()
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

    private fun samplePortfolio() = Portfolio(
        id = UUID.random(),
        key = "PORTFOLIO",
        name = "Portfolio",
        description = "Portfolio description",
        ownerProfileId = ownerProfileId,
        version = 1,
    )

    private fun sampleProgram(portfolioId: UUID) = Program(
        id = UUID.random(),
        portfolioId = portfolioId,
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = ownerProfileId,
    )

    private fun sampleInput() = PortfolioInput(
        key = "PORTFOLIO",
        name = "Portfolio",
        ownerProfileId = ownerProfileId,
    )
}
