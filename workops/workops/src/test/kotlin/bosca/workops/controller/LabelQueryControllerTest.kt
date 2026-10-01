package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Permission-focused tests for LabelQueryController.
 *
 * Previous behavior: every endpoint omitted AuthenticationContext entirely
 * and returned label data to anonymous callers. Post-fix: every endpoint
 * requires authentication AND verifies VIEW on the parent scope.
 */
class LabelQueryControllerTest {

    private val service = mockk<LabelService>()
    private val projectService = mockk<ProjectService>()
    private val projectPermissions = mockk<ProjectPermissionEvaluator>()
    private val programService = mockk<ProgramService>()
    private val programPermissions = mockk<ProgramPermissionEvaluator>()
    private val portfolioService = mockk<PortfolioService>()
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>()

    private val principalId = UUID.random()
    private val profileId = UUID.random()

    private fun authenticated(): AuthenticationContext {
        val principal = Principal(id = principalId, primaryProfileId = profileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun anonymous(): AuthenticationContext = AuthenticationContext(null, null)

    private fun controller() = LabelQueryController(
        service = service,
        projectService = projectService,
        projectPermissions = projectPermissions,
        programService = programService,
        programPermissions = programPermissions,
        portfolioService = portfolioService,
        portfolioPermissions = portfolioPermissions,
    )

    @Test
    fun `byProject throws when caller lacks VIEW on project`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        coEvery { projectService.getById(projectId) } returns project
        coEvery {
            projectPermissions.verifyAllowed(any(), project, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().byProject(authenticated(), projectId)
        }
    }

    @Test
    fun `byProject returns empty when project not found`() = runTest {
        val projectId = UUID.random()
        coEvery { projectService.getById(projectId) } returns null

        assertTrue(controller().byProject(authenticated(), projectId).isEmpty())
    }

    @Test
    fun `byProject returns labels when caller has VIEW`() = runTest {
        val projectId = UUID.random()
        val project = sampleProject(projectId)
        val labels = listOf(sampleLabel(projectId = projectId))
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.VIEW) } returns Unit
        coEvery { service.listByProject(projectId) } returns labels

        assertEquals(labels, controller().byProject(authenticated(), projectId))
    }

    @Test
    fun `global denies anonymous callers`() = runTest {
        assertFailsWith<IllegalStateException> {
            controller().global(anonymous())
        }
    }

    @Test
    fun `byProgram throws when caller lacks VIEW on program`() = runTest {
        val programId = UUID.random()
        val program = sampleProgram(programId)
        coEvery { programService.getById(programId) } returns program
        coEvery {
            programPermissions.verifyAllowed(any(), program, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().byProgram(authenticated(), programId)
        }
    }

    @Test
    fun `byPortfolio throws when caller lacks VIEW on portfolio`() = runTest {
        val portfolioId = UUID.random()
        val portfolio = samplePortfolio(portfolioId)
        coEvery { portfolioService.getById(portfolioId) } returns portfolio
        coEvery {
            portfolioPermissions.verifyAllowed(any(), portfolio, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().byPortfolio(authenticated(), portfolioId)
        }
    }

    @Test
    fun `label returns null for project label when caller lacks VIEW on project`() = runTest {
        val labelId = UUID.random()
        val projectId = UUID.random()
        val label = sampleLabel(projectId = projectId, labelId = labelId)
        val project = sampleProject(projectId)
        coEvery { service.getById(labelId) } returns label
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returns false

        assertNull(controller().label(authenticated(), labelId))
    }

    @Test
    fun `label returns label for project label when caller has VIEW on project`() = runTest {
        val labelId = UUID.random()
        val projectId = UUID.random()
        val label = sampleLabel(projectId = projectId, labelId = labelId)
        val project = sampleProject(projectId)
        coEvery { service.getById(labelId) } returns label
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.isAllowed(any<AuthenticationContext>(), project, PermissionAction.VIEW) } returns true

        assertEquals(label, controller().label(authenticated(), labelId))
    }

    // --- helpers ---

    private fun sampleLabel(projectId: UUID? = null, labelId: UUID = UUID.random()): Label = Label(
        id = labelId,
        name = "bug",
        colorHex = null,
        scope = if (projectId != null) LabelScope.PROJECT else LabelScope.GLOBAL,
        portfolioId = null,
        programId = null,
        projectId = projectId,
    )

    private fun sampleProject(id: UUID): Project = Project(
        id = id, programId = UUID.random(),
        key = "P", name = "Project", ownerProfileId = profileId,
    )

    private fun sampleProgram(id: UUID): Program = Program(
        id = id, portfolioId = UUID.random(),
        key = "PROG", name = "Program", ownerProfileId = profileId,
    )

    private fun samplePortfolio(id: UUID): Portfolio = Portfolio(
        id = id, key = "PORT", name = "Portfolio", ownerProfileId = profileId,
    )
}
