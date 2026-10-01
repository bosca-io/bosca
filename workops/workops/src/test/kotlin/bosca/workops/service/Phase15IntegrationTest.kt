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
import bosca.workops.model.emailin.EmailInboxKind
import bosca.workops.model.emailin.InboundEmailOutcome
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import io.mockk.mockk
import bosca.di.asProvider
import bosca.workops.repository.EmailInboxRepositoryImpl
import bosca.workops.repository.InboundEmailRepositoryImpl
import bosca.workops.repository.OutboundMessageIdRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
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
import kotlinx.serialization.json.Json
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Phase15IntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase15_test")
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
                key = "workops-phase15-test",
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
    private val taskCommentRepo = TaskCommentRepositoryImpl()
    private val inboxRepo = EmailInboxRepositoryImpl()
    private val inboundRepo = InboundEmailRepositoryImpl()
    private val outboundRepo = OutboundMessageIdRepositoryImpl()

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

    private val inboxService = EmailInboxServiceImpl(inboxRepo)
    private val outboundService = OutboundMessageIdServiceImpl(outboundRepo)
    private val processor = EmailInProcessorImpl(
        inboxRepository = inboxRepo,
        auditRepository = inboundRepo,
        outboundRepository = outboundRepo,
        taskService = taskService,
        taskCommentService = taskCommentService,
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
                connection().useStatement("DELETE FROM workops.outbound_message_id") { it.execute() }
                connection().useStatement("DELETE FROM workops.inbound_email") { it.execute() }
                connection().useStatement("DELETE FROM workops.email_inbox") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_comment_likes") { it.execute() }
                connection().useStatement("DELETE FROM workops.task_comment") { it.execute() }
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
                        PortfolioInput(key = "P15", name = "P15",
                            description = null, ownerProfileId = ownerId)
                    ).id,
                    key = "P15P", name = "P15 Program",
                    description = null, ownerProfileId = ownerId,
                )
            ).id,
            key = "P15", name = "P15 Project", description = null,
            ownerProfileId = ownerId,
        )
    )

    private suspend fun seedInbox(projectId: UUID) = inboxService.create(
        CreateEmailInboxInput(
            name = "Support",
            description = null,
            kind = EmailInboxKind.POSTMARK_WEBHOOK,
            address = "support@example.com",
            projectId = projectId,
            defaultTaskTypeId = null,
            defaultPriorityId = null,
        )
    )

    @Test
    fun `unmatched In-Reply-To creates a fresh task`() = withDb {
        val project = seedProject()
        val inbox = seedInbox(project.id)
        val result = processor.process(
            inboxId = inbox.id,
            message = InboundMessage(
                messageId = "<a@b.example.com>",
                inReplyTo = null,
                fromAddress = "user@elsewhere.com",
                subject = "Help me",
                bodyMarkdown = "There is a problem.",
            )
        )
        assertEquals(InboundEmailOutcome.NEW_TASK.name, result.outcome.name)
        assertNotNull(result.taskId)
    }

    @Test
    fun `matched In-Reply-To appends a comment to the original task`() = withDb {
        val project = seedProject()
        val inbox = seedInbox(project.id)
        // Seed a task and a registered Message-ID pointing at it.
        val task = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "original"),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        val notifyMessageId = "abc-${task.id}@workops.example"
        outboundService.register(notifyMessageId, task.id)
        // Reply with that Message-ID in In-Reply-To.
        val result = processor.process(
            inboxId = inbox.id,
            message = InboundMessage(
                messageId = "<reply@example.com>",
                inReplyTo = "<$notifyMessageId>",
                fromAddress = "user@example.com",
                subject = "RE: original",
                bodyMarkdown = "More context.",
            )
        )
        assertEquals(InboundEmailOutcome.APPENDED_COMMENT.name, result.outcome.name)
        assertEquals(task.id, result.taskId)
        assertNotNull(result.commentId)
    }

    @Test
    fun `auto-submitted header trips the loop guard and DLQs`() = withDb {
        val project = seedProject()
        val inbox = seedInbox(project.id)
        val result = processor.process(
            inboxId = inbox.id,
            message = InboundMessage(
                messageId = "<vacation@example.com>",
                inReplyTo = null,
                fromAddress = "vacation-bot@example.com",
                subject = "Out of office",
                bodyMarkdown = "I'm away",
                autoSubmitted = "auto-replied",
            )
        )
        assertEquals(InboundEmailOutcome.DLQ_LOOP_GUARD.name, result.outcome.name)
    }

    @Test
    fun `dispatcher-stamped Message-ID matches an inbound reply end-to-end`() = withDb {
        // Register a Message-ID via the outbound service and prove the
        // inbound side matches that exact stamped ID.
        val project = seedProject()
        val inbox = seedInbox(project.id)
        val task = taskService.create(
            CreateTaskInput(projectId = project.id, summary = "ping",
                assigneeProfileId = callerProfileId),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )
        // Simulate what the platform event handler does: register a
        // stamped Message-ID for the outbound email notification.
        val stamped = "stamped-${task.id}@workops.example"
        outboundService.register(stamped, task.id)
        // The inbound processor matches that exact Message-ID.
        val result = processor.process(
            inboxId = inbox.id,
            message = InboundMessage(
                messageId = "<reply@example.com>",
                inReplyTo = "<$stamped>",
                fromAddress = "user@example.com",
                subject = "RE: ${task.key}",
                bodyMarkdown = "thanks",
            )
        )
        assertEquals(InboundEmailOutcome.APPENDED_COMMENT.name, result.outcome.name)
        assertEquals(task.id, result.taskId)
    }

    @Test
    fun `unverified sender is DLQ'd before any task work`() = withDb {
        val project = seedProject()
        val inbox = seedInbox(project.id)
        val result = processor.process(
            inboxId = inbox.id,
            message = InboundMessage(
                messageId = "<spoofed@evil.example>",
                inReplyTo = null,
                fromAddress = "spoofed@evil.example",
                subject = "spoofed",
                bodyMarkdown = "...",
                verifiedSender = false,
            )
        )
        assertEquals(InboundEmailOutcome.DLQ_UNVERIFIED_SENDER.name, result.outcome.name)
    }
}
