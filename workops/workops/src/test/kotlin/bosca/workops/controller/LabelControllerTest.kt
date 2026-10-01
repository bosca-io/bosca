package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.Label
import bosca.workops.model.label.LabelScope
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.service.LabelService
import bosca.workops.service.PortfolioPermissionEvaluator
import bosca.workops.service.PortfolioService
import bosca.workops.service.ProgramPermissionEvaluator
import bosca.workops.service.ProgramService
import bosca.workops.service.ProjectPermissionEvaluator
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

class LabelControllerTest {

    private val service = mockk<LabelService>(relaxed = true)
    private val projectService = mockk<ProjectService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>(relaxed = true)
    private val programService = mockk<ProgramService>(relaxed = true)
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val portfolioService = mockk<PortfolioService>(relaxed = true)
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val profileId = UUID.random()
    private val authentication: AuthenticationContext = ImpersonatedAuthenticationContext(
        Principal(UUID.random(), primaryProfileId = profileId),
        listOf(Group(UUID.random(), "users", "", GroupType.SYSTEM)),
    )
    private val unauthenticated = AuthenticationContext(null, null)

    private fun queryController() = LabelQueryController(
        service,
        projectService,
        projectPermissions,
        programService,
        programPermissions,
        portfolioService,
        portfolioPermissions,
    )

    private fun mutationController() = LabelMutationController(
        service,
        projectService,
        projectPermissions,
        programService,
        programPermissions,
        portfolioService,
        portfolioPermissions,
        groupEvaluator,
    )

    @Test
    fun `label scalar fields expose persisted values`() {
        val label = sampleLabel(LabelScope.PROJECT, projectId = UUID.random())
        val controller = LabelTypeController()
        assertEquals(label.id, controller.id(label))
        assertEquals(label.name, controller.name(label))
        assertEquals(label.colorHex, controller.colorHex(label))
        assertEquals(label.scope, controller.scope(label))
        assertEquals(label.portfolioId, controller.portfolioId(label))
        assertEquals(label.programId, controller.programId(label))
        assertEquals(label.projectId, controller.projectId(label))
        assertEquals(label.version, controller.version(label))
    }

    @Test
    fun `label queries enforce every scope and list visible labels`() = runTest {
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectLabel = sampleLabel(LabelScope.PROJECT, projectId = project.id)
        val programLabel = sampleLabel(LabelScope.PROGRAM, programId = program.id)
        val portfolioLabel = sampleLabel(LabelScope.PORTFOLIO, portfolioId = portfolio.id)
        val globalLabel = sampleLabel(LabelScope.GLOBAL)
        listOf(projectLabel, programLabel, portfolioLabel, globalLabel).forEach {
            coEvery { service.getById(it.id) } returns it
        }
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW) } returns true
        coEvery { programPermissions.isAllowed(authentication, program, PermissionAction.VIEW) } returns true
        coEvery { portfolioPermissions.isAllowed(authentication, portfolio, PermissionAction.VIEW) } returns true
        coEvery { service.listGlobal() } returns listOf(globalLabel)
        coEvery { service.listByProject(project.id) } returns listOf(projectLabel)
        coEvery { service.listByProgram(program.id) } returns listOf(programLabel)
        coEvery { service.listByPortfolio(portfolio.id) } returns listOf(portfolioLabel)

        val controller = queryController()
        assertSame(projectLabel, controller.label(authentication, projectLabel.id))
        assertSame(programLabel, controller.label(authentication, programLabel.id))
        assertSame(portfolioLabel, controller.label(authentication, portfolioLabel.id))
        assertSame(globalLabel, controller.label(authentication, globalLabel.id))
        assertEquals(listOf(globalLabel), controller.global(authentication))
        assertEquals(listOf(projectLabel), controller.byProject(authentication, project.id))
        assertEquals(listOf(programLabel), controller.byProgram(authentication, program.id))
        assertEquals(listOf(portfolioLabel), controller.byPortfolio(authentication, portfolio.id))
    }

    @Test
    fun `label queries fail closed for missing denied and unauthenticated scopes`() = runTest {
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectLabel = sampleLabel(LabelScope.PROJECT, projectId = project.id)
        val programLabel = sampleLabel(LabelScope.PROGRAM, programId = program.id)
        val portfolioLabel = sampleLabel(LabelScope.PORTFOLIO, portfolioId = portfolio.id)
        val globalLabel = sampleLabel(LabelScope.GLOBAL)
        val missingId = UUID.random()
        coEvery { service.getById(missingId) } returns null
        coEvery { service.getById(projectLabel.id) } returns projectLabel
        coEvery { service.getById(programLabel.id) } returns programLabel
        coEvery { service.getById(portfolioLabel.id) } returns portfolioLabel
        coEvery { service.getById(globalLabel.id) } returns globalLabel
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { projectPermissions.isAllowed(authentication, project, PermissionAction.VIEW) } returns false
        coEvery { programPermissions.isAllowed(authentication, program, PermissionAction.VIEW) } returns false
        coEvery { portfolioPermissions.isAllowed(authentication, portfolio, PermissionAction.VIEW) } returns false

        val controller = queryController()
        assertNull(controller.label(authentication, missingId))
        assertNull(controller.label(authentication, projectLabel.id))
        assertNull(controller.label(authentication, programLabel.id))
        assertNull(controller.label(authentication, portfolioLabel.id))
        assertFailsWith<IllegalStateException> { controller.label(unauthenticated, globalLabel.id) }
        assertFailsWith<IllegalStateException> { controller.global(unauthenticated) }

        coEvery { projectService.getById(project.id) } returns null
        coEvery { programService.getById(program.id) } returns null
        coEvery { portfolioService.getById(portfolio.id) } returns null
        assertNull(controller.label(authentication, projectLabel.id))
        assertNull(controller.label(authentication, programLabel.id))
        assertNull(controller.label(authentication, portfolioLabel.id))
        assertTrue(controller.byProject(authentication, project.id).isEmpty())
        assertTrue(controller.byProgram(authentication, program.id).isEmpty())
        assertTrue(controller.byPortfolio(authentication, portfolio.id).isEmpty())
    }

    @Test
    fun `label mutations cover all scopes update and delete`() = runTest {
        val project = sampleProject()
        val program = sampleProgram()
        val portfolio = samplePortfolio()
        val projectInput = CreateLabelInput("Project", scope = LabelScope.PROJECT, projectId = project.id)
        val programInput = CreateLabelInput("Program", scope = LabelScope.PROGRAM, programId = program.id)
        val portfolioInput = CreateLabelInput("Portfolio", scope = LabelScope.PORTFOLIO, portfolioId = portfolio.id)
        val globalInput = CreateLabelInput("Global")
        val projectLabel = sampleLabel(LabelScope.PROJECT, projectId = project.id)
        val programLabel = sampleLabel(LabelScope.PROGRAM, programId = program.id)
        val portfolioLabel = sampleLabel(LabelScope.PORTFOLIO, portfolioId = portfolio.id)
        val globalLabel = sampleLabel(LabelScope.GLOBAL)
        coEvery { projectService.getById(project.id) } returns project
        coEvery { programService.getById(program.id) } returns program
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery { service.create(projectInput) } returns projectLabel
        coEvery { service.create(programInput) } returns programLabel
        coEvery { service.create(portfolioInput) } returns portfolioLabel
        coEvery { service.create(globalInput) } returns globalLabel
        listOf(projectLabel, programLabel, portfolioLabel, globalLabel).forEach {
            coEvery { service.getById(it.id) } returns it
            coEvery { service.update(it.id, "Updated", "#123456", 1) } returns it.copy(name = "Updated")
        }

        val controller = mutationController()
        assertSame(projectLabel, controller.create(authentication, projectInput))
        assertSame(programLabel, controller.create(authentication, programInput))
        assertSame(portfolioLabel, controller.create(authentication, portfolioInput))
        assertSame(globalLabel, controller.create(authentication, globalInput))
        assertEquals("Updated", controller.update(authentication, projectLabel.id, "Updated", "#123456", 1).name)
        assertEquals("Updated", controller.update(authentication, programLabel.id, "Updated", "#123456", 1).name)
        assertEquals("Updated", controller.update(authentication, portfolioLabel.id, "Updated", "#123456", 1).name)
        assertEquals("Updated", controller.update(authentication, globalLabel.id, "Updated", "#123456", 1).name)
        assertTrue(controller.delete(authentication, projectLabel.id))

        coVerify(exactly = 2) { groupEvaluator.verifyHasAdminGroup(authentication) }
        coVerify { service.delete(projectLabel.id) }
    }

    @Test
    fun `label mutations fail closed for missing labels and scoped parents`() = runTest {
        val missingId = UUID.random()
        val projectInput = CreateLabelInput("Project", scope = LabelScope.PROJECT, projectId = missingId)
        val programInput = CreateLabelInput("Program", scope = LabelScope.PROGRAM, programId = missingId)
        val portfolioInput = CreateLabelInput("Portfolio", scope = LabelScope.PORTFOLIO, portfolioId = missingId)
        val projectLabel = sampleLabel(LabelScope.PROJECT, projectId = missingId)
        val programLabel = sampleLabel(LabelScope.PROGRAM, programId = missingId)
        val portfolioLabel = sampleLabel(LabelScope.PORTFOLIO, portfolioId = missingId)
        coEvery { projectService.getById(missingId) } returns null
        coEvery { programService.getById(missingId) } returns null
        coEvery { portfolioService.getById(missingId) } returns null
        coEvery { service.getById(missingId) } returns null

        val controller = mutationController()
        assertFailsWith<IllegalStateException> { controller.create(authentication, projectInput) }
        assertFailsWith<IllegalStateException> { controller.create(authentication, programInput) }
        assertFailsWith<IllegalStateException> { controller.create(authentication, portfolioInput) }
        assertFailsWith<IllegalStateException> {
            controller.update(authentication, missingId, "Missing", null, 0)
        }
        assertFailsWith<IllegalStateException> { controller.delete(authentication, missingId) }

        coEvery { service.getById(projectLabel.id) } returns projectLabel
        coEvery { service.getById(programLabel.id) } returns programLabel
        coEvery { service.getById(portfolioLabel.id) } returns portfolioLabel
        assertFailsWith<IllegalStateException> {
            controller.update(authentication, projectLabel.id, "Missing", null, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller.update(authentication, programLabel.id, "Missing", null, 0)
        }
        assertFailsWith<IllegalStateException> {
            controller.update(authentication, portfolioLabel.id, "Missing", null, 0)
        }
    }

    private fun sampleLabel(
        scope: LabelScope,
        portfolioId: UUID? = null,
        programId: UUID? = null,
        projectId: UUID? = null,
    ) = Label(
        id = UUID.random(),
        name = "Label",
        colorHex = "#abcdef",
        scope = scope,
        portfolioId = portfolioId,
        programId = programId,
        projectId = projectId,
    )

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "GIT",
        name = "Git",
        ownerProfileId = profileId,
    )

    private fun sampleProgram() = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROGRAM",
        name = "Program",
        ownerProfileId = profileId,
    )

    private fun samplePortfolio() = Portfolio(
        id = UUID.random(),
        key = "PORTFOLIO",
        name = "Portfolio",
        ownerProfileId = profileId,
    )
}
