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
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.CrossProjectTaskRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskKeyAliasRepositoryImpl
import bosca.workops.repository.TaskMoveAuditRepositoryImpl
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for `CrossProjectMoveService.moveTaskToProject` error paths and
 * audit-trail correctness. Complements the happy-path coverage in
 * `Phase16IntegrationTest`.
 */
class CrossProjectMoveErrorPathTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_move_error_test")
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
                key = "workops-move-error-test",
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
    private val moveRepo = CrossProjectTaskRepositoryImpl()
    private val aliasRepo = TaskKeyAliasRepositoryImpl()
    private val moveAuditRepo = TaskMoveAuditRepositoryImpl()

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
    private val moveService = CrossProjectMoveServiceImpl(
        taskRepository = taskRepo,
        crossProjectRepo = moveRepo,
        keyCounterRepository = keyCounterRepo,
        taskKeyAliasRepository = aliasRepo,
        auditRepository = moveAuditRepo,
        projectRepository = projectRepo,
        statusRepository = statusRepo,
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
                connection().useStatement("DELETE FROM workops.task_move_audit") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_affected_project") { it.execute() }
                connection().useStatement("DELETE FROM workops.release_component_version") { it.execute() }
                connection().useStatement("DELETE FROM workops.release") { it.execute() }
                connection().useStatement("DELETE FROM workops.version") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_key_alias") { it.execute() }
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
            PortfolioInput(key = "MVE", name = "MoveErr",
                description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "MVEP", name = "MoveErr Program",
                description = null, ownerProfileId = ownerId)
        )
        return program.id
    }

    // ----------------------------------------------------------------
    // 1. Moving a task to its own project is rejected
    // ----------------------------------------------------------------

    @Test
    fun `moveTaskToProject rejects move to the same project`() = withDb {
        val programId = seedProgram()
        val project = projectService.create(
            ProjectInput(programId = programId, key = "SAME", name = "Same",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "stay put"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }
        val ex = assertFailsWith<WorkOpsValidationException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(
                    targetProjectId = project.id,
                    statusMapping = mapOf(task.statusId to toDoStatus.id),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
        assertEquals("targetProjectId", ex.field)
        assertTrue(ex.reason.contains("already in the target project"))
    }

    // ----------------------------------------------------------------
    // 2. Moving with a missing status mapping throws an error
    // ----------------------------------------------------------------

    @Test
    fun `moveTaskToProject rejects empty statusMapping`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCA", name = "SrcA",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTA", name = "TgtA",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "no mapping"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val ex = assertFailsWith<WorkOpsValidationException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(targetProjectId = target.id, statusMapping = emptyMap()),
                actingPrincipalId = callerPrincipalId,
            )
        }
        assertEquals("statusMapping", ex.field)
        assertTrue(ex.reason.contains("no mapping provided for source status"))
    }

    @Test
    fun `moveTaskToProject rejects statusMapping that omits the current status`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCB", name = "SrcB",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTB", name = "TgtB",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "wrong mapping"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        // Provide a mapping keyed by a bogus UUID that does not match the task's current status.
        val bogusSourceStatusId = UUID.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }
        val ex = assertFailsWith<WorkOpsValidationException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(
                    targetProjectId = target.id,
                    statusMapping = mapOf(bogusSourceStatusId to toDoStatus.id),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
        assertEquals("statusMapping", ex.field)
        assertTrue(ex.reason.contains(task.statusId.toString()), "error should name the unmapped source status")
    }

    @Test
    fun `moveTaskToProject rejects when target status does not exist`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCC", name = "SrcC",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTC", name = "TgtC",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "bad target status"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val nonExistentStatusId = UUID.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        assertFailsWith<WorkOpsNotFoundException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(
                    targetProjectId = target.id,
                    statusMapping = mapOf(task.statusId to nonExistentStatusId),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
    }

    @Test
    fun `moveTaskToProject rejects non-existent task`() = withDb {
        val programId = seedProgram()
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTD", name = "TgtD",
                description = null, ownerProfileId = ownerId)
        )
        val nonExistentTaskId = UUID.parse("deadbeef-dead-beef-dead-beefdeadbeef")
        val ex = assertFailsWith<WorkOpsNotFoundException> {
            moveService.moveTaskToProject(
                nonExistentTaskId,
                MoveWorkOpsTaskInput(
                    targetProjectId = target.id,
                    statusMapping = emptyMap(),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
        assertEquals("Task", ex.type)
    }

    @Test
    fun `moveTaskToProject rejects non-existent target project`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCE", name = "SrcE",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "bad target"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }
        val nonExistentProjectId = UUID.parse("baadf00d-baad-f00d-baad-f00dbaadf00d")
        val ex = assertFailsWith<WorkOpsNotFoundException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(
                    targetProjectId = nonExistentProjectId,
                    statusMapping = mapOf(task.statusId to toDoStatus.id),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
        assertEquals("Project", ex.type)
    }

    @Test
    fun `moveTaskToProject rejects non-empty fieldMapping`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCF", name = "SrcF",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTF", name = "TgtF",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "field mapping"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }
        assertFailsWith<UnsupportedOperationException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(
                    targetProjectId = target.id,
                    statusMapping = mapOf(task.statusId to toDoStatus.id),
                    fieldMapping = mapOf("custom_1" to "custom_2"),
                ),
                actingPrincipalId = callerPrincipalId,
            )
        }
    }

    // ----------------------------------------------------------------
    // 3. After a successful move, the old key alias resolves to the
    //    new task (same task ID, new key)
    // ----------------------------------------------------------------

    @Test
    fun `old key alias resolves to the moved task after successful move`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCG", name = "SrcG",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTG", name = "TgtG",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "alias check"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val originalKey = task.key
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }

        val moved = moveService.moveTaskToProject(
            task.id,
            MoveWorkOpsTaskInput(
                targetProjectId = target.id,
                statusMapping = mapOf(task.statusId to toDoStatus.id),
            ),
            actingPrincipalId = callerPrincipalId,
        )

        // The task ID is preserved; only the key and project change.
        assertEquals(task.id, moved.id)
        assertTrue(moved.key.startsWith("TGTG-"), "new key should use target project prefix")
        assertTrue(moved.key != originalKey, "key must change after a cross-project move")
        assertEquals(target.id, moved.projectId)

        // The legacy key alias resolves back to the same task ID.
        val resolvedId = aliasRepo.resolve(originalKey)
        assertNotNull(resolvedId, "alias for the legacy key should exist")
        assertEquals(task.id, resolvedId)

        // The new key does NOT have an alias (it is the canonical key).
        val newKeyAlias = aliasRepo.resolve(moved.key)
        assertEquals(null, newKeyAlias, "canonical key should not have an alias entry")
    }

    // ----------------------------------------------------------------
    // 4. The audit trail is created with correct source/target project
    //    info after a successful move
    // ----------------------------------------------------------------

    @Test
    fun `audit trail records correct source and target project info`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "SRCH", name = "SrcH",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "TGTH", name = "TgtH",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "audit check"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val originalKey = task.key
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }

        val moved = moveService.moveTaskToProject(
            task.id,
            MoveWorkOpsTaskInput(
                targetProjectId = target.id,
                statusMapping = mapOf(task.statusId to toDoStatus.id),
            ),
            actingPrincipalId = callerPrincipalId,
        )

        // Query the audit row directly to verify all fields.
        transaction {
            connection().useStatement(
                """
                select task_id, from_project_id, to_project_id,
                       legacy_key, new_key, moved_by_principal_id,
                       status_mapping, field_mapping
                from workops.task_move_audit
                where task_id = '${task.id}'
                """
            ) { stmt ->
                val rs = stmt.executeQuery()
                assertTrue(rs.next(), "audit row should exist for the moved task")

                assertEquals(task.id.toString(), rs.getString("task_id"))
                assertEquals(source.id.toString(), rs.getString("from_project_id"))
                assertEquals(target.id.toString(), rs.getString("to_project_id"))
                assertEquals(originalKey, rs.getString("legacy_key"))
                assertEquals(moved.key, rs.getString("new_key"))
                assertEquals(callerPrincipalId.toString(), rs.getString("moved_by_principal_id"))

                // status_mapping should contain the source->target mapping as JSON.
                val statusMappingRaw = rs.getString("status_mapping")
                assertNotNull(statusMappingRaw)
                assertTrue(
                    statusMappingRaw.contains(task.statusId.toString()),
                    "status_mapping JSON should contain the source status ID",
                )
                assertTrue(
                    statusMappingRaw.contains(toDoStatus.id.toString()),
                    "status_mapping JSON should contain the target status ID",
                )

                // field_mapping should be an empty JSON object since we passed no field mappings.
                val fieldMappingRaw = rs.getString("field_mapping")
                assertNotNull(fieldMappingRaw)
                assertEquals("{}", fieldMappingRaw)

                // There should be exactly one audit row for this task.
                assertTrue(!rs.next(), "only one audit row expected per move")
            }
        }
    }
}
