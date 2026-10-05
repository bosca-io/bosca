package bosca.workops.service

import bosca.db.ConnectionConfig
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.migrations.FlywayMigration
import bosca.db.migrations.CoreMigration
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.profile.model.ProfileVisibility
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.workops.migration.WorkOpsMigration
import bosca.workops.model.project.PortfolioInput
import bosca.workops.model.project.ProgramInput
import bosca.workops.model.project.ProjectInput
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.workops.model.spec.CreateSpecContextInput
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.SpecCommentInput
import bosca.workops.model.spec.SpecContextType
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.model.SpecCycleException
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.repository.PortfolioRepositoryImpl
import bosca.workops.repository.ProgramRepositoryImpl
import bosca.workops.repository.ProjectRepositoryImpl
import bosca.workops.repository.ProjectKeyCounterRepositoryImpl
import bosca.workops.repository.PortfolioPermissionRepositoryImpl
import bosca.workops.repository.ProgramPermissionRepositoryImpl
import bosca.workops.repository.ProjectPermissionRepositoryImpl
import bosca.workops.repository.StatusRepositoryImpl
import bosca.workops.repository.PriorityRepositoryImpl
import bosca.workops.repository.WorkflowRepositoryImpl
import bosca.workops.repository.TaskRepositoryImpl
import bosca.workops.repository.TaskHistoryRepositoryImpl
import bosca.workops.repository.TaskTypeRepositoryImpl
import bosca.workops.repository.TaskTypeSchemeRepositoryImpl
import bosca.workops.repository.TaskPermissionRepositoryImpl
import bosca.workops.repository.TaskFieldConfigurationRepositoryImpl
import bosca.workops.repository.SpecRepositoryImpl
import bosca.workops.repository.SpecHistoryRepositoryImpl
import bosca.workops.repository.SpecKeyCounterRepositoryImpl
import bosca.workops.repository.SpecContextRepositoryImpl
import bosca.workops.repository.SpecTaskGenerationRepositoryImpl
import bosca.workops.repository.SpecCommentRepositoryImpl
import bosca.workops.repository.SpecPermissionRepositoryImpl
import bosca.workops.repository.RequirementRepositoryImpl
import bosca.workops.repository.RequirementHistoryRepositoryImpl
import bosca.workops.repository.RequirementKeyCounterRepositoryImpl
import bosca.workops.repository.RequirementPermissionRepositoryImpl
import io.mockk.mockk
import bosca.di.asProvider
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

class SpecIntegrationTest {

    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("bosca_workops_spec_test")
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
                key = "workops-spec-test",
            )
        )
        private var schemaInitialized = false

        @AfterClass
        @JvmStatic
        fun shutdown() {
            runBlocking { pool.close() }
            postgres.stop()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val securityService = mockk<SecurityService>(relaxed = true)

    private val portfolioRepo = PortfolioRepositoryImpl()
    private val portfolioPermissionRepo = PortfolioPermissionRepositoryImpl()
    private val programRepo = ProgramRepositoryImpl()
    private val programPermissionRepo = ProgramPermissionRepositoryImpl()
    private val projectRepo = ProjectRepositoryImpl()
    private val projectPermissionRepo = ProjectPermissionRepositoryImpl()
    private val keyCounterRepo = ProjectKeyCounterRepositoryImpl()
    private val statusRepo = StatusRepositoryImpl()
    private val priorityRepo = PriorityRepositoryImpl()
    private val workflowRepo = WorkflowRepositoryImpl()
    private val workflowQueryRepo = WorkflowQueryRepositoryImpl()
    private val taskRepo = TaskRepositoryImpl()
    private val taskHistoryRepo = TaskHistoryRepositoryImpl()
    private val taskTypeRepo = TaskTypeRepositoryImpl()
    private val taskTypeSchemeRepo = TaskTypeSchemeRepositoryImpl()
    private val taskPermissionRepo = TaskPermissionRepositoryImpl()
    private val fieldConfigRepo = TaskFieldConfigurationRepositoryImpl()

    private val specRepo = SpecRepositoryImpl()
    private val specHistoryRepo = SpecHistoryRepositoryImpl()
    private val specKeyCounterRepo = SpecKeyCounterRepositoryImpl()
    private val specContextRepo = SpecContextRepositoryImpl()
    private val specTaskGenRepo = SpecTaskGenerationRepositoryImpl()
    private val specCommentRepo = SpecCommentRepositoryImpl()
    private val specPermissionRepo = SpecPermissionRepositoryImpl()
    private val requirementRepo = RequirementRepositoryImpl()
    private val requirementHistoryRepo = RequirementHistoryRepositoryImpl()
    private val requirementKeyCounterRepo = RequirementKeyCounterRepositoryImpl()
    private val requirementPermissionRepo = RequirementPermissionRepositoryImpl()

    private val groupEvaluator = bosca.security.service.GroupEvaluator(securityService)
    private val portfolioService = PortfolioServiceImpl(portfolioRepo, portfolioPermissionRepo, securityService)
    private val portfolioPermissionEvaluator = PortfolioPermissionEvaluator(portfolioService, securityService, groupEvaluator)
    private val programService = ProgramServiceImpl(programRepo, portfolioRepo, programPermissionRepo, portfolioPermissionRepo, portfolioPermissionEvaluator)
    private val programPermissionEvaluator = ProgramPermissionEvaluator(programService, securityService, groupEvaluator)
    private val projectService = ProjectServiceImpl(projectRepo, programRepo, keyCounterRepo, projectPermissionRepo, programPermissionRepo, programPermissionEvaluator)
    private val projectPermissionEvaluator = ProjectPermissionEvaluator(projectService, securityService, groupEvaluator)
    private val workflowSchemeRepo = bosca.workops.repository.WorkflowSchemeRepositoryImpl()
    private val workflowService = WorkflowServiceImpl(workflowRepo, workflowSchemeRepo, projectService, workflowQueryRepo)
    private val affectedRepo = bosca.workops.repository.TaskAffectedProjectRepositoryImpl()
    private val affectedService = TaskAffectedProjectServiceImpl(affectedRepo)

    private val taskService = TaskServiceImpl(
        taskRepository = taskRepo,
        taskHistoryRepository = taskHistoryRepo,
        projectService = projectService,
        programService = programService,
        taskTypeService = TaskTypeServiceImpl(taskTypeRepo),
        taskTypeSchemeService = TaskTypeSchemeServiceImpl(taskTypeSchemeRepo),
        statusService = StatusServiceImpl(statusRepo),
        priorityService = PriorityServiceImpl(priorityRepo),
        workflowService = workflowService,
        workflowEvaluator = WorkflowEvaluator(),
        customFieldService = TaskCustomFieldServiceImpl(fieldConfigRepo),
        json = json,
        taskPermissionRepository = taskPermissionRepo,
        affectedProjectService = affectedService,
        requirementServiceProvider = mockk<RequirementService>(relaxed = true).asProvider(),
        metadataService = mockk(relaxed = true),
        sprintService = mockk(relaxed = true),
        projectPermissionEvaluator = projectPermissionEvaluator,
    )

    private lateinit var specService: SpecServiceImpl

    private lateinit var requirementService: RequirementServiceImpl

    private val specCommentService = SpecCommentServiceImpl(
        commentRepository = specCommentRepo,
        specRepository = specRepo,
        specHistoryRepository = specHistoryRepo,
        json = json,
    )

    private val specContextService = SpecContextServiceImpl(
        contextRepository = specContextRepo,
        specRepository = specRepo,
        specHistoryRepository = specHistoryRepo,
        json = json,
    )

    private val ownerId = UUID.parse("11111111-1111-1111-1111-111111111111")
    private val principalId = UUID.parse("22222222-2222-2222-2222-222222222222")
    private val profileId = UUID.parse("33333333-3333-3333-3333-333333333333")
    private val metadataId = UUID.random()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        specService = SpecServiceImpl(
            specRepository = specRepo,
            specHistoryRepository = specHistoryRepo,
            keyCounterRepository = specKeyCounterRepo,
            projectRepository = projectRepo,
            programRepository = programRepo,
            statusRepository = statusRepo,
            workflowRepository = workflowRepo,
            specPermissionRepository = specPermissionRepo,
            requirementRepository = requirementRepo,
            taskService = taskService,
            metadataService = mockk(relaxed = true),
            documentService = mockk(relaxed = true),
            specTaskGenerationRepository = specTaskGenRepo,
            projectPermissionEvaluator = projectPermissionEvaluator,
            programPermissionEvaluator = programPermissionEvaluator,
            json = json,
        )
        val specPermissionEvaluator = SpecPermissionEvaluator(specService, securityService, groupEvaluator)
        val taskPermissionEvaluator = TaskPermissionEvaluator(taskService, securityService, groupEvaluator)
        requirementService = RequirementServiceImpl(
            requirementRepository = requirementRepo,
            requirementHistoryRepository = requirementHistoryRepo,
            keyCounterRepository = requirementKeyCounterRepo,
            projectRepository = projectRepo,
            specRepository = specRepo,
            taskRepository = taskRepo,
            taskService = taskService,
            metadataService = mockk(relaxed = true),
            documentService = mockk(relaxed = true),
            statusRepository = statusRepo,
            priorityRepository = priorityRepo,
            workflowRepository = workflowRepo,
            requirementPermissionRepository = requirementPermissionRepo,
            specPermissionEvaluator = specPermissionEvaluator,
            taskPermissionEvaluator = taskPermissionEvaluator,
            json = json,
        )
        if (!schemaInitialized) {
            runBlocking {
                FlywayMigration(pool).migrate(listOf(CoreMigration(), WorkOpsMigration()))
            }
            schemaInitialized = true
        }
        withDb {
            transaction {
                connection().useStatement("DELETE FROM workops.spec_comment_likes") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_comment") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_context") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_task_generation") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_permissions") { it.execute() }
                connection().useStatement("DELETE FROM workops.requirement_permissions") { it.execute() }
                connection().useStatement("DELETE FROM workops.requirement_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.requirement") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_history") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec_key_counter") { it.execute() }
                connection().useStatement("DELETE FROM workops.requirement_key_counter") { it.execute() }
                connection().useStatement("DELETE FROM workops.spec") { it.execute() }
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

    private suspend fun seedProject(): UUID {
        val portfolio = portfolioService.create(
            PortfolioInput(key = "SPC", name = "Spec Test", description = null, ownerProfileId = ownerId)
        )
        val program = programService.create(
            ProgramInput(portfolioId = portfolio.id, key = "SPCP", name = "Spec Program",
                description = null, ownerProfileId = ownerId)
        )
        val project = projectService.create(
            ProjectInput(programId = program.id, key = "SPC", name = "Spec Project",
                description = null, ownerProfileId = ownerId)
        )
        return project.id
    }

    private suspend fun createSpec(projectId: UUID): bosca.workops.model.spec.Spec =
        specService.create(
            input = CreateSpecInput(metadataId = UUID.random(), projectId = projectId),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )

    // ── Seed data ──────────────────────────────────────────────

    @Test
    fun `V26 seeds Spec Document Template into document_templates`() = withDb {
        // Real DB check that the seed row referenced by SpecServiceImpl.SPEC_TEMPLATE_ID
        // is actually present after migrations run. If V26 ever stops creating this row,
        // workops_spec auto-create-metadata will violate the documents.template_metadata_id FK.
        transaction {
            connection().useStatement(
                "select 1 from document_templates where metadata_id = ?::uuid and version = ?"
            ) { stmt ->
                stmt.setString(1, SpecServiceImpl.SPEC_TEMPLATE_ID.toString())
                stmt.setInt(2, SpecServiceImpl.SPEC_TEMPLATE_VERSION)
                val seedPresent = stmt.executeQuery().use { it.next() }
                assertTrue(
                    seedPresent,
                    "V26 migration must seed a document_templates row at " +
                    "(${SpecServiceImpl.SPEC_TEMPLATE_ID}, v${SpecServiceImpl.SPEC_TEMPLATE_VERSION}). " +
                    "Without it, spec creation violates documents_template_metadata_id_fkey."
                )
            }
        }
    }

    @Test
    fun `V26 seeds Requirement Document Template into document_templates`() = withDb {
        // Same guarantee for the Requirement template that requirement auto-create-metadata depends on.
        transaction {
            connection().useStatement(
                "select 1 from document_templates where metadata_id = ?::uuid and version = ?"
            ) { stmt ->
                stmt.setString(1, RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID.toString())
                stmt.setInt(2, RequirementServiceImpl.REQUIREMENT_TEMPLATE_VERSION)
                val seedPresent = stmt.executeQuery().use { it.next() }
                assertTrue(
                    seedPresent,
                    "V26 migration must seed a document_templates row at " +
                    "(${RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID}, v${RequirementServiceImpl.REQUIREMENT_TEMPLATE_VERSION}). " +
                    "Without it, requirement creation violates documents_template_metadata_id_fkey."
                )
            }
        }
    }

    // ── Spec CRUD ──────────────────────────────────────────────

    @Test
    fun `create spec mints key and persists`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        assertEquals("SPC-SPEC-1", spec.key, "first spec key should be SPC-SPEC-1")
        assertEquals(projectId, spec.projectId)
        assertEquals(profileId, spec.ownerProfileId)
        assertEquals(0, spec.version)

        val fetched = specService.getById(spec.id)
        assertNotNull(fetched, "spec should be fetchable by id")
        assertEquals(spec.key, fetched.key)
    }

    @Test
    fun `second spec in same project gets SPC-SPEC-2`() = withDb {
        val projectId = seedProject()
        createSpec(projectId)
        val spec2 = createSpec(projectId)
        assertEquals("SPC-SPEC-2", spec2.key)
    }

    @Test
    fun `update spec bumps version and persists`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)
        val newOwner = UUID.random()

        val updated = specService.update(
            id = spec.id,
            input = UpdateSpecInput(ownerProfileId = newOwner, expectedVersion = 0),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(1, updated.version, "version should bump to 1")
        assertEquals(newOwner, updated.ownerProfileId)
    }

    @Test
    fun `soft delete and restore round-trip`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        val deleted = specService.softDelete(spec.id, 0L, principalId, profileId)
        assertNotNull(deleted.deletedAt, "deletedAt should be set")
        assertNull(specService.getById(spec.id), "getById should not return soft-deleted spec")

        val restored = specService.restore(spec.id, deleted.version.toLong(), principalId, profileId)
        assertNull(restored.deletedAt, "deletedAt should be cleared after restore")
        assertNotNull(specService.getById(spec.id), "spec should be fetchable again after restore")
    }

    @Test
    fun `spec history tracks create and update`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)
        specService.update(
            id = spec.id,
            input = UpdateSpecInput(ownerProfileId = UUID.random(), expectedVersion = 0),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        val history = specService.listHistory(spec.id, 0, 50)
        assertEquals(2, history.size, "should have create + update entries")
    }

    // ── Requirements ───────────────────────────────────────────

    @Test
    fun `create requirement attached to spec`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        val req = requirementService.create(
            input = CreateRequirementInput(
                metadataId = UUID.random(),
                parentType = RequirementParent.SPEC,
                parentId = spec.id,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals("SPC-REQ-1", req.key)
        assertEquals(RequirementParent.SPEC, req.parentType)
        assertEquals(spec.id, req.parentId)
    }

    @Test
    fun `create requirement attached to task`() = withDb {
        val projectId = seedProject()
        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "epic task"),
            principalId, profileId, profileId,
        )

        val req = requirementService.create(
            input = CreateRequirementInput(
                metadataId = UUID.random(),
                parentType = RequirementParent.TASK,
                parentId = task.id,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(RequirementParent.TASK, req.parentType)
        assertEquals(task.id, req.parentId)
    }

    @Test
    fun `list requirements by spec`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )
        requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )

        val reqs = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 50)
        assertEquals(2, reqs.size)
        assertEquals(2L, requirementService.countByParent(RequirementParent.SPEC, spec.id))
    }

    @Test
    fun `update requirement and verify history`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)
        val req = requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )

        requirementService.update(
            req.id,
            UpdateRequirementInput(sortOrder = 5, expectedVersion = 0),
            principalId, profileId,
        )

        val history = requirementService.listHistory(req.id, 0, 50)
        assertEquals(2, history.size, "should have create + update entries")

        val updated = requirementService.getById(req.id)!!
        assertEquals(5, updated.sortOrder)
    }

    // ── Spec Context ───────────────────────────────────────────

    @Test
    fun `add and remove spec context`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        val ctx = specContextService.add(
            specId = spec.id,
            input = CreateSpecContextInput(
                contextType = SpecContextType.GIT_RESOURCE,
                targetId = UUID.random().toString(),
                label = "main branch",
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(SpecContextType.GIT_RESOURCE, ctx.contextType)
        assertEquals("main branch", ctx.label)

        val contexts = specContextService.listBySpec(spec.id)
        assertEquals(1, contexts.size)

        specContextService.remove(spec.id, ctx.id, principalId, profileId)
        assertTrue(specContextService.listBySpec(spec.id).isEmpty(), "context should be removed")
    }

    @Test
    fun `multiple context types on one spec`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        specContextService.add(spec.id,
            CreateSpecContextInput(SpecContextType.PROFILE, profileId.toString(), "owner"),
            principalId, profileId)
        specContextService.add(spec.id,
            CreateSpecContextInput(SpecContextType.METADATA, UUID.random().toString()),
            principalId, profileId)
        specContextService.add(spec.id,
            CreateSpecContextInput(SpecContextType.EXTERNAL_URI, "https://docs.example.com"),
            principalId, profileId)

        val all = specContextService.listBySpec(spec.id)
        assertEquals(3, all.size)

        val profileContexts = specContextService.listBySpecAndType(spec.id, SpecContextType.PROFILE)
        assertEquals(1, profileContexts.size)
    }

    // ── Spec Comments ──────────────────────────────────────────

    @Test
    fun `add comment and verify in list`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        val comment = specCommentService.add(
            specId = spec.id,
            input = SpecCommentInput(content = "Looks good to me"),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertTrue(comment.id > 0)
        assertEquals("Looks good to me", comment.content)

        val comments = specCommentService.listManager(spec.id, 0, 50)
        assertEquals(1, comments.size)
    }

    @Test
    fun `comment adds history entry to spec`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        specCommentService.add(
            specId = spec.id,
            input = SpecCommentInput(content = "first review note"),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        val history = specService.listHistory(spec.id, 0, 50)
        assertEquals(2, history.size, "create + comment-add")
    }

    @Test
    fun `like and unlike comment`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)
        val comment = specCommentService.add(
            spec.id, SpecCommentInput(content = "nice"), principalId, profileId,
        )

        specCommentService.like(spec.id, comment.id, profileId)
        val afterLike = specCommentService.getManager(spec.id, comment.id)!!
        assertEquals(1, afterLike.likes)

        specCommentService.unlike(spec.id, comment.id, profileId)
        val afterUnlike = specCommentService.getManager(spec.id, comment.id)!!
        assertEquals(0, afterUnlike.likes)
    }

    // ── End-to-end flow ────────────────────────────────────────

    @Test
    fun `full spec lifecycle - create, add requirements, add context, comment, update, delete`() = withDb {
        val projectId = seedProject()

        val spec = createSpec(projectId)
        assertEquals("SPC-SPEC-1", spec.key)

        val req1 = requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )
        val req2 = requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )
        assertEquals(2L, requirementService.countByParent(RequirementParent.SPEC, spec.id))

        specContextService.add(spec.id,
            CreateSpecContextInput(SpecContextType.GIT_RESOURCE, UUID.random().toString(), "repo"),
            principalId, profileId)

        specCommentService.add(spec.id, SpecCommentInput(content = "review ready"), principalId, profileId)

        val updated = specService.update(
            spec.id,
            UpdateSpecInput(ownerProfileId = UUID.random(), expectedVersion = 0),
            principalId, profileId,
        )
        assertEquals(1, updated.version)

        val history = specService.listHistory(spec.id, 0, 50)
        assertTrue(history.size >= 4, "should have create + context + comment + update = at least 4 entries, got ${history.size}")

        val deleted = specService.softDelete(spec.id, updated.version.toLong(), principalId, profileId)
        assertNotNull(deleted.deletedAt)

        val reqs = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 50)
        assertEquals(2, reqs.size, "requirements should survive spec soft delete")
    }

    // ── Hierarchy ──────────────────────────────────────────────

    @Test
    fun `parent-child spec relationship with rollup counts`() = withDb {
        val projectId = seedProject()
        val parent = createSpec(projectId)
        val child1 = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = parent.id),
            principalId, profileId, profileId,
        )
        val child2 = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = parent.id),
            principalId, profileId, profileId,
        )

        val refreshedParent = specService.getById(parent.id)!!
        assertEquals(2, refreshedParent.childCount, "parent should have 2 children")
        assertEquals(0, refreshedParent.childDoneCount, "no children are done yet")

        val children = specService.listChildren(parent.id, 0, 50)
        assertEquals(2, children.size)
    }

    @Test
    fun `soft delete child decrements parent childCount`() = withDb {
        val projectId = seedProject()
        val parent = createSpec(projectId)
        val child = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = parent.id),
            principalId, profileId, profileId,
        )

        assertEquals(1, specService.getById(parent.id)!!.childCount)

        specService.softDelete(child.id, child.version.toLong(), principalId, profileId)
        assertEquals(0, specService.getById(parent.id)!!.childCount, "childCount should decrement on soft delete")

        specService.restore(child.id, (child.version + 1).toLong(), principalId, profileId)
        assertEquals(1, specService.getById(parent.id)!!.childCount, "childCount should increment on restore")
    }

    @Test
    fun `re-parenting updates both old and new parent counts`() = withDb {
        val projectId = seedProject()
        val parentA = createSpec(projectId)
        val parentB = createSpec(projectId)
        val child = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = parentA.id),
            principalId, profileId, profileId,
        )

        assertEquals(1, specService.getById(parentA.id)!!.childCount)
        assertEquals(0, specService.getById(parentB.id)!!.childCount)

        specService.update(child.id, UpdateSpecInput(parentSpecId = parentB.id, expectedVersion = 0), principalId, profileId)

        assertEquals(0, specService.getById(parentA.id)!!.childCount, "old parent should lose child")
        assertEquals(1, specService.getById(parentB.id)!!.childCount, "new parent should gain child")
    }

    // ── Cycle Detection ────────────────────────────────────────

    @Test
    fun `cycle detection prevents self-parenting`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        assertFailsWith<SpecCycleException> {
            specService.update(spec.id, UpdateSpecInput(parentSpecId = spec.id, expectedVersion = 0), principalId, profileId)
        }
    }

    @Test
    fun `cycle detection prevents indirect cycle`() = withDb {
        val projectId = seedProject()
        val a = createSpec(projectId)
        val b = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = a.id),
            principalId, profileId, profileId,
        )
        val c = specService.create(
            CreateSpecInput(metadataId = UUID.random(), projectId = projectId, parentSpecId = b.id),
            principalId, profileId, profileId,
        )

        assertFailsWith<SpecCycleException> {
            specService.update(a.id, UpdateSpecInput(parentSpecId = c.id, expectedVersion = 0), principalId, profileId)
        }
    }

    // ── Task Generation ────────────────────────────────────────

    @Test
    fun `generate tasks creates one task per requirement`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)

        requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )
        requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )

        val generation = specService.generateTasks(
            specId = spec.id,
            metadataVersion = 1,
            source = GenerationSource.MANUAL,
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(2, generation.generatedTaskIds.size, "should generate one task per requirement")
        assertEquals(GenerationSource.MANUAL, generation.source)
        assertEquals(spec.id, generation.specId)
    }

    // ── Requirement Re-parenting ───────────────────────────────

    @Test
    fun `move requirement from spec to task`() = withDb {
        val projectId = seedProject()
        val spec = createSpec(projectId)
        val task = taskService.create(
            CreateTaskInput(projectId = projectId, summary = "target task"),
            principalId, profileId, profileId,
        )
        val req = requirementService.create(
            CreateRequirementInput(metadataId = UUID.random(), parentType = RequirementParent.SPEC, parentId = spec.id),
            principalId, profileId,
        )

        assertEquals(RequirementParent.SPEC, req.parentType)

        val moved = requirementService.moveToParent(
            id = req.id,
            newParentType = RequirementParent.TASK,
            newParentId = task.id,
            expectedVersion = req.version,
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(RequirementParent.TASK, moved.parentType)
        assertEquals(task.id, moved.parentId)

        val specsReqs = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 50)
        assertEquals(0, specsReqs.size, "spec should have no requirements after move")

        val taskReqs = requirementService.listByParent(RequirementParent.TASK, task.id, 0, 50)
        assertEquals(1, taskReqs.size, "task should have the moved requirement")
    }
}
