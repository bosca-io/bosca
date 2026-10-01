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
import bosca.security.model.AuthenticatedPrincipal
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.model.PermissionAction
import bosca.security.model.Principal
import bosca.security.service.AuthenticationContext
import bosca.security.service.GroupEvaluator
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.db.migrations.CoreMigration
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.workflow.Condition
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskPermissionRepositoryImpl
import bosca.workops.repository.TaskAffectedProjectRepositoryImpl
import bosca.workops.repository.TaskRepositoryImpl
import bosca.workops.repository.TaskTypeRepositoryImpl
import bosca.workops.repository.TaskTypeSchemeRepositoryImpl
import bosca.workops.repository.WorkflowRepositoryImpl
import bosca.workops.repository.WorkflowSchemeRepositoryImpl
import io.mockk.mockk
import bosca.di.asProvider
import kotlinx.coroutines.NonCancellable
import org.junit.AfterClass
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7 coverage: standard Bosca entity-permission evaluator
 * semantics on Work Ops entities, and the workflow engine's
 * permission-based conditions.
 */
class Phase7IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase7_test")
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
                key = "workops-phase7-test",
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

    private val securityService = mockk<SecurityService>(relaxed = true)
    private val groupEvaluator = GroupEvaluator(securityService)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
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

    private val taskPermissionEvaluator = TaskPermissionEvaluator(taskService, securityService, groupEvaluator)

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

    private fun authFor(
        principalId: UUID,
        profileId: UUID?,
        groups: List<String> = emptyList(),
    ): AuthenticationContext {
        val principal = Principal(
            id = principalId,
            primaryProfileId = profileId,
        )
        val groupRows = groups.mapIndexed { index, name ->
            Group(
                id = UUID.parse("11111111-2222-3333-4444-%012d".format(index + 1)),
                name = name,
                description = name,
                type = GroupType.PRINCIPAL,
            )
        }
        val authenticated = AuthenticatedPrincipal(principal, groupRows)
        return object : AuthenticationContext(null, null) {
            override fun principal(): AuthenticatedPrincipal = authenticated
        }
    }

    private val ownerProfileId: UUID = UUID.parse("11111111-1111-1111-1111-111111111111")
    private val reporterProfileId: UUID = UUID.parse("22222222-2222-2222-2222-222222222222")
    private val randomProfileId: UUID = UUID.parse("44444444-4444-4444-4444-444444444444")
    private val adminPrincipalId: UUID = UUID.parse("55555555-5555-5555-5555-555555555555")

    private suspend fun seedProject() = projectService.create(
        ProjectInput(
            programId = programService.create(
                ProgramInput(
                    portfolioId = portfolioService.create(
                        PortfolioInput(
                            key = "P7", name = "Phase7", description = null,
                            ownerProfileId = ownerProfileId,
                        )
                    ).id,
                    key = "P7P", name = "Phase7 Program", description = null,
                    ownerProfileId = ownerProfileId,
                )
            ).id,
            key = "P7", name = "Phase7 Project", description = null,
            ownerProfileId = ownerProfileId,
        )
    )

    private suspend fun createTask(projectId: UUID, assignee: UUID? = null) =
        taskService.create(
            CreateTaskInput(
                projectId = projectId,
                summary = "task",
                assigneeProfileId = assignee,
            ),
            actingPrincipalId = UUID.parse("99999999-0000-0000-0000-000000000000"),
            actingProfileId = reporterProfileId,
            reporterProfileId = reporterProfileId,
        )

    @Test
    fun `admin group bypasses every gate`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val admin = authFor(adminPrincipalId, ownerProfileId, groups = listOf("administrators"))
        for (action in PermissionAction.entries) {
            assertTrue(
                taskPermissionEvaluator.isAllowed(admin, task, action),
                "admin should pass $action",
            )
        }
    }

    @Test
    fun `unauthenticated context is denied on non-public entities`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        assertFalse(taskPermissionEvaluator.isAllowed(null, task, PermissionAction.VIEW))
    }

    @Test
    fun `editor group grants EDIT on tasks`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val editor = authFor(UUID.parse("66666666-0000-0000-0000-000000000000"), randomProfileId, groups = listOf("editors"))
        assertTrue(taskPermissionEvaluator.isAllowed(editor, task, PermissionAction.EDIT))
        assertFalse(taskPermissionEvaluator.isAllowed(editor, task, PermissionAction.MANAGE))
    }

    @Test
    fun `WorkflowEvaluator HasGlobalPermission checks PermissionAction set`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val evaluator = WorkflowEvaluator()
        val transitionDescriptor = bosca.workops.model.workflow.WorkflowTransition(
            id = UUID.parse("77777777-0000-0000-0000-000000000000"),
            workflowId = UUID.NIL,
            name = "x",
            fromStateIds = listOf("*"),
            toStateId = UUID.NIL,
        )

        val ctx = WorkflowContext(
            task = task,
            actingPrincipalId = UUID.parse("66666666-0000-0000-0000-000000000000"),
            actingProfileId = randomProfileId,
            comment = null,
            resolutionId = null,
            unresolvedSubtaskCount = 0,
            principalPermissions = setOf(PermissionAction.MANAGE),
        )

        val pass = evaluator.evaluate(
            transition = transitionDescriptor,
            conditions = listOf(Condition.HasGlobalPermission("MANAGE")),
            validators = emptyList(),
            postFunctions = emptyList(),
            context = ctx,
        )
        assertTrue(pass is TransitionEvaluation.Plan)

        val unknown = evaluator.evaluate(
            transition = transitionDescriptor,
            conditions = listOf(Condition.HasGlobalPermission("NOT_A_PERMISSION")),
            validators = emptyList(),
            postFunctions = emptyList(),
            context = ctx,
        )
        assertTrue(unknown is TransitionEvaluation.ConditionFailed)
    }

    @Test
    fun `HasProjectRole condition always denies after role removal`() = withDb {
        val project = seedProject()
        val task = createTask(project.id)
        val evaluator = WorkflowEvaluator()
        val transitionDescriptor = bosca.workops.model.workflow.WorkflowTransition(
            id = UUID.parse("77777777-0000-0000-0000-000000000000"),
            workflowId = UUID.NIL,
            name = "x",
            fromStateIds = listOf("*"),
            toStateId = UUID.NIL,
        )
        val ctx = WorkflowContext(
            task = task,
            actingPrincipalId = UUID.parse("66666666-0000-0000-0000-000000000000"),
            actingProfileId = randomProfileId,
            comment = null,
            resolutionId = null,
            unresolvedSubtaskCount = 0,
        )
        val deny = evaluator.evaluate(
            transition = transitionDescriptor,
            conditions = listOf(Condition.HasProjectRole(UUID.parse("a0000000-0000-0000-0000-000000000001"))),
            validators = emptyList(),
            postFunctions = emptyList(),
            context = ctx,
        )
        assertTrue(deny is TransitionEvaluation.ConditionFailed)
    }
}
