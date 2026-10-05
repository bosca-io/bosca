package bosca.workops.controller

import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
import bosca.workops.service.AutomationExecutionLogService
import bosca.workops.service.AutomationRuleService
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AutomationQueryControllerTest {

    private val ruleService = mockk<AutomationRuleService>()
    private val logService = mockk<AutomationExecutionLogService>()
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>()
    private val portfolioService = mockk<PortfolioService>()
    private val projectPermissions = mockk<ProjectPermissionEvaluator>()
    private val programPermissions = mockk<ProgramPermissionEvaluator>()
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>()
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val authentication = mockk<AuthenticationContext>()

    private fun controller() = AutomationQueryController(
        ruleService = ruleService,
        logService = logService,
        projectService = projectService,
        programService = programService,
        portfolioService = portfolioService,
        projectPermissions = projectPermissions,
        programPermissions = programPermissions,
        portfolioPermissions = portfolioPermissions,
        groupEvaluator = groupEvaluator,
    )

    @Test
    fun `global rules require administrator and list global scope`() = runTest {
        val rules = listOf(sampleRule(scope = AutomationScope.GLOBAL, scopeId = null))
        coEvery { ruleService.listInScope(AutomationScope.GLOBAL, null) } returns rules

        assertEquals(rules, controller().rules(authentication, "GLOBAL"))
        coVerify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `entity scoped rules require scope id`() = runTest {
        for (scope in listOf("PORTFOLIO", "PROGRAM", "PROJECT")) {
            assertFailsWith<IllegalStateException> {
                controller().rules(authentication, scope, null)
            }
        }
        coVerify(exactly = 0) { ruleService.listInScope(any(), any()) }
    }

    @Test
    fun `entity scoped rules error when scope resource is missing`() = runTest {
        val portfolioId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        coEvery { portfolioService.getById(portfolioId) } returns null
        coEvery { programService.getById(programId) } returns null
        coEvery { projectService.getById(projectId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().rules(authentication, "PORTFOLIO", portfolioId)
        }
        assertFailsWith<IllegalStateException> {
            controller().rules(authentication, "PROGRAM", programId)
        }
        assertFailsWith<IllegalStateException> {
            controller().rules(authentication, "PROJECT", projectId)
        }
        coVerify(exactly = 0) { ruleService.listInScope(any(), any()) }
    }

    @Test
    fun `portfolio rules verify view and delegate`() = runTest {
        val portfolio = samplePortfolio()
        val rules = listOf(sampleRule(scope = AutomationScope.PORTFOLIO, scopeId = portfolio.id))
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery {
            portfolioPermissions.verifyAllowed(authentication, portfolio, PermissionAction.VIEW)
        } returns Unit
        coEvery { ruleService.listInScope(AutomationScope.PORTFOLIO, portfolio.id) } returns rules

        assertEquals(rules, controller().rules(authentication, "PORTFOLIO", portfolio.id))
    }

    @Test
    fun `program rules verify view and delegate`() = runTest {
        val program = sampleProgram()
        val rules = listOf(sampleRule(scope = AutomationScope.PROGRAM, scopeId = program.id))
        coEvery { programService.getById(program.id) } returns program
        coEvery {
            programPermissions.verifyAllowed(authentication, program, PermissionAction.VIEW)
        } returns Unit
        coEvery { ruleService.listInScope(AutomationScope.PROGRAM, program.id) } returns rules

        assertEquals(rules, controller().rules(authentication, "PROGRAM", program.id))
    }

    @Test
    fun `project rules verify view and delegate`() = runTest {
        val project = sampleProject()
        val rules = listOf(sampleRule(scope = AutomationScope.PROJECT, scopeId = project.id))
        coEvery { projectService.getById(project.id) } returns project
        coEvery {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        } returns Unit
        coEvery { ruleService.listInScope(AutomationScope.PROJECT, project.id) } returns rules

        assertEquals(rules, controller().rules(authentication, "PROJECT", project.id))
    }

    @Test
    fun `rules stop before listing when scope permission is denied`() = runTest {
        val project = sampleProject()
        coEvery { projectService.getById(project.id) } returns project
        coEvery {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        } throws SecurityException("denied")

        assertFailsWith<SecurityException> {
            controller().rules(authentication, "PROJECT", project.id)
        }
        coVerify(exactly = 0) { ruleService.listInScope(any(), any()) }
    }

    @Test
    fun `rule returns null when rule is missing`() = runTest {
        val ruleId = UUID.random()
        coEvery { ruleService.getById(ruleId) } returns null

        assertNull(controller().rule(authentication, ruleId))
    }

    @Test
    fun `rule returns visible scoped rule`() = runTest {
        val rule = sampleRule(scope = AutomationScope.GLOBAL, scopeId = null)
        coEvery { ruleService.getById(rule.id) } returns rule

        assertEquals(rule, controller().rule(authentication, rule.id))
        coVerify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(authentication) }
    }

    @Test
    fun `executionLogs errors when rule is missing`() = runTest {
        val ruleId = UUID.random()
        coEvery { ruleService.getById(ruleId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().executionLogs(authentication, ruleId, 0, 25)
        }
        coVerify(exactly = 0) { logService.listForRule(any(), any(), any()) }
    }

    @Test
    fun `executionLogs verifies rule scope and delegates`() = runTest {
        val project = sampleProject()
        val rule = sampleRule(scope = AutomationScope.PROJECT, scopeId = project.id)
        coEvery { ruleService.getById(rule.id) } returns rule
        coEvery { projectService.getById(project.id) } returns project
        coEvery {
            projectPermissions.verifyAllowed(authentication, project, PermissionAction.VIEW)
        } returns Unit
        coEvery { logService.listForRule(rule.id, 5, 25) } returns emptyList()

        assertEquals(emptyList(), controller().executionLogs(authentication, rule.id, 5, 25))
        coVerify(exactly = 1) { logService.listForRule(rule.id, 5, 25) }
    }

    private fun sampleRule(
        scope: AutomationScope,
        scopeId: UUID?,
    ) = AutomationRule(
        id = UUID.random(),
        scope = scope,
        scopeId = scopeId,
        name = "Rule",
        trigger = buildJsonObject { put("type", JsonPrimitive("TaskCreated")) },
        conditions = JsonArray(emptyList()),
        actions = JsonArray(emptyList()),
        runAsProfileId = UUID.random(),
        failureMode = FailureMode.STOP_ON_ERROR,
    )

    private fun samplePortfolio() = Portfolio(
        id = UUID.random(),
        key = "PORT",
        name = "Portfolio",
        ownerProfileId = UUID.random(),
    )

    private fun sampleProgram() = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROG",
        name = "Program",
        ownerProfileId = UUID.random(),
    )

    private fun sampleProject() = Project(
        id = UUID.random(),
        programId = UUID.random(),
        key = "PROJ",
        name = "Project",
        ownerProfileId = UUID.random(),
    )
}
