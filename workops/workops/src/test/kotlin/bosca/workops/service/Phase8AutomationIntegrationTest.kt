@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.Profile
import bosca.profile.model.ProfileType
import bosca.profile.model.ProfileVisibility
import bosca.profile.profile.service.ProfileService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.JobQueue
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.automation.Action
import bosca.workops.model.automation.AutomationOutcome
import bosca.workops.model.automation.AutomationScope
import bosca.workops.model.automation.FailureMode
import bosca.workops.model.automation.Trigger
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.workflow.Condition
import io.mockk.coEvery
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.AutomationExecutionLogRepositoryImpl
import bosca.workops.repository.AutomationLoopGuardRepositoryImpl
import bosca.workops.repository.AutomationRuleRepositoryImpl
import bosca.workops.repository.NotificationOutboxRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskLinkRepositoryImpl
import bosca.workops.repository.TaskRepositoryImpl
import bosca.workops.repository.TaskTypeRepositoryImpl
import bosca.workops.repository.TaskTypeSchemeRepositoryImpl
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.TaskPermissionRepositoryImpl
import bosca.workops.repository.TaskAffectedProjectRepositoryImpl
import bosca.workops.repository.WorkflowRepositoryImpl
import bosca.workops.repository.WorkflowSchemeRepositoryImpl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase8AutomationIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase8_automation_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    url = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                    maxConnections = 5,
                ),
                key = "workops-phase8-automation-test",
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking {
                pool.close()
            }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

        private val portfolioRepo = PortfolioRepositoryImpl()
    private val portfolioPermissionRepo = PortfolioPermissionRepositoryImpl()
        private val programRepo = ProgramRepositoryImpl()
    private val programPermissionRepo = ProgramPermissionRepositoryImpl()
        private val projectRepo = ProjectRepositoryImpl()
    private val projectPermissionRepo = ProjectPermissionRepositoryImpl()
    private val keyCounterRepo = ProjectKeyCounterRepositoryImpl()
    private val taskTypeRepo = TaskTypeRepositoryImpl()
    private val taskTypeSchemeRepo = TaskTypeSchemeRepositoryImpl()
    private val statusRepo = StatusRepositoryImpl()
    private val priorityRepo = PriorityRepositoryImpl()
        private val taskRepo = TaskRepositoryImpl()
    private val taskPermissionRepo = TaskPermissionRepositoryImpl()
    private val historyRepo = TaskHistoryRepositoryImpl()
    private val workflowRepo = WorkflowRepositoryImpl()
    private val schemeRepo = WorkflowSchemeRepositoryImpl()
    private val fieldConfigRepo = TaskFieldConfigurationRepositoryImpl()
    private val workflowQueryRepo = WorkflowQueryRepositoryImpl()
    private val affectedRepo = TaskAffectedProjectRepositoryImpl()
    private val outboxRepo = NotificationOutboxRepositoryImpl()
    private val outboxService = NotificationOutboxServiceImpl(
        outboxRepo,
        mockk<JobQueue>(relaxed = true),
        mockk<NotificationChannelDeliveryService>(relaxed = true),
    )
    private val ruleRepo = AutomationRuleRepositoryImpl()
    private val logRepo = AutomationExecutionLogRepositoryImpl()
    private val loopRepo = AutomationLoopGuardRepositoryImpl()

    private val securityService = mockk<SecurityService>(relaxed = true)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
    private val groupEvaluator = bosca.security.service.GroupEvaluator(securityService)
    private val portfolioPermissionEvaluator = PortfolioPermissionEvaluator(portfolioService, securityService, groupEvaluator)
    private val programService = ProgramServiceImpl(programRepo, portfolioRepo, programPermissionRepo, portfolioPermissionRepo, portfolioPermissionEvaluator)
    private val programPermissionEvaluator = ProgramPermissionEvaluator(programService, securityService, groupEvaluator)
    private val projectService = ProjectServiceImpl(projectRepo, programRepo, keyCounterRepo, projectPermissionRepo, programPermissionRepo, programPermissionEvaluator)
    private val projectPermissionEvaluator = ProjectPermissionEvaluator(projectService, securityService, groupEvaluator)
    private val workflowService = WorkflowServiceImpl(workflowRepo, schemeRepo, projectService, workflowQueryRepo)
    private val customFieldService = TaskCustomFieldServiceImpl(fieldConfigRepo)
    private val affectedService = TaskAffectedProjectServiceImpl(affectedRepo)
    private val taskService = TaskServiceImpl(
        taskRepository = taskRepo,
        taskHistoryRepository = historyRepo,
        projectService = projectService,
        programService = programService,
        taskTypeService = TaskTypeServiceImpl(taskTypeRepo),
        taskTypeSchemeService = TaskTypeSchemeServiceImpl(taskTypeSchemeRepo),
        statusService = StatusServiceImpl(statusRepo),
        priorityService = PriorityServiceImpl(priorityRepo),
        workflowService = workflowService,
        workflowEvaluator = WorkflowEvaluator(),
        customFieldService = customFieldService,
        json = json,
        taskPermissionRepository = taskPermissionRepo,
        affectedProjectService = affectedService,
        requirementServiceProvider = mockk<RequirementService>(relaxed = true).asProvider(),
        metadataService = mockk(relaxed = true),
        sprintService = mockk(relaxed = true),
        projectPermissionEvaluator = projectPermissionEvaluator,
    )

    private val linkRepo = TaskLinkRepositoryImpl()
    private val linkService = TaskLinkServiceImpl(linkRepo, taskService, securityService, mockk(relaxed = true))

    private val ruleService = AutomationRuleServiceImpl(ruleRepo, json)
    private val profileService = mockk<ProfileService>()
    private val executor = AutomationExecutorImpl(
        logRepo,
        loopRepo,
        taskService,
        linkService,
        outboxService,
        profileService,
        json,
    )
    private val dispatcher = AutomationDispatcherImpl(ruleRepo, executor, json)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        coEvery { profileService.getById(runAsProfileId) } returns Profile(
            id = runAsProfileId,
            type = ProfileType.GENERIC,
            principal = callerPrincipalId,
            name = "Automation",
            visibility = ProfileVisibility.USER,
        )
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        if (!schemaInitialized) {
            runBlocking { FlywayMigration(pool).migrate(listOf(CoreMigration(), WorkOpsMigration())) }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("DELETE FROM workops.automation_loop_guard") { it.execute() }
                connection().useStatement("DELETE FROM workops.automation_execution_log") { it.execute() }
                connection().useStatement("DELETE FROM workops.automation_rule") { it.execute() }
                connection().useStatement("DELETE FROM workops.notification_outbox") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.task") { it.execute() }
                connection().useStatement("DELETE FROM workops.project_key_counter") { it.execute() }
                connection().useStatement("DELETE FROM workops.project") { it.execute() }
                connection().useStatement("DELETE FROM workops.program") { it.execute() }
                connection().useStatement("DELETE FROM workops.portfolio") { it.execute() }
            }
        }
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val mgr = pool.connection()
        try {
            withContext(mgr.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { mgr.release() }
        }
    }

    private val ownerId: UUID = UUID.parse("11111111-1111-1111-1111-111111111111")
    private val callerProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")
    private val callerPrincipalId: UUID = UUID.parse("22222222-0000-0000-0000-000000000000")
    private val runAsProfileId: UUID = UUID.parse("44444444-4444-4444-4444-444444444444")

    private suspend fun seedProject() = projectService.create(
        ProjectInput(
            programId = programService.create(
                ProgramInput(
                    portfolioId = portfolioService.create(
                        PortfolioInput(key = "P82", name = "P82", description = null,
                            ownerProfileId = ownerId)
                    ).id,
                    key = "P82P", name = "P82 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P82", name = "P82 Project", description = null,
            ownerProfileId = ownerId,
        )
    )

    private suspend fun createTask(projectId: UUID) = taskService.create(
        CreateTaskInput(projectId = projectId, summary = "task"),
        actingPrincipalId = callerPrincipalId,
        actingProfileId = callerProfileId,
        reporterProfileId = callerProfileId,
    )

    @Test
    fun `rule create round-trips with discriminated trigger and actions`() = withDb {
        val project = seedProject()
        val rule = ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Auto-comment on create",
                description = null,
                enabled = true,
                trigger = Trigger.TaskCreated(),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.AddComment(template = "thanks for filing!")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        assertNotNull(ruleService.getById(rule.id))
        val list = ruleService.listEnabledForScope(AutomationScope.PROJECT, project.id)
        assertEquals(1, list.size)
    }

    @Test
    fun `dispatcher fires AddComment on TaskCreated and queues an outbox row`() = withDb {
        val project = seedProject()
        ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Auto-comment",
                description = null,
                enabled = true,
                trigger = Trigger.TaskCreated(),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.AddComment(template = "thanks for filing!")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        val task = createTask(project.id)
        dispatcher.fireTaskCreated(task, project.id, programId = null, portfolioId = null)
        assertEquals(1L, outboxRepo.pendingCount(),
            "AddComment action should queue an AUTOMATION_COMMENT outbox row")
    }

    @Test
    fun `pending action raises NOT_IMPLEMENTED outcome and writes log`() = withDb {
        val project = seedProject()
        val rule = ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Bad action",
                description = null,
                enabled = true,
                trigger = Trigger.TaskCreated(),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.RunScript(scriptKey = "noop")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        val task = createTask(project.id)
        executor.run(rule, AutomationContext(triggeringTask = task, projectId = project.id))
        val logs = logRepo.listForRule(rule.id, 0, 16)
        assertTrue(logs.isNotEmpty())
        assertEquals(AutomationOutcome.NOT_IMPLEMENTED.name, logs.first().outcome.name)
    }

    @Test
    fun `loop guard short-circuits after maxFiresPerTaskPerHour`() = withDb {
        val project = seedProject()
        val rule = ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Looped",
                description = null,
                enabled = true,
                trigger = Trigger.TaskUpdated(),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.AddComment(template = "loop")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.CONTINUE,
                maxFiresPerTaskPerHour = 2,
            )
        )
        val task = createTask(project.id)
        // Three executions: two OK, third LOOP_GUARD_TRIPPED.
        executor.run(rule, AutomationContext(task, project.id))
        executor.run(rule, AutomationContext(task, project.id))
        val third = executor.run(rule, AutomationContext(task, project.id))
        assertEquals(AutomationOutcome.LOOP_GUARD_TRIPPED, third)
    }

    @Test
    fun `custom field automation dispatches the changed field key`() = withDb {
        val project = seedProject()
        ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Set risk after summary change",
                description = null,
                enabled = true,
                trigger = Trigger.TaskUpdated(fieldKeys = listOf("summary")),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.SetFieldValue(fieldKey = "risk", expression = "high")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Comment after risk change",
                description = null,
                enabled = true,
                trigger = Trigger.TaskUpdated(fieldKeys = listOf("risk")),
                conditions = listOf(Condition.Always),
                actions = listOf(Action.AddComment(template = "risk changed")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        val task = createTask(project.id)
        provides<AutomationDispatcher>(singleton = true) { dispatcher }

        taskService.update(
            task.id,
            UpdateTaskInput(summary = "changed", expectedVersion = task.version),
            callerPrincipalId,
            callerProfileId,
        )

        val reloaded = taskService.getById(task.id)!!
        assertEquals(JsonPrimitive("high"), reloaded.customFieldValues["risk"])
        assertEquals(1L, outboxRepo.pendingCount())
    }

    @Test
    fun `built-in task updates dispatch camel case automation field keys`() = withDb {
        val project = seedProject()
        for (fieldKey in listOf("priorityId", "descriptionMarkdown")) {
            ruleService.create(
                AutomationRuleInput(
                    scope = AutomationScope.PROJECT,
                    scopeId = project.id,
                    name = "Comment after $fieldKey change",
                    description = null,
                    enabled = true,
                    trigger = Trigger.TaskUpdated(fieldKeys = listOf(fieldKey)),
                    conditions = listOf(Condition.Always),
                    actions = listOf(Action.AddComment(template = "$fieldKey changed")),
                    runAsProfileId = runAsProfileId,
                    failureMode = FailureMode.STOP_ON_ERROR,
                )
            )
        }
        val task = createTask(project.id)
        val highPriorityId = priorityRepo.getAll().first { it.name == "High" }.id
        provides<AutomationDispatcher>(singleton = true) { dispatcher }

        taskService.update(
            task.id,
            UpdateTaskInput(
                priorityId = highPriorityId,
                descriptionMarkdown = "Updated by test",
                expectedVersion = task.version,
            ),
            callerPrincipalId,
            callerProfileId,
        )

        assertEquals(2L, outboxRepo.pendingCount())
    }

    @Test
    fun `loop guard reserves a fire before a task mutation redispatches`() = withDb {
        val project = seedProject()
        val rule = ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Self-updating rule",
                description = null,
                enabled = true,
                trigger = Trigger.TaskUpdated(),
                conditions = listOf(Condition.Always),
                actions = listOf(
                    Action.EditTask(
                        buildJsonObject { put("summary", JsonPrimitive("automated")) }
                    )
                ),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
                maxFiresPerTaskPerHour = 1,
            )
        )
        val task = createTask(project.id)
        provides<AutomationDispatcher>(singleton = true) { dispatcher }

        taskService.update(
            task.id,
            UpdateTaskInput(summary = "trigger", expectedVersion = task.version),
            callerPrincipalId,
            callerProfileId,
        )

        val reloaded = taskService.getById(task.id)!!
        assertEquals("automated", reloaded.summary)
        assertEquals(task.version + 2, reloaded.version)
        assertEquals(1L, loopRepo.currentCount(rule.id, task.id))
        assertTrue(
            logRepo.listForRule(rule.id, 0, 16).any {
                it.outcome == AutomationOutcome.LOOP_GUARD_TRIPPED
            }
        )
    }

    @Test
    fun `unmet condition skips the actions and logs SKIPPED`() = withDb {
        val project = seedProject()
        val rule = ruleService.create(
            AutomationRuleInput(
                scope = AutomationScope.PROJECT,
                scopeId = project.id,
                name = "Gated",
                description = null,
                enabled = true,
                trigger = Trigger.TaskCreated(),
                // Never holds, so the action must not run.
                conditions = listOf(Condition.Never),
                actions = listOf(Action.AddComment(template = "should not run")),
                runAsProfileId = runAsProfileId,
                failureMode = FailureMode.STOP_ON_ERROR,
            )
        )
        val task = createTask(project.id)
        val outcome = executor.run(rule, AutomationContext(triggeringTask = task, projectId = project.id))
        assertEquals(AutomationOutcome.SKIPPED, outcome)
        val logs = logRepo.listForRule(rule.id, 0, 16)
        assertEquals(AutomationOutcome.SKIPPED.name, logs.first().outcome.name)
    }
}
