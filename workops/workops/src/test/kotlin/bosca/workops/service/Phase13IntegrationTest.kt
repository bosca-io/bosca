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
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.ai.AiOptOutException
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import io.mockk.*
import bosca.di.asProvider
import bosca.workops.repository.AiOptInRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.ResolutionRepositoryImpl
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class Phase13IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase13_test")
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
                key = "workops-phase13-test",
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
    private val workflowQueryRepo = WorkflowQueryRepositoryImpl()
    private val affectedRepo = TaskAffectedProjectRepositoryImpl()
    private val aiOptInRepo = AiOptInRepositoryImpl()

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

    private val searchService = mockk<bosca.search.service.SearchService>(relaxed = true)
    private val storageSystemService = mockk<bosca.storage.service.StorageSystemService>(relaxed = true)

    private val optInService = AiOptInServiceImpl(aiOptInRepo)
    private val duplicateService = DuplicateSuggestionServiceImpl(searchService, storageSystemService, optInService)
    private val triageService = TriageSuggestionServiceImpl(taskRepo, projectRepo, optInService)
    private val summarizationService = SummarizationServiceImpl(taskRepo, optInService)
    private val translationService = BqlTranslationServiceImpl(optInService)

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
                connection().useStatement("DELETE FROM workops.task_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.task") { it.execute() }
                connection().useStatement("DELETE FROM workops.project_key_counter") { it.execute() }
                connection().useStatement("DELETE FROM workops.project") { it.execute() }
                connection().useStatement("DELETE FROM workops.program") { it.execute() }
                connection().useStatement("DELETE FROM workops.portfolio") { it.execute() }
                // Reset org-level opt-in to enabled (the default seed).
                connection().useStatement(
                    """
                    update workops.ai_org_settings set enabled = true
                    where id = '00000000-0000-0000-0000-000000000001'
                    """.trimIndent(),
                ) { it.execute() }
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
                        PortfolioInput(key = "P13", name = "P13",
                            description = null, ownerProfileId = ownerId)
                    ).id,
                    key = "P13P", name = "P13 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P13", name = "P13 Project", description = null,
            ownerProfileId = ownerId,
        )
    )

    private suspend fun createTask(projectId: UUID, summary: String) = taskService.create(
        CreateTaskInput(projectId = projectId, summary = summary),
        actingPrincipalId = callerPrincipalId,
        actingProfileId = callerProfileId,
        reporterProfileId = callerProfileId,
    )

    @Test
    fun `org-level opt-out short-circuits every AI service`() = withDb {
        val project = seedProject()
        optInService.setOrgEnabled(false)
        assertFailsWith<AiOptOutException> {
            duplicateService.suggest("anything", project.id, limit = 5)
        }
    }

    @Test
    fun `project opt-out short-circuits even when org is enabled`() = withDb {
        val project = seedProject()
        optInService.setProjectEnabled(project.id, false)
        assertFailsWith<AiOptOutException> {
            duplicateService.suggest("anything", project.id, limit = 5)
        }
    }

    @Test
    fun `task-level override flips opt-in to false even when project is true`() = withDb {
        val project = seedProject()
        val task = createTask(project.id, "x")
        optInService.setTaskEnabled(task.id, false)
        assertFalse(optInService.isEnabled(projectId = project.id, taskId = task.id))
    }

    @Test
    fun `duplicate suggestion ranks identical lexical matches highest`() = withDb {
        val project = seedProject()
        val a = createTask(project.id, "frob the widget")
        val b = createTask(project.id, "completely unrelated")
        val storageSystem = bosca.storage.model.StorageSystem(
            id = UUID.random(), name = "test", description = "", type = bosca.storage.model.StorageSystemType.SEARCH,
            configuration = kotlinx.serialization.json.JsonObject(emptyMap()),
        )
        coEvery { storageSystemService.getAll() } returns listOf(storageSystem)
        coEvery { searchService.searchRaw(any()) } returns bosca.search.model.RawSearchResult(
            hits = listOf(
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(a.id.toString()))
                    put("key", kotlinx.serialization.json.JsonPrimitive(a.key))
                    put("summary", kotlinx.serialization.json.JsonPrimitive("frob the widget"))
                },
                kotlinx.serialization.json.buildJsonObject {
                    put("id", kotlinx.serialization.json.JsonPrimitive(b.id.toString()))
                    put("key", kotlinx.serialization.json.JsonPrimitive(b.key))
                    put("summary", kotlinx.serialization.json.JsonPrimitive("completely unrelated"))
                },
            ),
            facets = emptyList(),
            estimatedHits = 2,
            system = bosca.search.IndexStorageSystem(storageSystem.id, storageSystem.name),
        )
        val results = duplicateService.suggest("frob the widget", project.id, limit = 5)
        assertTrue(results.isNotEmpty())
        assertEquals(a.id, results.first().candidateTaskId)
        assertEquals(1.0, results.first().lexicalScore)
    }

    @Test
    fun `triage suggest raises PendingPhaseImplementation`() = withDb {
        val project = seedProject()
        val task = createTask(project.id, "x")
        assertFailsWith<PendingPhaseImplementationException> {
            triageService.suggest(task.id)
        }
    }

    @Test
    fun `summarize and translate raise PendingPhaseImplementation`() = withDb {
        val project = seedProject()
        val task = createTask(project.id, "x")
        assertFailsWith<PendingPhaseImplementationException> {
            summarizationService.summarizeThread(task.id)
        }
        assertFailsWith<PendingPhaseImplementationException> {
            translationService.translate("show me my open bugs", project.id)
        }
    }
}
