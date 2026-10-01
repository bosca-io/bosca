@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.comments.model.CommentStatus
import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.ProfileVisibility
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.comment.TaskCommentInput
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
import bosca.workops.repository.TaskCommentRepositoryImpl
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 integration tests covering R5 (custom fields) and R6
 * (task comments). The custom-field path validates against the
 * project's TaskFieldConfigurationScheme — required-but-missing
 * raises, hidden fields filter on read. The comment path mirrors
 * the metadata-comment behavior (visibility / replies / soft-delete /
 * likes) but keys on task_id instead of (metadata_id, version).
 *
 * Every comment mutation writes a TaskHistoryEntry in the same
 * transaction (R6 + Excellence Bar audit completeness), so the
 * suite asserts the history side-effect alongside the read shape.
 */
class Phase4IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase4_test")
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
                key = "workops-phase4-test",
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
    private val taskCommentRepo = TaskCommentRepositoryImpl()
    private val fieldConfigRepo = TaskFieldConfigurationRepositoryImpl()
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
    private val taskCommentService = TaskCommentServiceImpl(
        taskCommentRepo,
        taskService,
        historyRepo,
        projectService,
        programService,
        mockk(relaxed = true),
        mockk(relaxed = true),
        json,
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
                // Order matters — child rows first.
                connection().useStatement("DELETE FROM workops.task_comment_likes") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_comment") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_link") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.task") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_field_configuration") { it.execute() }
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

    private val defaultFieldConfigSchemeId: UUID = UUID.parse("80000000-0000-0000-0000-000000000001")

    private suspend fun seedProject(): UUID {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "PHF", name = "Phase4", description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "PHFP", name = "Phase4 Program",
                description = null, ownerProfileId = ownerId)
        )
        val project = projectService.create(
            ProjectInput(programId = program.id, key = "PHF", name = "Phase4 Project",
                description = null, ownerProfileId = ownerId)
        )
        return project.id
    }

    private suspend fun createTask(
        projectId: UUID,
        summary: String = "test",
        customFields: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ) = taskService.create(
        CreateTaskInput(projectId = projectId, summary = summary, customFields = customFields),
        actingPrincipalId = callerPrincipalId,
        actingProfileId = callerProfileId,
        reporterProfileId = callerProfileId,
    )

    private fun TaskHistoryEntry.decodedChanges(): List<FieldChange> =
        json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), changes)

    // ----- Custom fields (R5) -----

    @Test
    fun `createTask without required custom field is rejected`() = withDb {
        val projectId = seedProject()
        val taskTypeId = UUID.parse("00000000-0000-0000-0000-000000000003") // Task seeded type
        // Add a required field configuration to the seeded scheme.
        fieldConfigRepo.addConfiguration(
            schemeId = defaultFieldConfigSchemeId,
            taskTypeId = null, // applies to every task type
            fieldKey = "browser",
            required = true,
            hidden = false,
            defaultValueExpression = null,
            helpText = "Reporter's browser",
        )

        val ex = assertFailsWith<WorkOpsValidationException> {
            createTask(projectId, summary = "missing required field")
        }
        assertTrue(ex.field.contains("browser"))
    }

    @Test
    fun `createTask with required custom field provided succeeds and stores the value`() = withDb {
        val projectId = seedProject()
        fieldConfigRepo.addConfiguration(
            schemeId = defaultFieldConfigSchemeId,
            taskTypeId = null,
            fieldKey = "browser",
            required = true,
            hidden = false,
            defaultValueExpression = null,
            helpText = null,
        )

        val task = createTask(
            projectId = projectId,
            summary = "filled",
            customFields = mapOf("browser" to JsonPrimitive("Firefox")),
        )
        val raw = task.customFieldValues
        assertEquals(JsonPrimitive("Firefox"), raw["browser"])
    }

    @Test
    fun `default value substitutes when caller omits the key`() = withDb {
        val projectId = seedProject()
        fieldConfigRepo.addConfiguration(
            schemeId = defaultFieldConfigSchemeId,
            taskTypeId = null,
            fieldKey = "severity",
            required = true,
            hidden = false,
            defaultValueExpression = JsonPrimitive("medium"),
            helpText = null,
        )
        val task = createTask(projectId, summary = "default-applies")
        assertEquals(JsonPrimitive("medium"), task.customFieldValues["severity"])
    }

    @Test
    fun `hidden fields filter on read but stay in raw`() = withDb {
        val projectId = seedProject()
        fieldConfigRepo.addConfiguration(
            schemeId = defaultFieldConfigSchemeId,
            taskTypeId = null,
            fieldKey = "internal_notes",
            required = false,
            hidden = true,
            defaultValueExpression = null,
            helpText = null,
        )
        val task = createTask(
            projectId,
            summary = "hidden",
            customFields = mapOf("internal_notes" to JsonPrimitive("don't show this")),
        )
        val project = projectService.getById(projectId)!!
        // Raw map keeps the value (manager-mode read).
        assertEquals(JsonPrimitive("don't show this"), task.customFieldValues["internal_notes"])
        // Public read filters it out.
        val publicView = customFieldService.filterForRead(project, task, manager = false)
        assertNull(publicView["internal_notes"])
        // Manager read still sees it.
        val managerView = customFieldService.filterForRead(project, task, manager = true)
        assertEquals(JsonPrimitive("don't show this"), managerView["internal_notes"])
    }

    @Test
    fun `set custom fields validates required values`() = withDb {
        val projectId = seedProject()
        fieldConfigRepo.addConfiguration(
            schemeId = defaultFieldConfigSchemeId,
            taskTypeId = null,
            fieldKey = "browser",
            required = true,
            hidden = false,
            defaultValueExpression = null,
            helpText = null,
        )
        val task = createTask(
            projectId = projectId,
            customFields = mapOf("browser" to JsonPrimitive("Firefox")),
        )

        assertFailsWith<WorkOpsValidationException> {
            taskService.setCustomFieldValues(
                id = task.id,
                customFieldValues = JsonObject(emptyMap()),
                expectedVersion = task.version,
                actingPrincipalId = callerPrincipalId,
                actingProfileId = callerProfileId,
            )
        }

        val unchanged = taskService.getById(task.id)!!
        assertEquals(task.version, unchanged.version)
        assertEquals(JsonPrimitive("Firefox"), unchanged.customFieldValues["browser"])
    }

    @Test
    fun `set custom fields records field keys and identical replacement is a no-op`() = withDb {
        val projectId = seedProject()
        val task = createTask(
            projectId = projectId,
            customFields = mapOf("risk" to JsonPrimitive("low")),
        )
        val replacement = JsonObject(mapOf("risk" to JsonPrimitive("high")))

        val updated = taskService.setCustomFieldValues(
            id = task.id,
            customFieldValues = replacement,
            expectedVersion = task.version,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        assertEquals(task.version + 1, updated.version)
        val historyAfterUpdate = taskService.listHistory(task.id, 0, 50)
        assertEquals(listOf("risk"), historyAfterUpdate.first().decodedChanges().map { it.fieldKey })

        val unchanged = taskService.setCustomFieldValues(
            id = task.id,
            customFieldValues = replacement,
            expectedVersion = updated.version,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        assertEquals(updated.version, unchanged.version)
        assertEquals(historyAfterUpdate.size, taskService.listHistory(task.id, 0, 50).size)
    }

    @Test
    fun `identical core update preserves version and history but still checks expected version`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId, summary = "unchanged")
        val historySize = taskService.listHistory(task.id, 0, 50).size

        val unchanged = taskService.update(
            task.id,
            UpdateTaskInput(summary = task.summary, expectedVersion = task.version),
            callerPrincipalId,
            callerProfileId,
        )

        assertEquals(task.version, unchanged.version)
        assertEquals(historySize, taskService.listHistory(task.id, 0, 50).size)
        assertFailsWith<OptimisticLockFailedException> {
            taskService.update(
                task.id,
                UpdateTaskInput(summary = task.summary, expectedVersion = task.version - 1),
                callerPrincipalId,
                callerProfileId,
            )
        }
    }

    // ----- Comments (R6) -----

    @Test
    fun `add comment writes a history entry and is fetchable`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId, summary = "with-comment")
        val comment = taskCommentService.add(
            taskId = task.id,
            input = TaskCommentInput(
                visibility = ProfileVisibility.PUBLIC,
                content = "first thoughts",
            ),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        assertTrue(comment.id > 0)

        // History grew by one (besides the create entry).
        val history = taskService.listHistory(task.id, 0, 50)
        assertEquals(2, history.size, "create + one comment-add entry")
        val keys = history.first().decodedChanges().map { it.fieldKey }
        assertTrue("comment" in keys, "comment-add history must carry fieldKey 'comment'")
    }

    @Test
    fun `reply requires the parent to be on the same task`() = withDb {
        val projectId = seedProject()
        val taskA = createTask(projectId, summary = "A")
        val taskB = createTask(projectId, summary = "B")
        val a1 = taskCommentService.add(
            taskA.id,
            TaskCommentInput(content = "on A", visibility = ProfileVisibility.PUBLIC),
            callerPrincipalId, callerProfileId,
        )
        // Reply against a different task — service rejects.
        assertFailsWith<WorkOpsValidationException> {
            taskCommentService.add(
                taskB.id,
                TaskCommentInput(parentId = a1.id, content = "wrong", visibility = ProfileVisibility.PUBLIC),
                callerPrincipalId, callerProfileId,
            )
        }
    }

    @Test
    fun `like increments and unlike decrements with idempotency`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val comment = taskCommentService.add(
            task.id,
            TaskCommentInput(content = "like me", visibility = ProfileVisibility.PUBLIC),
            callerPrincipalId, callerProfileId,
        )
        // Approve the comment so visibility filtering doesn't hide it.
        taskCommentService.setStatus(task.id, comment.id, CommentStatus.APPROVED, callerPrincipalId, callerProfileId)

        val afterLike = taskCommentService.like(task.id, comment.id, otherProfileId)
        assertEquals(1, afterLike)

        val seenByOther = taskCommentService.get(task.id, comment.id, otherProfileId, manager = false)
        assertNotNull(seenByOther)
        assertEquals(1, seenByOther.likes)

        val afterUnlike = taskCommentService.unlike(task.id, comment.id, otherProfileId)
        assertEquals(0, afterUnlike)
    }

    @Test
    fun `visibility filtering hides pending comments from anonymous viewers`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val pending = taskCommentService.add(
            task.id,
            TaskCommentInput(content = "pending", visibility = ProfileVisibility.PUBLIC),
            callerPrincipalId, callerProfileId,
        )

        assertNull(
            taskCommentService.get(task.id, pending.id, viewingProfileId = null, manager = false),
            "anonymous viewers must not see pending comments",
        )
        // The author sees their own pending comment.
        assertNotNull(
            taskCommentService.get(task.id, pending.id, viewingProfileId = callerProfileId, manager = false),
        )
        // After approval, public view unblocked.
        taskCommentService.setStatus(task.id, pending.id, CommentStatus.APPROVED, callerPrincipalId, callerProfileId)
        assertNotNull(taskCommentService.get(task.id, pending.id, viewingProfileId = null, manager = false))
    }

    @Test
    fun `soft delete writes a history entry and hides the comment`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val comment = taskCommentService.add(
            task.id,
            TaskCommentInput(content = "delete me", visibility = ProfileVisibility.PUBLIC),
            callerPrincipalId, callerProfileId,
        )
        taskCommentService.setStatus(task.id, comment.id, CommentStatus.APPROVED, callerPrincipalId, callerProfileId)
        val historyBefore = taskService.listHistory(task.id, 0, 50).size

        taskCommentService.delete(task.id, comment.id, callerPrincipalId, callerProfileId)
        assertNull(
            taskCommentService.get(task.id, comment.id, viewingProfileId = null, manager = false),
        )
        // Even managers see nothing — soft-delete short-circuits the read.
        assertNull(
            taskCommentService.get(task.id, comment.id, viewingProfileId = null, manager = true),
        )
        val historyAfter = taskService.listHistory(task.id, 0, 50)
        assertEquals(historyBefore + 1, historyAfter.size, "delete writes one history entry")
    }
}
