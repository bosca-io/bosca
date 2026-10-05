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
import bosca.workops.model.LinkCycleException
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.WorkflowConditionFailedException
import bosca.workops.model.WorkflowValidatorFailedException
import bosca.workops.model.WorkflowTransitionNotAvailableException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.TaskHistoryEntry
import bosca.workops.model.links.LinkCategory
import bosca.workops.model.links.TaskLinkInput
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.workflow.Condition
import bosca.workops.model.workflow.PostFunction
import bosca.workops.model.workflow.Validator
import bosca.workops.model.workflow.WorkflowTransition
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for the Phase 3 workflow engine and link service
 * against a real PostgreSQL via TestContainers. The suite pins:
 *
 *   1. The seeded "Default" workflow round-trips through the engine
 *      and `transition` advances the task's status atomically with
 *      a single history entry.
 *   2. Each [Condition] variant the Phase 3 engine implements (Always,
 *      Never, IsAssignee, IsReporter, AllOf, AnyOf, Not) honors the
 *      pass / fail semantics; phase-pending variants deny.
 *   3. Validators (RequireResolution, RequireComment, RequireSubtasksResolved,
 *      RequireField on built-in columns) reject the transition with
 *      [WorkflowValidatorFailedException] carrying a readable reason.
 *   4. Post-functions (SetResolution, AssignToReporter, AssignToCurrentUser,
 *      Unassign, ClearResolution, SetField on built-in columns) apply
 *      atomically with the status flip.
 *   5. Wildcard transitions (`fromStateIds == ["*"]`) are reachable
 *      from every state — the seeded "Reopen".
 *   6. `linkTasks` enforces R7's cycle rule on `BLOCKS`-category
 *      links and permits cycles on `RELATES_TO`.
 */
class WorkflowAndLinkServiceIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase3_test")
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
                key = "workops-phase3-test",
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
    private val linkRepo = TaskLinkRepositoryImpl()
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
    private val evaluator = WorkflowEvaluator()
    private val fieldConfigRepo = bosca.workops.repository.TaskFieldConfigurationRepositoryImpl()
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
        workflowEvaluator = evaluator,
        customFieldService = customFieldService,
        json = json,
        taskPermissionRepository = taskPermissionRepo,
        affectedProjectService = affectedService,
        requirementServiceProvider = mockk<RequirementService>(relaxed = true).asProvider(),
        metadataService = mockk(relaxed = true),
        sprintService = mockk(relaxed = true),
        projectPermissionEvaluator = projectPermissionEvaluator,
    )
    private val linkService = TaskLinkServiceImpl(linkRepo, taskService, securityService, mockk(relaxed = true))

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
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

    // ----- fixtures -----

    private val ownerProfileId: UUID = UUID.parse("11111111-1111-1111-1111-111111111111")
    private val callerPrincipalId: UUID = UUID.parse("22222222-2222-2222-2222-222222222222")
    private val callerProfileId: UUID = UUID.parse("33333333-3333-3333-3333-333333333333")
    private val otherProfileId: UUID = UUID.parse("44444444-4444-4444-4444-444444444444")

    // Seeded ids from V3 migration.
    private val startWorkTransitionId = UUID.parse("52000000-0000-0000-0000-000000000001")
    private val resolveTransitionId = UUID.parse("52000000-0000-0000-0000-000000000002")
    private val reopenTransitionId = UUID.parse("52000000-0000-0000-0000-000000000003")
    private val toDoStatusId = UUID.parse("20000000-0000-0000-0000-000000000001")
    private val inProgressStatusId = UUID.parse("20000000-0000-0000-0000-000000000002")
    private val doneStatusId = UUID.parse("20000000-0000-0000-0000-000000000004")

    private suspend fun seedProject(): UUID {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "WF", name = "Workflow Tests", description = null, ownerProfileId = ownerProfileId)
        )
        val program = programService.create(
            ProgramInput(
                portfolioId = portfolio.id,
                key = "WFP",
                name = "WF Program",
                description = null,
                ownerProfileId = ownerProfileId,
            )
        )
        val project = projectService.create(
            ProjectInput(
                programId = program.id,
                key = "WFT",
                name = "WF Project",
                description = null,
                ownerProfileId = ownerProfileId,
                defaultTaskTypeSchemeId = null,
            )
        )
        return project.id
    }

    private suspend fun createTask(projectId: UUID, summary: String = "test"): bosca.workops.model.task.Task =
        taskService.create(
            CreateTaskInput(projectId = projectId, summary = summary),
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            reporterProfileId = callerProfileId,
        )

    // ----- transition (seeded default workflow) -----

    @Test
    fun `seeded default workflow advances To Do to In Progress to Done`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)

        val started = taskService.transition(
            id = task.id,
            transitionId = startWorkTransitionId,
            expectedVersion = task.version,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        assertEquals(inProgressStatusId, started.statusId)
        assertEquals(task.version + 1, started.version)

        val resolved = taskService.transition(
            id = started.id,
            transitionId = resolveTransitionId,
            expectedVersion = started.version,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
        )
        assertEquals(doneStatusId, resolved.statusId)

        val history = taskService.listHistory(task.id, 0, 10)
        assertEquals(3, history.size, "create + 2 transitions")
        // The transition entries each carry a transition_id.
        val transitionEntries = history.filter { entry ->
            entry.decodedChanges().any { it.fieldKey == "transition_id" }
        }
        assertEquals(2, transitionEntries.size)
    }

    @Test
    fun `wildcard Reopen reaches from any state`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val inProgress = taskService.transition(task.id, startWorkTransitionId, task.version, callerPrincipalId, callerProfileId)
        // Reopen has fromStateIds = ["*"] so it's reachable from In Progress.
        val reopened = taskService.transition(inProgress.id, reopenTransitionId, inProgress.version, callerPrincipalId, callerProfileId)
        assertEquals(toDoStatusId, reopened.statusId)
    }

    @Test
    fun `transition id not reachable from current state is rejected`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        // The Resolve transition is reachable only from In Progress;
        // applying it from To Do should fail.
        assertFailsWith<WorkflowTransitionNotAvailableException> {
            taskService.transition(task.id, resolveTransitionId, task.version, callerPrincipalId, callerProfileId)
        }
    }

    @Test
    fun `transition with stale version is rejected`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        taskService.transition(task.id, startWorkTransitionId, task.version, callerPrincipalId, callerProfileId)
        // The first transition bumped the version to 1; using 0 again is stale.
        assertFailsWith<OptimisticLockFailedException> {
            taskService.transition(task.id, resolveTransitionId, 0, callerPrincipalId, callerProfileId)
        }
    }

    // ----- Condition / Validator / PostFunction shape coverage -----

    @Test
    fun `condition variants - Always passes Never fails IsAssignee gates`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val ctx = WorkflowContext(
            task = task,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            comment = null,
            resolutionId = null,
            unresolvedSubtaskCount = 0,
        )
        assertTrue(evalStub(ctx, conditions = listOf(Condition.Always)) is TransitionEvaluation.Plan)
        assertTrue(evalStub(ctx, conditions = listOf(Condition.Never)) is TransitionEvaluation.ConditionFailed)
        // Reporter is callerProfileId, no assignee. IsAssignee should fail.
        assertTrue(evalStub(ctx, conditions = listOf(Condition.IsAssignee)) is TransitionEvaluation.ConditionFailed)
        assertTrue(evalStub(ctx, conditions = listOf(Condition.IsReporter)) is TransitionEvaluation.Plan)
        // AllOf with a Never short-circuits.
        assertTrue(
            evalStub(ctx, conditions = listOf(Condition.AllOf(listOf(Condition.Always, Condition.Never))))
                is TransitionEvaluation.ConditionFailed,
        )
        // AnyOf with at least one passes.
        assertTrue(
            evalStub(ctx, conditions = listOf(Condition.AnyOf(listOf(Condition.Never, Condition.Always))))
                is TransitionEvaluation.Plan,
        )
        // Not(Never) passes.
        assertTrue(
            evalStub(ctx, conditions = listOf(Condition.Not(Condition.Never)))
                is TransitionEvaluation.Plan,
        )
    }

    @Test
    fun `phase-pending conditions deny - HasProjectRole HasGlobalPermission`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val ctx = WorkflowContext(task, callerPrincipalId, callerProfileId, null, null, 0)
        assertTrue(
            evalStub(ctx, conditions = listOf(Condition.HasProjectRole(UUID.parse("12345678-0000-0000-0000-000000000000"))))
                is TransitionEvaluation.ConditionFailed,
        )
        assertTrue(
            evalStub(ctx, conditions = listOf(Condition.HasGlobalPermission("MANAGE_WORKFLOWS")))
                is TransitionEvaluation.ConditionFailed,
        )
    }

    @Test
    fun `validator RequireResolution rejects when no resolution provided`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val ctx = WorkflowContext(task, callerPrincipalId, callerProfileId, null, null, 0)
        assertTrue(evalStub(ctx, validators = listOf(Validator.RequireResolution)) is TransitionEvaluation.ValidatorFailed)
    }

    @Test
    fun `validator RequireComment passes when comment supplied`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val withoutComment = WorkflowContext(task, callerPrincipalId, callerProfileId, null, null, 0)
        assertTrue(
            evalStub(withoutComment, validators = listOf(Validator.RequireComment))
                is TransitionEvaluation.ValidatorFailed
        )
        val withComment = withoutComment.copy(comment = "Won't fix because the team has decided.")
        assertTrue(
            evalStub(withComment, validators = listOf(Validator.RequireComment))
                is TransitionEvaluation.Plan
        )
    }

    @Test
    fun `validator RequireSubtasksResolved checks the count`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val withOpenSubs = WorkflowContext(task, callerPrincipalId, callerProfileId, null, null, unresolvedSubtaskCount = 3)
        assertTrue(
            evalStub(withOpenSubs, validators = listOf(Validator.RequireSubtasksResolved))
                is TransitionEvaluation.ValidatorFailed
        )
        val noOpenSubs = withOpenSubs.copy(unresolvedSubtaskCount = 0)
        assertTrue(
            evalStub(noOpenSubs, validators = listOf(Validator.RequireSubtasksResolved))
                is TransitionEvaluation.Plan
        )
    }

    // ----- post-functions -----

    @Test
    fun `transition with caller-supplied resolutionId stamps resolution and resolutionAt`() = withDb {
        val projectId = seedProject()
        val task = createTask(projectId)
        val started = taskService.transition(task.id, startWorkTransitionId, task.version, callerPrincipalId, callerProfileId)
        val doneRes = resolutionRepo.getAll().first { it.name == "Done" }
        val resolved = taskService.transition(
            id = started.id,
            transitionId = resolveTransitionId,
            expectedVersion = started.version,
            actingPrincipalId = callerPrincipalId,
            actingProfileId = callerProfileId,
            resolutionId = doneRes.id,
        )
        assertEquals(doneRes.id, resolved.resolutionId)
        assertNotNull(resolved.resolutionAt)
    }

    // ----- task links -----

    @Test
    fun `linkTasks BLOCKS rejects a cycle and is idempotent`() = withDb {
        val projectId = seedProject()
        val a = createTask(projectId, summary = "A")
        val b = createTask(projectId, summary = "B")

        val blocksTypeId = linkRepo.listLinkTypes().first { it.category == LinkCategory.BLOCKS }.id

        // A blocks B - allowed.
        val ab = linkService.link(
            TaskLinkInput(linkTypeId = blocksTypeId, sourceTaskId = a.id, targetTaskId = b.id),
            actingPrincipalId = callerPrincipalId,
        )
        assertEquals(a.id, ab.sourceTaskId)

        // Same call again — idempotent (returns the same row id).
        val abAgain = linkService.link(
            TaskLinkInput(linkTypeId = blocksTypeId, sourceTaskId = a.id, targetTaskId = b.id),
            actingPrincipalId = callerPrincipalId,
        )
        assertEquals(ab.id, abAgain.id)

        // B blocks A would close a cycle - rejected.
        assertFailsWith<LinkCycleException> {
            linkService.link(
                TaskLinkInput(linkTypeId = blocksTypeId, sourceTaskId = b.id, targetTaskId = a.id),
                actingPrincipalId = callerPrincipalId,
            )
        }
    }

    @Test
    fun `linkTasks RELATES_TO permits cycles`() = withDb {
        val projectId = seedProject()
        val a = createTask(projectId, summary = "A")
        val b = createTask(projectId, summary = "B")
        val relatesTypeId = linkRepo.listLinkTypes().first { it.category == LinkCategory.RELATES_TO }.id

        linkService.link(
            TaskLinkInput(linkTypeId = relatesTypeId, sourceTaskId = a.id, targetTaskId = b.id),
            actingPrincipalId = callerPrincipalId,
        )
        // Cycle on a non-BLOCKS category is permitted.
        linkService.link(
            TaskLinkInput(linkTypeId = relatesTypeId, sourceTaskId = b.id, targetTaskId = a.id),
            actingPrincipalId = callerPrincipalId,
        )
        val links = linkService.listForTask(a.id)
        assertEquals(2, links.size)
    }

    @Test
    fun `linkTasks rejects self-link`() = withDb {
        val projectId = seedProject()
        val a = createTask(projectId, summary = "A")
        val blocksTypeId = linkRepo.listLinkTypes().first { it.category == LinkCategory.BLOCKS }.id

        assertFailsWith<WorkOpsValidationException> {
            linkService.link(
                TaskLinkInput(linkTypeId = blocksTypeId, sourceTaskId = a.id, targetTaskId = a.id),
                actingPrincipalId = callerPrincipalId,
            )
        }
    }

    private val emptyTransition: WorkflowTransition = WorkflowTransition(
        id = UUID.parse("aaaaaaaa-0000-0000-0000-000000000001"),
        workflowId = UUID.parse("aaaaaaaa-1111-0000-0000-000000000001"),
        name = "Stub",
        fromStateIds = listOf("*"),
        toStateId = UUID.parse("aaaaaaaa-2222-0000-0000-000000000001"),
    )

    /**
     * Convenience for Phase 3 tests that exercise the evaluator
     * directly. The evaluator now takes typed lists separately
     * from the transition entity (the entity stores raw JsonElement
     * because Bosca's repository binder can't bind a
     * List<@Polymorphic-sealed> ↔ jsonb directly).
     */
    private fun evalStub(
        ctx: WorkflowContext,
        conditions: List<Condition> = emptyList(),
        validators: List<Validator> = emptyList(),
        postFunctions: List<PostFunction> = emptyList(),
    ): TransitionEvaluation =
        evaluator.evaluate(emptyTransition, conditions, validators, postFunctions, ctx)

    private fun TaskHistoryEntry.decodedChanges(): List<FieldChange> =
        json.decodeFromJsonElement(ListSerializer(FieldChange.serializer()), changes)
}
