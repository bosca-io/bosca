package bosca.workops.controller

import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.workops.model.automation.AutomationRule
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.project.Portfolio
import bosca.workops.model.project.Program
import bosca.workops.model.project.Project
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Permission-focused tests for AutomationMutationController.update.
 *
 * Previous behavior: update verified MANAGE against the input's scope/scopeId.
 * A user with MANAGE on project X could call update(targetRuleId,
 * scope=PROJECT, scopeId=X) to modify a rule that actually lived on project Y
 * (where they had no permission) — the service ignored the input scope and
 * mutated the rule on its existing scope.
 *
 * Post-fix behavior: update verifies MANAGE against the *existing rule's*
 * scope/scopeId, and rejects any attempt to change scope/scopeId via update.
 */
class AutomationControllerTest {

    private val ruleService = mockk<AutomationRuleService>()
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>(relaxed = true)
    private val portfolioService = mockk<PortfolioService>(relaxed = true)
    private val projectPermissions = mockk<ProjectPermissionEvaluator>()
    private val programPermissions = mockk<ProgramPermissionEvaluator>(relaxed = true)
    private val portfolioPermissions = mockk<PortfolioPermissionEvaluator>(relaxed = true)
    private val groupEvaluator = mockk<GroupEvaluator>(relaxed = true)
    private val json = Json { ignoreUnknownKeys = true }

    private val attackerProfileId = UUID.random()
    private val attackerPrincipalId = UUID.random()

    private fun attacker(): AuthenticationContext {
        val principal = Principal(id = attackerPrincipalId, primaryProfileId = attackerProfileId)
        val groups = listOf(Group(id = UUID.random(), name = "users", description = "", type = GroupType.SYSTEM))
        return ImpersonatedAuthenticationContext(principal, groups)
    }

    private fun controller() = AutomationMutationController(
        ruleService = ruleService,
        projectService = projectService,
        programService = programService,
        portfolioService = portfolioService,
        projectPermissions = projectPermissions,
        programPermissions = programPermissions,
        portfolioPermissions = portfolioPermissions,
        groupEvaluator = groupEvaluator,
        json = json,
    )

    @Test
    fun `create decodes global rule and requires administrator`() = runTest {
        val input = sampleDocumentInput(scope = "GLOBAL", scopeId = null)
        val rule = sampleRule(UUID.random(), AutomationScope.GLOBAL, null)
        coEvery { ruleService.create(any()) } returns rule

        assertEquals(rule, controller().create(attacker(), input))
        coVerify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(any()) }
        coVerify(exactly = 1) {
            ruleService.create(match {
                it.scope == AutomationScope.GLOBAL &&
                        it.scopeId == null &&
                        it.name == input.name &&
                        it.trigger is Trigger.TaskCreated &&
                        it.conditions.isEmpty() &&
                        it.actions.isEmpty() &&
                        it.failureMode == FailureMode.STOP_ON_ERROR
            })
        }
    }

    @Test
    fun `create requires scope id for entity scoped rules`() = runTest {
        for (scope in listOf("PORTFOLIO", "PROGRAM", "PROJECT")) {
            assertFailsWith<IllegalStateException> {
                controller().create(attacker(), sampleDocumentInput(scope, scopeId = null))
            }
        }
        coVerify(exactly = 0) { ruleService.create(any()) }
    }

    @Test
    fun `create errors when scoped entity is missing`() = runTest {
        val portfolioId = UUID.random()
        val programId = UUID.random()
        val projectId = UUID.random()
        coEvery { portfolioService.getById(portfolioId) } returns null
        coEvery { programService.getById(programId) } returns null
        coEvery { projectService.getById(projectId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().create(attacker(), sampleDocumentInput("PORTFOLIO", portfolioId))
        }
        assertFailsWith<IllegalStateException> {
            controller().create(attacker(), sampleDocumentInput("PROGRAM", programId))
        }
        assertFailsWith<IllegalStateException> {
            controller().create(attacker(), sampleDocumentInput("PROJECT", projectId))
        }
        coVerify(exactly = 0) { ruleService.create(any()) }
    }

    @Test
    fun `create verifies portfolio manage permission`() = runTest {
        val portfolio = samplePortfolio()
        val input = sampleDocumentInput("PORTFOLIO", portfolio.id)
        val rule = sampleRule(UUID.random(), AutomationScope.PORTFOLIO, portfolio.id)
        coEvery { portfolioService.getById(portfolio.id) } returns portfolio
        coEvery {
            portfolioPermissions.verifyAllowed(any(), portfolio, PermissionAction.MANAGE)
        } returns Unit
        coEvery { ruleService.create(any()) } returns rule

        assertEquals(rule, controller().create(attacker(), input))
        coVerify(exactly = 1) {
            portfolioPermissions.verifyAllowed(any(), portfolio, PermissionAction.MANAGE)
        }
    }

    @Test
    fun `create verifies program manage permission`() = runTest {
        val program = sampleProgram()
        val input = sampleDocumentInput("PROGRAM", program.id)
        val rule = sampleRule(UUID.random(), AutomationScope.PROGRAM, program.id)
        coEvery { programService.getById(program.id) } returns program
        coEvery {
            programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE)
        } returns Unit
        coEvery { ruleService.create(any()) } returns rule

        assertEquals(rule, controller().create(attacker(), input))
        coVerify(exactly = 1) {
            programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE)
        }
    }

    @Test
    fun `update verifies MANAGE against existing rule's scope not input's scope`() = runTest {
        // Attacker has MANAGE on attackerProject but rule lives on victimProject.
        val attackerProjectId = UUID.random()
        val victimProjectId = UUID.random()
        val ruleId = UUID.random()
        val existingRule = sampleRule(id = ruleId, scope = AutomationScope.PROJECT, scopeId = victimProjectId)
        val victimProject = sampleProject(id = victimProjectId)
        coEvery { ruleService.getById(ruleId) } returns existingRule
        coEvery { projectService.getById(victimProjectId) } returns victimProject
        coEvery {
            projectPermissions.verifyAllowed(any(), victimProject, PermissionAction.MANAGE)
        } throws SecurityException("denied")

        // Attacker passes their own scopeId in the input, but the controller must
        // verify against the *existing* rule's scope.
        val input = sampleDocumentInput(scope = "PROJECT", scopeId = attackerProjectId)

        assertFailsWith<SecurityException> {
            controller().update(attacker(), ruleId, input, expectedVersion = 0L)
        }
        coVerify(exactly = 0) { ruleService.update(any(), any(), any()) }
    }

    @Test
    fun `update rejects attempt to change scope or scopeId`() = runTest {
        val ruleId = UUID.random()
        val originalProjectId = UUID.random()
        val newProjectId = UUID.random()
        val existingRule = sampleRule(id = ruleId, scope = AutomationScope.PROJECT, scopeId = originalProjectId)
        val originalProject = sampleProject(id = originalProjectId)
        coEvery { ruleService.getById(ruleId) } returns existingRule
        coEvery { projectService.getById(originalProjectId) } returns originalProject
        coEvery {
            projectPermissions.verifyAllowed(any(), originalProject, PermissionAction.MANAGE)
        } returns Unit

        // Attacker has MANAGE on the existing project but tries to relocate the rule.
        val input = sampleDocumentInput(scope = "PROJECT", scopeId = newProjectId)

        assertFailsWith<IllegalStateException> {
            controller().update(attacker(), ruleId, input, expectedVersion = 0L)
        }
        coVerify(exactly = 0) { ruleService.update(any(), any(), any()) }
    }

    @Test
    fun `update rejects attempt to change scope kind`() = runTest {
        val project = sampleProject(UUID.random())
        val existingRule = sampleRule(UUID.random(), AutomationScope.PROJECT, project.id)
        coEvery { ruleService.getById(existingRule.id) } returns existingRule
        coEvery { projectService.getById(project.id) } returns project
        coEvery {
            projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE)
        } returns Unit

        assertFailsWith<IllegalStateException> {
            controller().update(
                attacker(),
                existingRule.id,
                sampleDocumentInput("PROGRAM", project.id),
                0,
            )
        }
        coVerify(exactly = 0) { ruleService.update(any(), any(), any()) }
    }

    @Test
    fun `update succeeds when caller has MANAGE on existing scope and scope is unchanged`() = runTest {
        val ruleId = UUID.random()
        val projectId = UUID.random()
        val existingRule = sampleRule(id = ruleId, scope = AutomationScope.PROJECT, scopeId = projectId)
        val project = sampleProject(id = projectId)
        coEvery { ruleService.getById(ruleId) } returns existingRule
        coEvery { projectService.getById(projectId) } returns project
        coEvery { projectPermissions.verifyAllowed(any(), project, PermissionAction.MANAGE) } returns Unit
        coEvery { ruleService.update(eq(ruleId), any(), eq(0L)) } returns existingRule.copy(name = "Updated")

        val input = sampleDocumentInput(scope = "PROJECT", scopeId = projectId, name = "Updated")
        controller().update(attacker(), ruleId, input, expectedVersion = 0L)

        coVerify { ruleService.update(ruleId, any(), 0L) }
    }

    @Test
    fun `update errors when rule does not exist`() = runTest {
        val ruleId = UUID.random()
        coEvery { ruleService.getById(ruleId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().update(attacker(), ruleId, sampleDocumentInput(scope = "PROJECT", scopeId = UUID.random()), 0L)
        }
    }

    @Test
    fun `delete errors when rule does not exist`() = runTest {
        val ruleId = UUID.random()
        coEvery { ruleService.getById(ruleId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().delete(attacker(), ruleId)
        }
        coVerify(exactly = 0) { ruleService.delete(any()) }
    }

    @Test
    fun `delete verifies scope and removes rule`() = runTest {
        val program = sampleProgram()
        val rule = sampleRule(UUID.random(), AutomationScope.PROGRAM, program.id)
        coEvery { ruleService.getById(rule.id) } returns rule
        coEvery { programService.getById(program.id) } returns program
        coEvery {
            programPermissions.verifyAllowed(any(), program, PermissionAction.MANAGE)
        } returns Unit
        coEvery { ruleService.delete(rule.id) } returns Unit

        assertEquals(true, controller().delete(attacker(), rule.id))
        coVerify(exactly = 1) { ruleService.delete(rule.id) }
    }

    @Test
    fun `setEnabled errors when rule does not exist`() = runTest {
        val ruleId = UUID.random()
        coEvery { ruleService.getById(ruleId) } returns null

        assertFailsWith<IllegalStateException> {
            controller().setEnabled(attacker(), ruleId, false, 2)
        }
        coVerify(exactly = 0) { ruleService.update(any(), any(), any()) }
    }

    @Test
    fun `setEnabled preserves rule content and changes enabled flag`() = runTest {
        val rule = sampleRule(UUID.random(), AutomationScope.GLOBAL, null)
        val updated = rule.copy(enabled = false, version = 3)
        coEvery { ruleService.getById(rule.id) } returns rule
        coEvery { ruleService.update(eq(rule.id), any(), eq(2)) } returns updated

        assertEquals(updated, controller().setEnabled(attacker(), rule.id, false, 2))
        coVerify(exactly = 1) { groupEvaluator.verifyHasAdminGroup(any()) }
        coVerify(exactly = 1) {
            ruleService.update(
                rule.id,
                match {
                    it.scope == rule.scope &&
                            it.scopeId == rule.scopeId &&
                            it.name == rule.name &&
                            it.description == rule.description &&
                            !it.enabled &&
                            it.trigger is Trigger.TaskCreated &&
                            it.conditions.isEmpty() &&
                            it.actions.isEmpty() &&
                            it.runAsProfileId == rule.runAsProfileId &&
                            it.failureMode == rule.failureMode &&
                            it.executionLogRetentionDays == rule.executionLogRetentionDays &&
                            it.maxFiresPerTaskPerHour == rule.maxFiresPerTaskPerHour
                },
                2,
            )
        }
    }

    // --- helpers ---

    private fun sampleProject(id: UUID): Project = Project(
        id = id, programId = UUID.random(),
        key = "P", name = "Project", ownerProfileId = attackerProfileId,
    )

    private fun samplePortfolio(): Portfolio = Portfolio(
        id = UUID.random(),
        key = "PORT",
        name = "Portfolio",
        ownerProfileId = attackerProfileId,
    )

    private fun sampleProgram(): Program = Program(
        id = UUID.random(),
        portfolioId = UUID.random(),
        key = "PROG",
        name = "Program",
        ownerProfileId = attackerProfileId,
    )

    private fun sampleRule(id: UUID, scope: AutomationScope, scopeId: UUID?): AutomationRule = AutomationRule(
        id = id,
        scope = scope,
        scopeId = scopeId,
        name = "Rule",
        description = null,
        enabled = true,
        trigger = buildJsonObject { put("type", JsonPrimitive("TaskCreated")) },
        conditions = JsonArray(emptyList()),
        actions = JsonArray(emptyList()),
        runAsProfileId = attackerProfileId,
        failureMode = FailureMode.STOP_ON_ERROR,
        executionLogRetentionDays = 30,
        maxFiresPerTaskPerHour = 5,
        version = 0,
    )

    private fun sampleDocumentInput(scope: String, scopeId: UUID?, name: String = "Rule"): AutomationRuleDocumentInput =
        AutomationRuleDocumentInput(
            scope = scope,
            scopeId = scopeId,
            name = name,
            description = null,
            enabled = true,
            trigger = buildJsonObject {
                put("type", JsonPrimitive("TaskCreated"))
            },
            conditions = JsonArray(emptyList()),
            actions = JsonArray(emptyList()),
            runAsProfileId = attackerProfileId,
            failureMode = "STOP_ON_ERROR",
            executionLogRetentionDays = 30,
            maxFiresPerTaskPerHour = 5,
        )
}
