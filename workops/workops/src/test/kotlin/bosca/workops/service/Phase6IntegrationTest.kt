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
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.bql.BqlParseException
import bosca.workops.model.bql.SavedFilterInput
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.ResolutionRepositoryImpl
import bosca.workops.repository.SavedFilterRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
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
import org.junit.AfterClass
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of Phase 6 (BQL search + saved filters).
 * Pins the search planner against real Postgres rows, verifies the
 * saved-filter parse-on-write contract, and confirms BQL parser
 * errors come back as typed [bosca.workops.model.bql.BqlError]
 * lists with byte-offset windows.
 */
class Phase6IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase6_test")
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
                key = "workops-phase6-test",
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
    private val resolutionRepo = ResolutionRepositoryImpl()
        private val taskRepo = TaskRepositoryImpl()
    private val taskPermissionRepo = TaskPermissionRepositoryImpl()
    private val historyRepo = TaskHistoryRepositoryImpl()
    private val workflowRepo = WorkflowRepositoryImpl()
    private val schemeRepo = WorkflowSchemeRepositoryImpl()
    private val fieldConfigRepo = TaskFieldConfigurationRepositoryImpl()
    private val savedFilterRepo = SavedFilterRepositoryImpl()
    private val workflowQueryRepo = WorkflowQueryRepositoryImpl()
    private val affectedRepo = TaskAffectedProjectRepositoryImpl()

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
    private val taskQueryService = TaskQueryServiceImpl(
        priorityRepository = priorityRepo,
        statusRepository = statusRepo,
        taskTypeRepository = taskTypeRepo,
        resolutionRepository = resolutionRepo,
        projectRepository = projectRepo,
    )
    private val savedFilterService = SavedFilterServiceImpl(savedFilterRepo, json)

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
                connection().useStatement("DELETE FROM workops.saved_filter") { it.execute() }
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
    private val callerPrincipalId: UUID = UUID.parse("22222222-2222-2222-2222-222222222222")
    private val callerProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")
    private val otherProfileId: UUID = UUID.parse("44444444-4444-4444-4444-444444444444")

    private suspend fun seedProject(): UUID {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "BQL", name = "BQL", description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "BQLP", name = "BQL Program",
                description = null, ownerProfileId = ownerId)
        )
        val project = projectService.create(
            ProjectInput(programId = program.id, key = "BQL", name = "BQL Project",
                description = null, ownerProfileId = ownerId)
        )
        return project.id
    }

    private suspend fun createTask(
        projectId: UUID,
        summary: String,
        assigneeProfileId: UUID? = null,
    ) = taskService.create(
        CreateTaskInput(
            projectId = projectId,
            summary = summary,
            assigneeProfileId = assigneeProfileId,
        ),
        actingPrincipalId = callerPrincipalId,
        actingProfileId = callerProfileId,
        reporterProfileId = callerProfileId,
    )

    @Test
    fun `searchTasks returns all tasks when BQL is blank`() = withDb {
        val projectId = seedProject()
        createTask(projectId, "alpha")
        createTask(projectId, "beta")
        val result = taskQueryService.search("", actingProfileId = null)
        assertTrue(result.rows.size >= 2)
        assertTrue(result.freeTextTerms.isEmpty())
    }

    @Test
    fun `validate returns no errors for blank BQL`() = withDb {
        val errs = taskQueryService.validate("")
        assertTrue(errs.isEmpty())
    }

    @Test
    fun `searchTasks returns tasks matching a name-reference filter`() = withDb {
        val projectId = seedProject()
        val a = createTask(projectId, "first")
        val b = createTask(projectId, "second")
        val result = taskQueryService.search(
            "project = BQL",
            actingProfileId = callerProfileId,
            offset = 0,
            limit = 50,
        )
        val ids = result.rows.map { it.id }.toSet()
        assertTrue(a.id in ids)
        assertTrue(b.id in ids)
        assertEquals(2, result.rows.size)
    }

    @Test
    fun `searchTasks resolves currentUser function`() = withDb {
        val projectId = seedProject()
        val mine = createTask(projectId, "mine", assigneeProfileId = callerProfileId)
        val theirs = createTask(projectId, "theirs", assigneeProfileId = otherProfileId)
        val result = taskQueryService.search(
            "assignee = currentUser()",
            actingProfileId = callerProfileId,
            offset = 0,
            limit = 50,
        )
        assertEquals(1, result.rows.size)
        assertEquals(mine.id, result.rows.single().id)
    }

    @Test
    fun `searchTasks ~ on summary peels free-text and matches via ILIKE`() = withDb {
        val projectId = seedProject()
        createTask(projectId, "frob the widget")
        createTask(projectId, "unrelated thing")
        val result = taskQueryService.search(
            "summary ~ \"frob\"",
            actingProfileId = callerProfileId,
            offset = 0,
            limit = 50,
        )
        assertEquals(1, result.rows.size)
        assertEquals(listOf("frob"), result.freeTextTerms)
    }

    @Test
    fun `searchTasks rejects an invalid query with typed parser errors`() = withDb {
        val projectId = seedProject()
        createTask(projectId, "x")
        val ex = assertFailsWith<BqlParseException> {
            taskQueryService.search("status =", actingProfileId = null)
        }
        assertTrue(ex.errors.isNotEmpty())
        // The error window should point at the EOF position, not 0.
        assertTrue(ex.errors.first().start > 0)
    }

    @Test
    fun `validate returns errors without running SQL`() = withDb {
        val errs = taskQueryService.validate("statoos = Done")
        assertEquals(1, errs.size)
        assertTrue(errs.first().message.contains("unknown field"))
    }

    @Test
    fun `savedFilter create round-trips and rejects duplicate names per owner`() = withDb {
        val first = savedFilterService.create(
            ownerProfileId = callerProfileId,
            input = SavedFilterInput(name = "Mine open", description = null,
                bqlSource = "assignee = currentUser() AND status != Done"),
        )
        assertNotNull(savedFilterService.getById(first.id))
        assertFailsWith<WorkOpsConflictException> {
            savedFilterService.create(
                ownerProfileId = callerProfileId,
                input = SavedFilterInput(name = "Mine open", description = null,
                    bqlSource = "status = Done"),
            )
        }
    }

    @Test
    fun `savedFilter rejects malformed BQL on create`() = withDb {
        assertFailsWith<BqlParseException> {
            savedFilterService.create(
                ownerProfileId = callerProfileId,
                input = SavedFilterInput(name = "Broken", description = null,
                    bqlSource = "= junk"),
            )
        }
    }
}
