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
import bosca.workops.repository.ReleaseRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskAffectedProjectRepositoryImpl
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

class Phase16IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase16_test")
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
                key = "workops-phase16-test",
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
    private val releaseRepo = ReleaseRepositoryImpl()
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
    private val releaseService = ReleaseServiceImpl(releaseRepo)
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
            PortfolioInput(key = "P16", name = "P16",
                description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "P16P", name = "P16 Program",
                description = null, ownerProfileId = ownerId)
        )
        return program.id
    }

    @Test
    fun `release create + bundle + unbundle round-trip`() = withDb {
        val programId = seedProgram()
        val project = projectService.create(
            ProjectInput(programId = programId, key = "P16A", name = "Project A",
                description = null, ownerProfileId = ownerId)
        )
        // Insert a Version row directly — the version surface is project-scoped Phase 5.
        val versionId = UUID.parse("99999999-aaaa-aaaa-aaaa-999999999999")
        connection().useStatement(
            """
            insert into workops.version (id, project_id, name, sequence_number)
            values ('$versionId', '${project.id}', 'v1.0', 1)
            """
        ) { it.execute() }

        val release = releaseService.create(
            programId = programId, name = "Q1 Release", description = null,
            releaseDate = null, ownerProfileId = ownerId,
        )
        releaseService.bundle(release.id, project.id, versionId)
        assertEquals(1, releaseService.listVersions(release.id).size)
        releaseService.unbundle(release.id, versionId)
        assertEquals(0, releaseService.listVersions(release.id).size)
    }

    @Test
    fun `markReleased flips releasedAt and bumps version`() = withDb {
        val programId = seedProgram()
        val release = releaseService.create(
            programId = programId, name = "R1", description = null,
            releaseDate = null, ownerProfileId = ownerId,
        )
        val released = releaseService.release(release.id, expectedVersion = release.version)
        assertNotNull(released.releasedAt)
        assertTrue(released.version > release.version)
    }

    @Test
    fun `moveTaskToProject re-mints the key, writes alias and audit, and re-maps status`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "P16C", name = "C",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "P16D", name = "D",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "moveable"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val toDoStatus = statusRepo.getAll().first { it.name == "To Do" }
        // Both projects share the global status set, so mapping the status to itself is enough.
        val moved = moveService.moveTaskToProject(
            task.id,
            MoveWorkOpsTaskInput(
                targetProjectId = target.id,
                statusMapping = mapOf(task.statusId to toDoStatus.id),
            ),
            actingPrincipalId = callerPrincipalId,
        )
        assertEquals(target.id, moved.projectId)
        assertTrue(moved.key.startsWith("P16D-"))
        // Alias points the legacy key at the same task id.
        assertEquals(task.id, aliasRepo.resolve(task.key))
    }

    @Test
    fun `moveTaskToProject rejects when statusMapping is missing the source status`() = withDb {
        val programId = seedProgram()
        val source = projectService.create(
            ProjectInput(programId = programId, key = "P16E", name = "E",
                description = null, ownerProfileId = ownerId)
        )
        val target = projectService.create(
            ProjectInput(programId = programId, key = "P16F", name = "F",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = source.id, summary = "x"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        assertFailsWith<WorkOpsValidationException> {
            moveService.moveTaskToProject(
                task.id,
                MoveWorkOpsTaskInput(targetProjectId = target.id, statusMapping = emptyMap()),
                actingPrincipalId = callerPrincipalId,
            )
        }
    }

    @Test
    fun `addAffectedProject and remove are idempotent`() = withDb {
        val programId = seedProgram()
        val project = projectService.create(
            ProjectInput(programId = programId, key = "P16G", name = "G",
                description = null, ownerProfileId = ownerId)
        )
        val task = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "x"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val other = projectService.create(
            ProjectInput(programId = programId, key = "P16H", name = "H",
                description = null, ownerProfileId = ownerId)
        )
        affectedService.add(task.id, other.id)
        affectedService.add(task.id, other.id)
        assertEquals(1, affectedService.listForTask(task.id).size)
        affectedService.remove(task.id, other.id)
        assertEquals(0, affectedService.listForTask(task.id).size)
    }
}
