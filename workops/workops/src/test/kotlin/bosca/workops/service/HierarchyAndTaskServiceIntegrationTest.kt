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
import kotlinx.serialization.json.Json
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsConflictException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.UpdateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.ResolutionRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
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
import kotlinx.serialization.builtins.ListSerializer
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests for the Phase 2 service layer against
 * a real PostgreSQL instance via TestContainers. The suite pins:
 *
 *   1. The hierarchy create / archive / unarchive flow, including the
 *      transitive archived-parent read-only rule (R1).
 *   2. The per-project task-key counter (R1, R2): keys are minted
 *      monotonically and never reused after a soft-delete.
 *   3. The optimistic-lock contract on every mutator: a stale
 *      `expectedVersion` surfaces as [OptimisticLockFailedException].
 *   4. The append-only history write contract (R17): every mutating
 *      service call lands exactly one [TaskHistoryEntry], a
 *      multi-field update produces ONE entry with multiple changes.
 */
class HierarchyAndTaskServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase2_test")
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
                key = "workops-phase2-test",
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

    private val securityService = mockk<SecurityService>(relaxed = true)
    private val groupEvaluator = bosca.security.service.GroupEvaluator(securityService)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
    private val portfolioPermissionEvaluator = PortfolioPermissionEvaluator(portfolioService, securityService, groupEvaluator)
    private val programService = ProgramServiceImpl(programRepo, portfolioRepo, programPermissionRepo, portfolioPermissionRepo, portfolioPermissionEvaluator)
    private val programPermissionEvaluator = ProgramPermissionEvaluator(programService, securityService, groupEvaluator)
    private val projectService = ProjectServiceImpl(projectRepo, programRepo, keyCounterRepo, projectPermissionRepo, programPermissionRepo, programPermissionEvaluator)
    private val projectPermissionEvaluator = ProjectPermissionEvaluator(projectService, securityService, groupEvaluator)
    private val taskJson = Json { ignoreUnknownKeys = true }
    private val workflowRepo = WorkflowRepositoryImpl()
    private val workflowSchemeRepo = WorkflowSchemeRepositoryImpl()
    private val workflowQueryRepo = WorkflowQueryRepositoryImpl()
    private val affectedRepo = TaskAffectedProjectRepositoryImpl()
    private val workflowService = WorkflowServiceImpl(workflowRepo, workflowSchemeRepo, projectService, workflowQueryRepo)
    private val taskFieldConfigurationRepo = bosca.workops.repository.TaskFieldConfigurationRepositoryImpl()
    private val customFieldService = TaskCustomFieldServiceImpl(taskFieldConfigurationRepo)
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
        taskPermissionRepository = taskPermissionRepo,
        affectedProjectService = affectedService,
        requirementServiceProvider = mockk<RequirementService>(relaxed = true).asProvider(),
        metadataService = mockk(relaxed = true),
        sprintService = mockk(relaxed = true),
        projectPermissionEvaluator = projectPermissionEvaluator,
        json = taskJson,
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { Json { ignoreUnknownKeys = true } }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }

        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration(), WorkOpsMigration()))
            }
            schemaInitialized = true
        }

        withDb {
            transaction {
                // Order matters: child rows first, FK chain restricts deletion.
                connection().useStatement("DELETE FROM workops.task_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.task") { it.execute() }
                connection().useStatement("DELETE FROM workops.project_key_counter") { it.execute() }
                connection().useStatement("DELETE FROM workops.project") { it.execute() }
                connection().useStatement("DELETE FROM workops.program") { it.execute() }
                connection().useStatement("DELETE FROM workops.portfolio") { it.execute() }
            }
        }
    }

    private fun withDb(block: suspend () -> Unit) {
        runBlocking {
            val manager = pool.connection()
            try {
                withContext(manager.asCoroutineContext()) { block() }
            } finally {
                withContext(NonCancellable) { manager.release() }
            }
        }
    }

    // ----- fixture helpers -----

    private val ownerProfileId: UUID = UUID.parse("11111111-1111-1111-1111-111111111111")
    private val callerPrincipalId: UUID = UUID.parse("22222222-2222-2222-2222-222222222222")
    private val callerProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")

    private suspend fun seedHierarchy(
        portfolioKey: String = "MARKETING",
        programKey: String = "WEB",
        projectKey: String = "BLOG",
    ): Triple<UUID, UUID, UUID> {
        val portfolio = portfolioService.create(
            PortfolioInput(key = portfolioKey, name = "Marketing", description = null, ownerProfileId = ownerProfileId)
        )
        val program = programService.create(
            ProgramInput(
                portfolioId = portfolio.id,
                key = programKey,
                name = "Web",
                description = null,
                ownerProfileId = ownerProfileId,
            )
        )
        val project = projectService.create(
            ProjectInput(
                programId = program.id,
                key = projectKey,
                name = "Blog",
                description = null,
                ownerProfileId = ownerProfileId,
                defaultTaskTypeSchemeId = null,
            )
        )
        return Triple(portfolio.id, program.id, project.id)
    }

    // ----- hierarchy tests -----

    @Test
    fun `create portfolio program and project`() = withDb {
        val (portfolioId, programId, projectId) = seedHierarchy()

        val portfolio = portfolioRepo.getById(portfolioId)
        assertNotNull(portfolio)
        assertEquals("MARKETING", portfolio.key)
        assertEquals(0, portfolio.version)

        val program = programRepo.getById(programId)
        assertNotNull(program)
        assertEquals(portfolioId, program.portfolioId)

        val project = projectRepo.getById(projectId)
        assertNotNull(project)
        assertEquals(programId, project.programId)
        assertNotNull(project.defaultTaskTypeSchemeId, "Phase 2 wires the seeded scheme by default")

        val counter = keyCounterRepo.lastUsed(projectId)
        assertEquals(0L, counter, "fresh project counter starts at 0")
    }

    @Test
    fun `portfolio key validation rejects lowercase or short keys`() = withDb {
        assertFailsWith<WorkOpsValidationException> {
            portfolioService.create(PortfolioInput(key = "x", name = "Bad", description = null, ownerProfileId = ownerProfileId))
        }
        assertFailsWith<WorkOpsValidationException> {
            portfolioService.create(PortfolioInput(key = "lowcase", name = "Bad", description = null, ownerProfileId = ownerProfileId))
        }
    }

    @Test
    fun `duplicate portfolio key conflict`() = withDb {
        portfolioService.create(PortfolioInput(key = "DUPE", name = "First", description = null, ownerProfileId = ownerProfileId))
        assertFailsWith<WorkOpsConflictException> {
            portfolioService.create(PortfolioInput(key = "DUPE", name = "Second", description = null, ownerProfileId = ownerProfileId))
        }
    }

    @Test
    fun `archived portfolio rejects program creation`() = withDb {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "ARCH", name = "Archived", description = null, ownerProfileId = ownerProfileId)
        )
        portfolioService.archive(portfolio.id, expectedVersion = 0)
        assertFailsWith<WorkOpsArchivedException> {
            programService.create(
                ProgramInput(
                    portfolioId = portfolio.id,
                    key = "P1",
                    name = "Should fail",
                    description = null,
                    ownerProfileId = ownerProfileId,
                )
            )
        }
    }

    @Test
    fun `archive then unarchive returns to writable`() = withDb {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "TOGGLE", name = "Toggle", description = null, ownerProfileId = ownerProfileId)
        )
        val archived = portfolioService.archive(portfolio.id, expectedVersion = 0)
        assertNotNull(archived.archivedAt)
        val unarchived = portfolioService.unarchive(archived.id, expectedVersion = archived.version)
        assertNull(unarchived.archivedAt)
    }

    // ----- task lifecycle and key sequence -----

    @Test
    fun `task keys are minted monotonically per project`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val first = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "First"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val second = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "Second"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val third = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "Third"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )

        assertEquals("BLOG-1", first.key)
        assertEquals("BLOG-2", second.key)
        assertEquals("BLOG-3", third.key)
    }

    @Test
    fun `soft-deleted task does not free its key - number is never reused`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val first = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "First"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        taskService.softDelete(first.id, expectedVersion = first.version, actingPrincipalId = callerPrincipalId, actingProfileId = callerProfileId)

        val second = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "After delete"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        assertEquals("BLOG-2", second.key, "task numbers must never be reused even after soft-delete")
    }

    @Test
    fun `update applies optimistic lock - stale version is rejected`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "First draft"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        // First update succeeds and bumps version to 1.
        taskService.update(
            id = task.id,
            input = UpdateTaskInput(summary = "Second draft", expectedVersion = task.version),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        // Second update with the SAME (now stale) version 0 must fail.
        assertFailsWith<OptimisticLockFailedException> {
            taskService.update(
                id = task.id,
                input = UpdateTaskInput(summary = "Stale write", expectedVersion = 0),
                actingPrincipalId = callerPrincipalId,
                actingProfileId = callerProfileId,
            )
        }
    }

    @Test
    fun `multi-field update produces one history entry with multiple field changes`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "Triage"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )

        // Pick a different priority + assignee to force two field changes.
        val newAssignee = UUID.parse("44444444-4444-4444-4444-444444444444")
        val priorities = priorityRepo.getAll()
        val newPriority = priorities.first { it.name == "High" }

        taskService.update(
            id = task.id,
            input = UpdateTaskInput(
                summary = "Triage — refined",
                assigneeProfileId = newAssignee,
                priorityId = newPriority.id,
                expectedVersion = task.version,
            ),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )

        val history = taskService.listHistory(task.id, offset = 0, limit = 50)
        // The history is newest-first: entry[0] is the update, entry[1] is the create.
        assertEquals(2, history.size)
        val updateEntry = history.first()
        val keys = updateEntry.decodedChanges().map { it.fieldKey }.toSet()
        assertTrue(
            keys.containsAll(setOf("summary", "assignee_profile_id", "priority_id")),
            "expected summary/assignee/priority field changes; got $keys",
        )
    }

    @Test
    fun `create task writes one creation history entry`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "Audit me"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )

        val history = taskService.listHistory(task.id, offset = 0, limit = 10)
        assertEquals(1, history.size, "create writes exactly one history entry")
        assertEquals("created", history.first().decodedChanges().single().fieldKey)
    }

    private fun TaskHistoryEntry.decodedChanges(): List<FieldChange> =
        taskJson.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), changes)


    @Test
    fun `soft-delete then restore round-trips and writes audit`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "Cleanup"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val deleted = taskService.softDelete(task.id, task.version, callerPrincipalId, callerProfileId)
        assertNotNull(deleted.deletedAt)

        // getById from the SERVICE filters soft-deleted; raw repo still sees the row.
        assertNull(taskService.getById(task.id))
        assertNotNull(taskRepo.getById(task.id))

        val restored = taskService.restore(deleted.id, deleted.version, callerPrincipalId, callerProfileId)
        assertNull(restored.deletedAt)
        assertEquals(task.id, restored.id)

        val history = taskService.listHistory(task.id, offset = 0, limit = 10)
        // create + soft-delete + restore = 3 entries
        assertEquals(3, history.size)
    }

    @Test
    fun `summary blank or oversized is rejected`() = withDb {
        val (_, _, projectId) = seedHierarchy()

        assertFailsWith<WorkOpsValidationException> {
            taskService.create(
                CreateTaskInput(projectId = projectId, summary = "   "),
                actingPrincipalId = callerPrincipalId,
                actingProfileId = callerProfileId,
                reporterProfileId = callerProfileId,
            )
        }
        assertFailsWith<WorkOpsValidationException> {
            taskService.create(
                CreateTaskInput(projectId = projectId, summary = "x".repeat(256)),
                actingPrincipalId = callerPrincipalId,
                actingProfileId = callerProfileId,
                reporterProfileId = callerProfileId,
            )
        }
    }
}
