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
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.okr.ConfidenceLevel
import bosca.workops.model.okr.KeyResultMetric
import bosca.workops.model.okr.Objective
import bosca.workops.model.okr.ObjectiveState
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.CapacityRepositoryImpl
import bosca.workops.repository.KeyResultRepositoryImpl
import bosca.workops.repository.ObjectiveRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.RoadmapScenarioRepositoryImpl
import bosca.workops.repository.RoadmapTaskRepositoryImpl
import bosca.workops.repository.SprintWorkRepositoryImpl
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Phase9IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase9_test")
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
                key = "workops-phase9-test",
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
    private val linkRepo = TaskLinkRepositoryImpl()
    private val scenarioRepo = RoadmapScenarioRepositoryImpl()
    private val roadmapTaskRepo = RoadmapTaskRepositoryImpl()
    private val objectiveRepo = ObjectiveRepositoryImpl()
    private val keyResultRepo = KeyResultRepositoryImpl()
    private val capacityRepo = CapacityRepositoryImpl()
    private val sprintWorkRepo = SprintWorkRepositoryImpl()

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
    private val roadmapService = RoadmapServiceImpl(
        scenarioRepo, taskRepo, roadmapTaskRepo, statusRepo, taskService, json,
    )
    private val dependencyService = TaskDependencyServiceImpl(taskRepo, linkRepo)
    private val objectiveService = ObjectiveServiceImpl(objectiveRepo)
    private val keyResultService = KeyResultServiceImpl(keyResultRepo, KeyResultEvaluator(null), json)
    private val capacityService = CapacityServiceImpl(capacityRepo, sprintWorkRepo)

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
                connection().useStatement("DELETE FROM workops.capacity") { it.execute() }
                connection().useStatement("DELETE FROM workops.key_result") { it.execute() }
                connection().useStatement("DELETE FROM workops.objective") { it.execute() }
                connection().useStatement("DELETE FROM workops.roadmap_scenario") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_link") { it.execute() }
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

    private suspend fun seedProgram(): UUID {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "P9", name = "P9",
                description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "P9P", name = "P9 Program",
                description = null, ownerProfileId = ownerId)
        )
        projectService.create(
            ProjectInput(programId = program.id, key = "P9", name = "P9 Project",
                description = null, ownerProfileId = ownerId)
        )
        return program.id
    }

    @Test
    fun `roadmap compute returns epic-typed tasks for the program`() = withDb {
        val programId = seedProgram()
        val project = projectService.listByProgram(programId, 0L, 50).single()
        val epicType = taskTypeRepo.getAll().first { it.name == "Epic" }
        val epicId = UUID.parse("22222222-aaaa-aaaa-aaaa-222222222222")
        // Insert an epic-typed task directly so we exercise the roadmap
        // query path without depending on TaskService.create's
        // task-type-scheme default selection.
        connection().useStatement(
            """
            insert into workops.task
                (id, project_id, key, summary, task_type_id,
                 status_id, priority_id, reporter_profile_id,
                 created_by_principal_id, modified_by_principal_id)
            values
                ('$epicId', '${project.id}', 'P9-100', 'Q1 Epic', '${epicType.id}',
                 (select id from workops.status where category = 'TODO' limit 1),
                 (select id from workops.priority where name = 'Medium' limit 1),
                 '$callerProfileId', '$callerPrincipalId', '$callerPrincipalId')
            """
        ) { it.execute() }
        val entries = roadmapService.compute(programId, scenarioId = null)
        assertEquals(1, entries.size)
        assertEquals("P9-100", entries.single().key)
    }

    @Test
    fun `scenario overrides apply at compute and do not mutate the task`() = withDb {
        val programId = seedProgram()
        val project = projectService.listByProgram(programId, 0L, 50).single()
        val epicType = taskTypeRepo.getAll().first { it.name == "Epic" }
        val epicId = UUID.parse("33333333-aaaa-aaaa-aaaa-333333333333")
        connection().useStatement(
            """
            insert into workops.task
                (id, project_id, key, summary, task_type_id,
                 status_id, priority_id, reporter_profile_id,
                 created_by_principal_id, modified_by_principal_id)
            values
                ('$epicId', '${project.id}', 'P9-200', 'Original Summary', '${epicType.id}',
                 (select id from workops.status where category = 'TODO' limit 1),
                 (select id from workops.priority where name = 'Medium' limit 1),
                 '$callerProfileId', '$callerPrincipalId', '$callerPrincipalId')
            """
        ) { it.execute() }

        val overlay = buildJsonObject {
            put(epicId.toString(), buildJsonObject {
                put("summary", JsonPrimitive("Re-prioritized"))
            })
        }
        val scenario = roadmapService.createScenario(
            programId = programId,
            name = "what if",
            description = null,
            overrides = overlay,
            createdByProfileId = callerProfileId,
        )
        val entries = roadmapService.compute(programId, scenarioId = scenario.id)
        val scenarioEntry = entries.single { it.key == "P9-200" }
        assertEquals("Re-prioritized", scenarioEntry.summary)
        assertTrue(scenarioEntry.isFromScenario)
        // Without scenario, the original summary still wins.
        assertEquals("Original Summary", roadmapService.compute(programId, null).single { it.key == "P9-200" }.summary)
    }

    @Test
    fun `dependency graph BFS surfaces blocks edges`() = withDb {
        val programId = seedProgram()
        val project = projectService.listByProgram(programId, 0L, 50).single()
        val a = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "A"),
            actingPrincipalId = callerPrincipalId, actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val b = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "B"),
            actingPrincipalId = callerPrincipalId, actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val blocks = linkRepo.listLinkTypes().first { it.category == LinkCategory.BLOCKS }
        linkRepo.add(blocks.id, a.id, b.id, callerPrincipalId)
        val graph = dependencyService.graph(a.id, depth = 2)
        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.edges.size)
        assertEquals("BLOCKS", graph.edges.single().category)
    }

    @Test
    fun `objective and key result CRUD round-trips`() = withDb {
        val programId = seedProgram()
        val obj = objectiveService.create(
            Objective(
                programId = programId,
                title = "Q1 Objective",
                description = null,
                state = ObjectiveState.ACTIVE,
                periodStart = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                periodEnd = OffsetDateTime.parse("2026-03-31T23:59:59Z"),
                periodName = "Q1 2026",
                ownerProfileId = ownerId,
                confidence = ConfidenceLevel.MEDIUM,
            )
        )
        keyResultService.create(
            objectiveId = obj.id,
            title = "Reach 80%",
            description = null,
            metric = KeyResultMetric.Percentage(target = 80),
        )
        keyResultService.create(
            objectiveId = obj.id,
            title = "Done",
            description = null,
            metric = KeyResultMetric.Boolean(achieved = true),
        )
        val krs = keyResultService.list(obj.id)
        assertEquals(2, krs.size)
    }

    @Test
    fun `capacity report sums committed vs aggregated planned`() = withDb {
        val programId = seedProgram()
        val project = projectService.listByProgram(programId, 0L, 50).single()
        // Create a board + sprint we can pin tasks to.
        val boardId = UUID.parse("55555555-5555-5555-5555-555555555555")
        val sprintId = UUID.parse("44444444-4444-4444-4444-444444444444")
        connection().useStatement(
            """
            insert into workops.board (id, project_id, name, type)
            values ('$boardId', '${project.id}', 'B', 'scrum')
            """
        ) { it.execute() }
        connection().useStatement(
            """
            insert into workops.sprint (id, board_id, name, state)
            values ('$sprintId', '$boardId', 'Sprint 1', 'active')
            """
        ) { it.execute() }
        capacityService.setCommitment(sprintId, callerProfileId, committedSeconds = 8 * 3600L, notes = null)
        // Aggregate is empty (no tasks pinned to this sprint with original_estimate).
        val report = capacityService.report(sprintId)
        assertEquals(1, report.size)
        val entry = report.single()
        assertEquals(callerProfileId, entry.profileId)
        assertEquals(8 * 3600L, entry.committedSeconds)
        assertEquals(0L, entry.plannedSeconds)
    }
}
