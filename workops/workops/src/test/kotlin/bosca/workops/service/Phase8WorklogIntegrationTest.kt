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
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.worklog.DurationShorthand
import bosca.workops.model.worklog.WorkLogInput
import bosca.workops.model.worklog.WorklogVisibility
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskRepositoryImpl
import bosca.workops.repository.TaskTimeRepositoryImpl
import bosca.workops.repository.TaskTypeRepositoryImpl
import bosca.workops.repository.TaskTypeSchemeRepositoryImpl
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.TaskPermissionRepositoryImpl
import bosca.workops.repository.TaskAffectedProjectRepositoryImpl
import bosca.workops.repository.WorkLogRepositoryImpl
import bosca.workops.repository.WorkflowRepositoryImpl
import bosca.workops.repository.WorkflowSchemeRepositoryImpl
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

class Phase8WorklogIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase8_worklog_test")
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
                key = "workops-phase8-worklog-test",
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
    private val worklogRepo = WorkLogRepositoryImpl()
    private val taskTimeRepo = TaskTimeRepositoryImpl()

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

    private val modeResolver = WorklogModeResolverImpl(projectRepo)
    private val service = WorkLogServiceImpl(worklogRepo, taskService, taskTimeRepo, modeResolver)

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
                connection().useStatement("DELETE FROM workops.worklog") { it.execute() }
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

    private suspend fun seedProject() = projectService.create(
        ProjectInput(
            programId = programService.create(
                ProgramInput(
                    portfolioId = portfolioService.create(
                        PortfolioInput(key = "P83", name = "P83",
                            description = null, ownerProfileId = ownerId)
                    ).id,
                    key = "P83P", name = "P83 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P83", name = "P83 Project", description = null,
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
    fun `duration shorthand round-trips through parse and render`() {
        val seconds = DurationShorthand.parseToSeconds("1w 2d 3h 30m")
        // 1w = 5*8h = 40h, 2d = 16h, 3h = 3h, 30m = 30m → 59h30m total
        assertEquals(40 * 3600L + 16 * 3600L + 3 * 3600L + 30 * 60L, seconds)
        // Re-render returns the canonical string.
        assertEquals("1w 2d 3h 30m", DurationShorthand.renderShorthand(seconds))
    }

    @Test
    fun `logWork inserts a row and recomputes Task time spent`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val log = service.log(
            task.id, callerProfileId,
            WorkLogInput(timeSpent = "2h", startedAt = OffsetDateTime.now(), comment = "x"),
        )
        assertNotNull(log)
        // sumForTask should match.
        assertEquals(2 * 3600L, worklogRepo.sumForTask(task.id))
        // Task row is updated; reload to confirm.
        val reloaded = taskService.getById(task.id)
        assertNotNull(reloaded)
        assertEquals(2 * 3600L, reloaded.timeSpentSeconds)
    }

    @Test
    fun `update changes time spent by the delta`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val first = service.log(
            task.id, callerProfileId,
            WorkLogInput(timeSpent = "1h", startedAt = OffsetDateTime.now()),
        )
        service.update(
            first.id,
            WorkLogInput(timeSpent = "30m", startedAt = first.startedAt),
        )
        assertEquals(30 * 60L, worklogRepo.sumForTask(task.id))
    }

    @Test
    fun `delete soft-deletes the row and rolls back time`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val first = service.log(
            task.id, callerProfileId,
            WorkLogInput(timeSpent = "45m", startedAt = OffsetDateTime.now()),
        )
        assertTrue(service.delete(first.id))
        assertEquals(0L, worklogRepo.sumForTask(task.id))
    }

    @Test
    fun `due notifications are claimed once per due date`() = withDb {
        val project = seedProject()
        taskService.create(
            CreateTaskInput(
                projectId = project.id,
                summary = "overdue task",
                dueDate = OffsetDateTime.now().minusMinutes(1),
            ),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )

        assertEquals(1, taskService.dispatchDueNotifications())
        assertEquals(0, taskService.dispatchDueNotifications())
    }

    @Test
    fun `epic rollup aggregates child estimates and spent totals`() = withDb {
        val project = seedProject()
        val epic = createTask(project.id)
        // Two child tasks linked to the epic via SQL since taskService.create
        // doesn't yet expose epicTaskId on the input shape.
        val childA = createTask(project.id)
        val childB = createTask(project.id)
        connection().useStatement(
            """
            update workops.task set epic_task_id = '${epic.id}' where id in ('${childA.id}','${childB.id}')
            """
        ) { it.execute() }

        service.log(childA.id, callerProfileId,
            WorkLogInput(timeSpent = "1h", startedAt = OffsetDateTime.now()))
        service.log(childB.id, callerProfileId,
            WorkLogInput(timeSpent = "2h 30m", startedAt = OffsetDateTime.now()))

        // Direct rollup invocation; in production this fires from
        // each worklog mutation when the task has an epicTaskId.
        service.rollupEpic(epic.id)
        val agg = taskTimeRepo.aggregateForEpic(epic.id)
        assertNotNull(agg)
        assertEquals(2, agg.childCount)
        assertEquals(3 * 3600L + 30 * 60L, agg.totalSpent)
    }
}
