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
import bosca.security.service.SecurityService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.sla.SlaGoal
import bosca.workops.model.sla.SlaOutcome
import bosca.workops.model.task.CreateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.SlaGoalRepositoryImpl
import bosca.workops.repository.SlaPolicyRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskRepositoryImpl
import bosca.workops.repository.TaskSlaStateRepositoryImpl
import bosca.workops.repository.TaskTypeRepositoryImpl
import bosca.workops.repository.TaskTypeSchemeRepositoryImpl
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.TaskPermissionRepositoryImpl
import bosca.workops.repository.TaskAffectedProjectRepositoryImpl
import bosca.workops.repository.WorkflowRepositoryImpl
import bosca.workops.repository.WorkflowSchemeRepositoryImpl
import bosca.workops.repository.WorkingCalendarRepositoryImpl
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 8.1 — SLA evaluator edges, tick job emission, and the
 * working-calendar deadline math.
 */
class Phase8SlaIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase8_sla_test")
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
                key = "workops-phase8-sla-test",
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
    private val ALWAYS_CALENDAR_ID = UUID.parse("c0000000-0000-0000-0000-000000000001")

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
    private val calendarRepo = WorkingCalendarRepositoryImpl()
    private val policyRepo = SlaPolicyRepositoryImpl()
    private val goalRepo = SlaGoalRepositoryImpl()
    private val stateRepo = TaskSlaStateRepositoryImpl()

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

    private val calendarService = WorkingCalendarServiceImpl(calendarRepo)
    private val policyService = SlaPolicyServiceImpl(policyRepo, goalRepo)

    private val evaluator = SlaEvaluator(
        taskSlaStateRepo = stateRepo,
        goalRepo = goalRepo,
        calendarService = calendarService,
        projectRepository = projectRepo,
    )

    private val tickService = SlaTickServiceImpl(
        stateRepo = stateRepo,
        goalRepo = goalRepo,
        taskRepository = taskRepo,
        projectRepository = projectRepo,
        json = json,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
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
                connection().useStatement("DELETE FROM workops.task_sla_state") { it.execute() }
                connection().useStatement("DELETE FROM workops.sla_goal") { it.execute() }
                connection().useStatement("DELETE FROM workops.sla_policy") { it.execute() }
                connection().useStatement("DELETE FROM workops.notification") { it.execute() }
                connection().useStatement("DELETE FROM workops.notification_outbox") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_watcher") { it.execute() }
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
    private val callerPrincipalId: UUID = UUID.parse("22222222-0000-0000-0000-000000000000")
    private val callerProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")

    private suspend fun seedProject() = projectService.create(
        ProjectInput(
            programId = programService.create(
                ProgramInput(
                    portfolioId = portfolioService.create(
                        PortfolioInput(key = "P81", name = "P81",
                            description = null, ownerProfileId = ownerId)
                    ).id,
                    key = "P81P", name = "P81 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P81", name = "P81 Project", description = null,
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
    fun `Always calendar adds wall-clock minutes`() = withDb {
        val cal = calendarService.getById(ALWAYS_CALENDAR_ID)
        assertNotNull(cal)
        val start = OffsetDateTime.parse("2026-04-30T12:00:00Z")
        val end = WorkingCalendarMath.addBusinessMinutes(cal, start, 60)
        assertEquals(start.plusMinutes(60), end)
    }

    @Test
    fun `Mon-Fri 9-17 calendar wraps to next morning`() = withDb {
        val cal = calendarService.getById(UUID.parse("c0000000-0000-0000-0000-000000000002"))
        assertNotNull(cal)
        // Friday 16:00 UTC; +120 minutes lands at Monday 10:00 UTC.
        val friday1600 = OffsetDateTime.parse("2026-05-01T16:00:00Z")
        val resolved = WorkingCalendarMath.addBusinessMinutes(cal, friday1600, 120)
        assertEquals(OffsetDateTime.parse("2026-05-04T10:00:00Z"), resolved)
    }

    @Test
    fun `evaluator starts goal on first 'open' task and stops on 'done' edge`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val policy = policyService.create("Phase 8 Default", null)
        val goal = policyService.addGoal(
            SlaGoal(
                policyId = policy.id,
                name = "First response",
                startConditions = "open",
                pauseConditions = null,
                stopConditions = "done",
                targetMinutes = 240,
                atRiskAtPercent = 80,
                calendarId = ALWAYS_CALENDAR_ID,
            )
        )
        val edges = evaluator.evaluate(task, StatusCategory.IN_PROGRESS, policy.id)
        assertEquals(listOf(SlaEvaluator.Edge.START), edges)
        val state = stateRepo.get(task.id, goal.id)
        assertNotNull(state)
        assertEquals(SlaOutcome.OPEN, state.outcome)

        val stopped = evaluator.evaluate(task, StatusCategory.DONE, policy.id)
        assertEquals(listOf(SlaEvaluator.Edge.STOP_MET), stopped)
        val final = stateRepo.get(task.id, goal.id)
        assertEquals(SlaOutcome.MET, final?.outcome)
    }

    @Test
    fun `tick emits SLA_BREACHED when due_at is in the past`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val policy = policyService.create("Tick Policy", null)
        val goal = policyService.addGoal(
            SlaGoal(
                policyId = policy.id,
                name = "Resolve",
                startConditions = "open",
                stopConditions = "done",
                targetMinutes = 60,
                atRiskAtPercent = 80,
                calendarId = ALWAYS_CALENDAR_ID,
            )
        )
        val past = OffsetDateTime.now().minusMinutes(120)
        stateRepo.start(task.id, goal.id, past, OffsetDateTime.now().minusMinutes(30))
        val emitted = tickService.tick(OffsetDateTime.now(), atRiskWindowMinutes = 5, limit = 16)
        assertEquals(1, emitted)
        val final = stateRepo.get(task.id, goal.id)
        assertNotNull(final)
        assertEquals(SlaOutcome.BREACHED, final.outcome)
        assertTrue(final.breachEmitted)
    }

    @Test
    fun `tick emits SLA_AT_RISK exactly once per crossing`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val policy = policyService.create("AtRisk Policy", null)
        val goal = policyService.addGoal(
            SlaGoal(
                policyId = policy.id,
                name = "Triage",
                startConditions = "open",
                stopConditions = "done",
                targetMinutes = 100,
                atRiskAtPercent = 80,
                calendarId = ALWAYS_CALENDAR_ID,
            )
        )
        val started = OffsetDateTime.now().minusMinutes(85)
        val due = OffsetDateTime.now().plusMinutes(15)
        stateRepo.start(task.id, goal.id, started, due)

        // First tick: 85m elapsed, threshold 80m → emit AT_RISK.
        val first = tickService.tick(OffsetDateTime.now(), atRiskWindowMinutes = 30, limit = 16)
        assertEquals(1, first)
        // Second tick: at_risk_emitted = true → no emission.
        val second = tickService.tick(OffsetDateTime.now().plusMinutes(1), atRiskWindowMinutes = 30, limit = 16)
        assertEquals(0, second)
    }
}
