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
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.SprintAlreadyActiveException
import bosca.workops.model.SprintCloseUnfinishedException
import bosca.workops.model.VersionInUseException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.board.BoardType
import bosca.workops.model.board.CreateBoardColumnInput
import bosca.workops.model.board.CreateBoardInput
import bosca.workops.model.board.SwimlaneStrategy
import bosca.workops.model.component.ComponentAssigneeMode
import bosca.workops.model.component.CreateComponentInput
import bosca.workops.model.label.CreateLabelInput
import bosca.workops.model.label.LabelScope
import bosca.workops.model.milestone.CreateMilestoneInput
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.sprint.CloseSprintDestination
import bosca.workops.model.sprint.CloseSprintInput
import bosca.workops.model.sprint.CreateSprintInput
import bosca.workops.model.sprint.StartSprintInput
import bosca.workops.model.version.CreateVersionInput
import io.mockk.mockk
import bosca.workops.repository.BoardRepositoryImpl
import bosca.workops.repository.ComponentRepositoryImpl
import bosca.workops.repository.LabelRepositoryImpl
import bosca.workops.repository.MilestoneRepositoryImpl
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.SprintRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.VersionRepositoryImpl
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
 * Integration tests for the Phase 5 agile / release surface (R8 + R9).
 * Pins:
 *  - Single-active-sprint per board enforcement
 *  - Sprint close orphan-prevention (every committed task needs a destination)
 *  - Board scope check (exactly one of project/program)
 *  - Board column status-id validation
 *  - Version delete blocked when tasks reference it
 *  - Component CRUD
 *  - Label kebab-case + scope-parent consistency
 *  - Milestone close lifecycle
 */
class AgileServicesIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_phase5_test")
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
                key = "workops-phase5-test",
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
    private val statusRepo = StatusRepositoryImpl()
    private val sprintRepo = SprintRepositoryImpl()
    private val boardRepo = BoardRepositoryImpl()
    private val versionRepo = VersionRepositoryImpl()
    private val componentRepo = ComponentRepositoryImpl()
    private val labelRepo = LabelRepositoryImpl()
    private val milestoneRepo = MilestoneRepositoryImpl()

    private val securityService = mockk<SecurityService>(relaxed = true)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
    private val groupEvaluator = bosca.security.service.GroupEvaluator(securityService)
    private val portfolioPermissionEvaluator = PortfolioPermissionEvaluator(portfolioService, securityService, groupEvaluator)
    private val programService = ProgramServiceImpl(programRepo, portfolioRepo, programPermissionRepo, portfolioPermissionRepo, portfolioPermissionEvaluator)
    private val programPermissionEvaluator = ProgramPermissionEvaluator(programService, securityService, groupEvaluator)
    private val projectService = ProjectServiceImpl(projectRepo, programRepo, keyCounterRepo, projectPermissionRepo, programPermissionRepo, programPermissionEvaluator)
    private val projectPermissionEvaluator = ProjectPermissionEvaluator(projectService, securityService, groupEvaluator)
    private val boardService = BoardServiceImpl(boardRepo, statusRepo)
    private val sprintService = SprintServiceImpl(sprintRepo, boardService, projectService, programService)
    private val versionService = VersionServiceImpl(versionRepo)
    private val componentService = ComponentServiceImpl(componentRepo)
    private val labelService = LabelServiceImpl(labelRepo)
    private val milestoneService = MilestoneServiceImpl(milestoneRepo)

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
                connection().useStatement("DELETE FROM workops.sprint") { it.execute() }
                connection().useStatement("DELETE FROM workops.board_column") { it.execute() }
                connection().useStatement("DELETE FROM workops.board_project") { it.execute() }
                connection().useStatement("DELETE FROM workops.board") { it.execute() }
                connection().useStatement("DELETE FROM workops.version") { it.execute() }
                connection().useStatement("DELETE FROM workops.component") { it.execute() }
                connection().useStatement("DELETE FROM workops.label") { it.execute() }
                connection().useStatement("DELETE FROM workops.milestone") { it.execute() }
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

    private suspend fun seed(): Triple<UUID, UUID, UUID> {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "AG", name = "Agile", description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "AGM", name = "AG Mod", description = null, ownerProfileId = ownerId)
        )
        val project = projectService.create(
            ProjectInput(programId = program.id, key = "AGP", name = "AG Proj", description = null, ownerProfileId = ownerId)
        )
        return Triple(portfolio.id, program.id, project.id)
    }

    @Test
    fun `board scope must have at most one parent`() = withDb {
        val (_, programId, projectId) = seed()
        // Both project and program — invalid.
        assertFailsWith<WorkOpsValidationException> {
            boardService.create(
                CreateBoardInput(projectId = projectId, programId = programId, name = "x", type = BoardType.KANBAN)
            )
        }
        // No parent and no projectIds — invalid.
        assertFailsWith<WorkOpsValidationException> {
            boardService.create(CreateBoardInput(name = "x", type = BoardType.KANBAN))
        }
        // projectId + projectIds — mutually exclusive, invalid.
        assertFailsWith<WorkOpsValidationException> {
            boardService.create(
                CreateBoardInput(projectId = projectId, projectIds = listOf(projectId), name = "x", type = BoardType.KANBAN)
            )
        }
        // Single project-scope — valid.
        val board = boardService.create(
            CreateBoardInput(projectId = projectId, name = "Project Board", type = BoardType.KANBAN)
        )
        assertNotNull(board.projectId)
        // Single-project board should also have a board_project row.
        val bp = boardService.listBoardProjects(board.id)
        assertEquals(1, bp.size)
        assertEquals(projectId, bp.first().projectId)
    }

    @Test
    fun `multi-project board spans multiple projects`() = withDb {
        val (portfolioId, _, project1Id) = seed()
        val program2 = programService.create(
            ProgramInput(portfolioId = portfolioId, key = "AG2", name = "AG Mod 2", description = null, ownerProfileId = ownerId)
        )
        val project2 = projectService.create(
            ProjectInput(programId = program2.id, key = "AGQ", name = "AG Proj 2", description = null, ownerProfileId = ownerId)
        )

        // Create a multi-project board spanning two projects.
        val board = boardService.create(
            CreateBoardInput(
                projectIds = listOf(project1Id, project2.id),
                name = "Cross-Project Board",
                type = BoardType.KANBAN,
                swimlaneStrategy = SwimlaneStrategy.PROJECT,
            )
        )
        // No single parent set.
        assertEquals(null, board.projectId)
        assertEquals(null, board.programId)
        assertEquals(null, board.portfolioId)

        // Both projects are linked via board_project.
        val projects = boardService.listBoardProjects(board.id)
        assertEquals(2, projects.size)
        assertTrue(projects.map { it.projectId }.containsAll(listOf(project1Id, project2.id)))

        // Discoverable via listMultiProjectByProjectIds.
        val found = boardService.listMultiProjectByProjectIds(listOf(project1Id))
        assertTrue(found.any { it.id == board.id })

        // Add a third project dynamically.
        val project3 = projectService.create(
            ProjectInput(programId = program2.id, key = "AGR", name = "AG Proj 3", description = null, ownerProfileId = ownerId)
        )
        boardService.addBoardProject(board.id, project3.id)
        assertEquals(3, boardService.listBoardProjects(board.id).size)

        // Remove one project.
        boardService.removeBoardProject(board.id, project3.id)
        assertEquals(2, boardService.listBoardProjects(board.id).size)
    }

    @Test
    fun `board column rejects empty status list`() = withDb {
        val (_, _, projectId) = seed()
        val board = boardService.create(
            CreateBoardInput(projectId = projectId, name = "Empty", type = BoardType.KANBAN)
        )
        assertFailsWith<WorkOpsValidationException> {
            boardService.addColumn(
                CreateBoardColumnInput(boardId = board.id, name = "Bad", displayOrder = 0, statusIds = emptyList())
            )
        }
    }

    @Test
    fun `single-active-sprint per board is enforced`() = withDb {
        val (_, _, projectId) = seed()
        val board = boardService.create(
            CreateBoardInput(projectId = projectId, name = "Scrum", type = BoardType.SCRUM)
        )

        val sprint1 = sprintService.create(CreateSprintInput(boardId = board.id, name = "Sprint 1"))
        val started = sprintService.start(StartSprintInput(sprintId = sprint1.id, expectedVersion = sprint1.version))
        assertEquals("active", started.state.name.lowercase())

        val sprint2 = sprintService.create(CreateSprintInput(boardId = board.id, name = "Sprint 2"))
        assertFailsWith<SprintAlreadyActiveException> {
            sprintService.start(StartSprintInput(sprintId = sprint2.id, expectedVersion = sprint2.version))
        }
    }

    @Test
    fun `sprint close requires destinations for committed tasks`() = withDb {
        val (_, _, projectId) = seed()
        val board = boardService.create(
            CreateBoardInput(projectId = projectId, name = "Scrum", type = BoardType.SCRUM)
        )
        val sprint = sprintService.create(CreateSprintInput(boardId = board.id, name = "Sprint A"))
        val taskAId = UUID.parse("aaaaaaaa-0000-0000-0000-000000000001")
        val taskBId = UUID.parse("aaaaaaaa-0000-0000-0000-000000000002")
        val started = sprintService.start(
            StartSprintInput(
                sprintId = sprint.id,
                expectedVersion = sprint.version,
                committedTaskIds = listOf(taskAId, taskBId),
            )
        )

        // Closing without destinations fails.
        assertFailsWith<SprintCloseUnfinishedException> {
            sprintService.close(CloseSprintInput(sprintId = started.id, expectedVersion = started.version))
        }
        // Closing with destinations for both succeeds.
        val closed = sprintService.close(
            CloseSprintInput(
                sprintId = started.id,
                expectedVersion = started.version,
                destinations = listOf(
                    CloseSprintDestination(taskId = taskAId),
                    CloseSprintDestination(taskId = taskBId),
                ),
            )
        )
        assertEquals("closed", closed.state.name.lowercase())
    }

    @Test
    fun `version delete is blocked when a task references it`() = withDb {
        val (_, _, projectId) = seed()
        val v = versionService.create(
            CreateVersionInput(projectId = projectId, name = "1.0.0")
        )
        // Create a task with this version in fix_version_ids.
        transaction {
            connection().useStatement(
                """
                insert into workops.task (
                    key, project_id, task_type_id, status_id, priority_id,
                    summary, reporter_profile_id, fix_version_ids,
                    created_by_principal_id, modified_by_principal_id
                ) values (
                    'AGP-99', '$projectId',
                    '00000000-0000-0000-0000-000000000003',
                    '20000000-0000-0000-0000-000000000001',
                    '30000000-0000-0000-0000-000000000003',
                    'Refs version', '$ownerId', array['${v.id}'::uuid],
                    '$ownerId', '$ownerId'
                )
                """.trimIndent()
            ) { it.execute() }
        }
        assertFailsWith<VersionInUseException> { versionService.delete(v.id) }
    }

    @Test
    fun `component CRUD round-trips`() = withDb {
        val (_, _, projectId) = seed()
        assertFailsWith<WorkOpsValidationException> {
            componentService.create(CreateComponentInput(projectId = projectId, name = " "))
        }
        val c = componentService.create(
            CreateComponentInput(
                projectId = projectId,
                name = "Frontend",
                description = "Web UI",
                assigneeMode = ComponentAssigneeMode.PROJECT_DEFAULT,
            )
        )
        val list = componentService.listByProject(projectId)
        assertEquals(1, list.size)
        assertEquals(c.id, list.first().id)
        componentService.delete(c.id)
        assertEquals(0, componentService.listByProject(projectId).size)
    }

    @Test
    fun `label name normalizes to lowercase kebab-case`() = withDb {
        val ok = labelService.create(CreateLabelInput(name = "high-priority", scope = LabelScope.GLOBAL))
        assertEquals("high-priority", ok.name)

        // Mixed case is normalized — service stores the lowercase form.
        val normalized = labelService.create(CreateLabelInput(name = "Marketing", scope = LabelScope.GLOBAL))
        assertEquals("marketing", normalized.name)

        // Underscores violate kebab-case and are rejected.
        assertFailsWith<WorkOpsValidationException> {
            labelService.create(CreateLabelInput(name = "high_priority", scope = LabelScope.GLOBAL))
        }
        // Whitespace is rejected.
        assertFailsWith<WorkOpsValidationException> {
            labelService.create(CreateLabelInput(name = "high priority", scope = LabelScope.GLOBAL))
        }
    }

    @Test
    fun `label scope must align with parent id`() = withDb {
        val (portfolioId, _, _) = seed()
        // PORTFOLIO without portfolioId — invalid.
        assertFailsWith<WorkOpsValidationException> {
            labelService.create(CreateLabelInput(name = "scoped", scope = LabelScope.PORTFOLIO))
        }
        // GLOBAL with a portfolioId — invalid.
        assertFailsWith<WorkOpsValidationException> {
            labelService.create(
                CreateLabelInput(name = "wrong-scope", scope = LabelScope.GLOBAL, portfolioId = portfolioId)
            )
        }
        // PORTFOLIO with portfolioId — valid.
        val ok = labelService.create(
            CreateLabelInput(name = "marketing", scope = LabelScope.PORTFOLIO, portfolioId = portfolioId)
        )
        assertEquals("marketing", ok.name)
    }

    @Test
    fun `milestone close advances state`() = withDb {
        val (_, programId, _) = seed()
        assertFailsWith<WorkOpsValidationException> {
            milestoneService.create(CreateMilestoneInput(programId = programId, name = " "))
        }
        val m = milestoneService.create(
            CreateMilestoneInput(programId = programId, name = "GA")
        )
        val closed = milestoneService.close(m.id, m.version)
        assertEquals("closed", closed.state.name.lowercase())
        assertNotNull(closed.closedAt)
        assertFailsWith<OptimisticLockFailedException> {
            milestoneService.close(m.id, m.version)
        }
    }
}
