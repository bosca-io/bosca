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
import bosca.workops.model.portal.PortalAuthMode
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.PortalRepositoryImpl
import bosca.workops.repository.PortalTokenRepositoryImpl
import bosca.workops.repository.PortalUserRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.RequestTypeRepositoryImpl
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Phase14IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase14_test")
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
                key = "workops-phase14-test",
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

    private val portalRepo = PortalRepositoryImpl()
    private val requestTypeRepo = RequestTypeRepositoryImpl()
    private val portalUserRepo = PortalUserRepositoryImpl()
    private val portalTokenRepo = PortalTokenRepositoryImpl()

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

    private val portalService = PortalServiceImpl(portalRepo, requestTypeRepo)
    private val tokenService = PortalTokenServiceImpl(portalTokenRepo)
    private val submissionService = PortalSubmissionServiceImpl(
        portalRepository = portalRepo,
        requestTypeRepository = requestTypeRepo,
        portalUserRepository = portalUserRepo,
        portalTokenRepository = portalTokenRepo,
        taskService = taskService,
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
                connection().useStatement("DELETE FROM workops.portal_token") { it.execute() }
                connection().useStatement("DELETE FROM workops.portal_user") { it.execute() }
                connection().useStatement("DELETE FROM workops.portal_request_type") { it.execute() }
                connection().useStatement("DELETE FROM workops.portal") { it.execute() }
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
    private val authedProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")

    private suspend fun seedProject() = projectService.create(
        ProjectInput(
            programId = programService.create(
                ProgramInput(
                    portfolioId = portfolioService.create(
                        PortfolioInput(key = "P14", name = "P14",
                            description = null, ownerProfileId = ownerId)
                    ).id,
                    key = "P14P", name = "P14 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P14", name = "P14 Project", description = null,
            ownerProfileId = ownerId,
        )
    )

    private suspend fun createPortal(
        projectId: UUID,
        authMode: PortalAuthMode,
        allow: List<String> = emptyList(),
    ) = portalService.create(
        CreatePortalInput(
            slug = "support-${authMode.name.lowercase()}",
            name = "Support",
            description = null,
            projectId = projectId,
            themeColorHex = "#3b82f6",
            welcomeMarkdown = null,
            supportEmail = "support@example.com",
            authMode = authMode,
            anonymousAllowlistDomains = allow,
            slaPolicyId = null,
            enabled = true,
        )
    )

    @Test
    fun `authenticated submission creates a task without a magic-link token`() = withDb {
        val project = seedProject()
        val portal = createPortal(project.id, PortalAuthMode.AUTHENTICATED_ONLY)
        val taskType = taskTypeRepo.getAll().first { it.name == "Task" }
        val rt = portalService.addRequestType(
            portalId = portal.id, name = "Bug report", description = null,
            taskTypeId = taskType.id, defaultPriorityId = null, displayOrder = 0,
        )
        val result = submissionService.submit(
            portalId = portal.id,
            input = PortalSubmissionInput(
                requestTypeId = rt.id,
                summary = "X is broken",
                description = "details",
                reporterEmail = "user@example.com",
                reporterDisplayName = "User",
                reporterProfileId = authedProfileId,
            )
        )
        assertNotNull(result.task.id)
        assertNull(result.magicLinkToken)
    }

    @Test
    fun `anonymous submission mints a token and persists hashed`() = withDb {
        val project = seedProject()
        val portal = createPortal(project.id, PortalAuthMode.ALLOW_ANONYMOUS_VIA_EMAIL)
        val taskType = taskTypeRepo.getAll().first { it.name == "Task" }
        val rt = portalService.addRequestType(
            portalId = portal.id, name = "Bug report", description = null,
            taskTypeId = taskType.id, defaultPriorityId = null, displayOrder = 0,
        )
        val result = submissionService.submit(
            portalId = portal.id,
            input = PortalSubmissionInput(
                requestTypeId = rt.id,
                summary = "anon report",
                description = null,
                reporterEmail = "anon@example.com",
                reporterDisplayName = null,
            )
        )
        assertNotNull(result.magicLinkToken)
        // The token verifies through the service (hash round-trip).
        val verified = tokenService.verify(result.magicLinkToken!!)
        assertNotNull(verified)
        assertEquals(result.task.id, verified.taskId)
    }

    @Test
    fun `AUTHENTICATED_ONLY portal rejects anonymous submission`() = withDb {
        val project = seedProject()
        val portal = createPortal(project.id, PortalAuthMode.AUTHENTICATED_ONLY)
        val taskType = taskTypeRepo.getAll().first { it.name == "Task" }
        val rt = portalService.addRequestType(
            portalId = portal.id, name = "Bug report", description = null,
            taskTypeId = taskType.id, defaultPriorityId = null, displayOrder = 0,
        )
        assertFailsWith<WorkOpsValidationException> {
            submissionService.submit(
                portalId = portal.id,
                input = PortalSubmissionInput(
                    requestTypeId = rt.id,
                    summary = "x", description = null,
                    reporterEmail = "anon@example.com",
                    reporterDisplayName = null,
                )
            )
        }
    }

    @Test
    fun `anonymous submission rejected when domain not in allow-list`() = withDb {
        val project = seedProject()
        val portal = createPortal(project.id, PortalAuthMode.ALLOW_ANONYMOUS_VIA_EMAIL,
            allow = listOf("trusted.com"))
        val taskType = taskTypeRepo.getAll().first { it.name == "Task" }
        val rt = portalService.addRequestType(
            portalId = portal.id, name = "Bug report", description = null,
            taskTypeId = taskType.id, defaultPriorityId = null, displayOrder = 0,
        )
        assertFailsWith<WorkOpsValidationException> {
            submissionService.submit(
                portalId = portal.id,
                input = PortalSubmissionInput(
                    requestTypeId = rt.id,
                    summary = "x", description = null,
                    reporterEmail = "anon@notallowed.com",
                    reporterDisplayName = null,
                )
            )
        }
        // Same submission with an allow-listed domain succeeds.
        val ok = submissionService.submit(
            portalId = portal.id,
            input = PortalSubmissionInput(
                requestTypeId = rt.id,
                summary = "x", description = null,
                reporterEmail = "anon@trusted.com",
                reporterDisplayName = null,
            )
        )
        assertNotNull(ok.magicLinkToken)
    }

    @Test
    fun `slug lookup resolves portal`() = withDb {
        val project = seedProject()
        val portal = createPortal(project.id, PortalAuthMode.MIXED)
        val resolved = portalService.getBySlug(portal.slug)
        assertNotNull(resolved)
        assertEquals(portal.id, resolved.id)
    }
}
