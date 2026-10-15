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
import io.mockk.coEvery
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataInput
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.model.DocumentInput
import bosca.content.metadata.model.Document
import bosca.content.metadata.service.MetadataService
import bosca.content.metadata.service.DocumentService
import bosca.documents.Content
import bosca.documents.MarkdownConverter
import bosca.workops.model.spec.fingerprint
import bosca.workops.model.spec.CreateSpecPlanInput
import bosca.workops.model.spec.RequirementContentInput
import bosca.workops.model.spec.SetRequirementContentInput
import bosca.workops.repository.PlanCreationRepositoryImpl
import bosca.di.asProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import bosca.test.resources.SharedPostgreSQLContainer
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecIntegrationTest {
    @Test
    fun `concurrent document and requirement content writes finish without deadlock`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val spec = planService().create(CreateSpecPlanInput(UUID.random(), projectId, "Review probe", "Scope", listOf(
            RequirementContentInput("Original", "Body", listOf("Pass")),
        )), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 100).single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val metadata = assertNotNull(metadataService.getById(requirement.metadataId))
        val input = SetRequirementContentInput(RequirementContentInput("Replacement", "Changed body", listOf("New check")), requirement.version, task.version, metadata.version)
        val replacementReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writerPid = kotlinx.coroutines.CompletableDeferred<Int>()
        val releaseReplacement = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { metadataService.setDocument(any(), any(), any()) } coAnswers {
            replacementReady.complete(Unit)
            releaseReplacement.await()
            writeDocument(firstArg(), arg(1)); Unit
        }
        suspend fun outcome(block: suspend () -> Unit): Exception? = try { block(); null }
            catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
            catch (failure: Exception) { failure }
        kotlinx.coroutines.coroutineScope {
            val replacement = async { outcome { inConnection { requirementService.setContent(requirement.id, input, principalId, profileId) } } }
            val directWriter = async {
                replacementReady.await()
                outcome { inConnection { transaction {
                    writerPid.complete(connection().useStatement("select pg_backend_pid()") { it.executeQuery().use { rows -> rows.next(); rows.getInt(1) } })
                    writeDocument(metadata, DocumentInput(title = "Concurrent direct edit", content = MarkdownConverter.fromMarkdown("Direct body")))
                } } }
            }
            try {
                val pid = writerPid.await()
                kotlinx.coroutines.withTimeout(10_000) {
                    inConnection {
                        while (!directWriter.isCompleted && !connection().useStatement("select cardinality(pg_blocking_pids(?)) > 0") {
                            it.setInt(1, pid); it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                        }) kotlinx.coroutines.yield()
                    }
                }
            } finally { releaseReplacement.complete(Unit) }
            val failures = listOfNotNull(replacement.await(), directWriter.await())
            assertTrue(failures.isEmpty(), "Concurrent document writes failed: $failures")
            assertEquals("Replacement", documentService.getDocument(requirement.metadataId, metadata.version)?.title)
            assertEquals(input.content.body(), taskService.getById(task.id)?.descriptionMarkdown)
        }
    }



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

    private lateinit var metadataService: MetadataService
    private lateinit var documentService: DocumentService
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
        // Integration tests must execute the real transaction helper, regardless of unit-test order.
        io.mockk.unmockkStatic("bosca.db.ConnectionManagerKt")
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { json }
        provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        metadataService = mockk(relaxed = true)
        documentService = mockk(relaxed = true)
        specService = newSpecService(mockk(relaxed = true))
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
            metadataService = metadataService,
            documentService = documentService,
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
                connection().useStatement("DELETE FROM workops.plan_creation") { it.execute() }
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

    private suspend fun <T> inConnection(block: suspend () -> T): T {
        val manager = pool.connection()
        try { return withContext(manager.asCoroutineContext()) { block() } }
        finally { withContext(NonCancellable) { manager.release() } }
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

    /** Content boundary fixture persists actual metadata/document rows in the request transaction. */
    private fun persistentContent() {
        coEvery { metadataService.add(any(), any(), any()) } coAnswers {
            val input = arg<MetadataInput>(2)
            val id = UUID.random()
            connection().useStatement("insert into metadata (id, name, content_type, language_tag) values (?::uuid, ?, ?, 'en')") {
                it.setString(1, id.toString()); it.setString(2, input.name); it.setString(3, input.contentType); it.executeUpdate()
            }
            val metadata = Metadata(id = id, name = input.name, type = MetadataType.STANDARD,
                contentType = input.contentType, contentLength = null, languageTag = "en", workflowStateId = "pending")
            input.document?.let { writeDocument(metadata, it) }
            metadata
        }
        coEvery { metadataService.getById(any()) } coAnswers {
            val id = firstArg<UUID>()
            connection().useStatement("select name, version, content_type, deleted from metadata where id = ?::uuid") {
                it.setString(1, id.toString())
                it.executeQuery().use { rows ->
                    if (!rows.next()) null else Metadata(id = id, name = rows.getString(1), version = rows.getInt(2),
                        type = MetadataType.STANDARD, contentType = rows.getString(3), contentLength = null, languageTag = "en", workflowStateId = "pending", deleted = rows.getBoolean(4))
                }
            }
        }
        coEvery { metadataService.getByIds(any()) } coAnswers { firstArg<List<UUID>>().mapNotNull { metadataService.getById(it) } }
        coEvery { documentService.getDocumentFresh(any(), any()) } coAnswers { documentService.getDocument(firstArg(), secondArg()) }
        coEvery { metadataService.setDocument(any(), any(), any()) } coAnswers {
            writeDocument(firstArg(), arg(1)); Unit
        }
        coEvery { documentService.getDocument(any(), any()) } coAnswers {
            val id = firstArg<UUID>(); val version = arg<Int>(1)
            connection().useStatement("select title, content, template_metadata_id, template_metadata_version from documents where metadata_id = ?::uuid and version = ?") {
                it.setString(1, id.toString()); it.setInt(2, version)
                it.executeQuery().use { rows ->
                    if (!rows.next()) null else Document(id, version, rows.getString(3)?.let(UUID::parse),
                        rows.getInt(4).takeUnless { rows.wasNull() }, rows.getString(1), json.decodeFromString(Content.serializer(), rows.getString(2)))
                }
            }
        }
    }

    private suspend fun writeDocument(metadata: Metadata, input: DocumentInput) {
        connection().useStatement("insert into documents (metadata_id, version, title, content, template_metadata_id, template_metadata_version) values (?::uuid, ?, ?, ?::jsonb, ?::uuid, ?) on conflict (metadata_id, version) do update set title = excluded.title, content = excluded.content") {
            it.setString(1, metadata.id.toString()); it.setInt(2, metadata.version); it.setString(3, input.title)
            it.setString(4, json.encodeToString(Content.serializer(), input.content ?: Content()))
            it.setString(5, input.templateMetadataId?.toString()); it.setObject(6, input.templateMetadataVersion); it.executeUpdate()
        }
    }


    @Test
    fun `guidance generated provider persists configuration document and resource atomically and reads across requests`() = runBlocking {
        persistentContent()
        val service = realGuidance()
        val resourceService = bosca.di.provide<bosca.ai.agents.service.AgentResourceService>()
        val guide = bosca.workops.model.guidance.WorkOpsGuideInput(bosca.workops.model.guidance.WorkOpsGuidancePhase.IMPLEMENTATION,
            "integration-tests", "Use for integration verification", "# Integration contract\n\nExercise rollback.")
        val project = UUID.random()
        val baseline = inConnection { bosca.cache.withRequestCache { service.effective(project) } }
        val saved = inConnection { bosca.cache.withRequestCache { service.saveCurrent(project, bosca.workops.model.guidance.WorkOpsGuidanceInput(guides = listOf(guide))) } }
        val reloaded = inConnection { bosca.cache.withRequestCache { service.effective(project) } }
        assertEquals(saved.revision, reloaded.revision)
        assertTrue(reloaded.guides.single { it.phase == guide.phase }.markdown.contains("Exercise rollback."))
        assertTrue(reloaded.guides.single { it.phase == guide.phase }.metadataId != null)
        inConnection {
            val body = reloaded.guides.single { it.phase == guide.phase }
            val resource = assertNotNull(resourceService.get(assertNotNull(body.resourceId)))
            assertEquals(body.metadataId, resource.documentMetadataId)
            assertEquals(body.documentVersion, resource.documentVersion)
            val before = connection().useStatement("select count(*) from metadata") { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } }
            assertFailsWith<IllegalStateException> {
                bosca.cache.withRequestCache {
                    transaction {
                        service.saveCurrent(project, bosca.workops.model.guidance.WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = "# Rolled back"))))
                        error("Abort enclosing transaction")
                    }
                }
            }
            val after = connection().useStatement("select count(*) from metadata") { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } }
            assertEquals(before, after)
        }
        assertEquals(saved.revision, inConnection { bosca.cache.withRequestCache { service.effective(project) } }.revision)
        val contenders = kotlinx.coroutines.coroutineScope {
            listOf("# Editor A", "# Editor B").map { body -> async {
                inConnection { bosca.cache.withRequestCache {
                    try { service.save(project, bosca.workops.model.guidance.WorkOpsGuidanceInput(guides = listOf(guide.copy(markdown = body))), saved.revision) }
                    catch (_: bosca.workops.model.WorkOpsValidationException) { null }
                } }
            } }.map { it.await() }
        }
        val winner = contenders.filterNotNull().single()
        assertEquals(winner.revision, inConnection { bosca.cache.withRequestCache { service.effective(project) } }.revision)
        inConnection { bosca.cache.withRequestCache {
            assertFailsWith<bosca.workops.model.WorkOpsValidationException> { service.reset(project, saved.revision) }
        } }
        assertEquals(baseline.revision, inConnection { bosca.cache.withRequestCache { service.resetCurrent(project) } }.revision)
        inConnection { assertNull(resourceService.getByKey("workops.guidance.$project.implementation")) }
    }

    @Test
    fun `guidance uses configuration request caching and reloads saved changes in a new request`() = runBlocking {
        val service = realGuidance()
        val configurations = bosca.di.provide<bosca.configuration.service.ConfigurationService>()
        val project = UUID.random()
        val originalInput = bosca.workops.model.guidance.WorkOpsGuidanceInput(
            policy = bosca.workops.model.guidance.WorkOpsGuidancePolicy(minimumLineCoverage = 100.0))
        val original = inConnection { bosca.cache.withRequestCache { service.saveCurrent(project, originalInput) } }

        val updated = inConnection { bosca.cache.withRequestCache {
            val configuration = requireNotNull(configurations.getByKey("workops.guidance.$project"))
            val cachedValue = requireNotNull(configurations.getValue(configuration.id))
            val saved = bosca.cache.withRequestCache {
                service.save(project, originalInput.copy(policy = original.policy.copy(minimumLineCoverage = 99.0)), original.revision)
            }
            assertNotEquals(original.revision, saved.revision)
            assertEquals(cachedValue, configurations.getValue(configuration.id))
            assertEquals(original, service.effective(project))
            saved
        } }

        assertEquals(updated, inConnection { bosca.cache.withRequestCache { service.effective(project) } })
    }

    private suspend fun realGuidance(): WorkOpsGuidanceService {
        val cache = GuidanceTestCacheManager()
        provides<bosca.cache.CacheManager>(singleton = true) { cache }
        provides<bosca.cache.RequestCacheSerializer>(singleton = true) { bosca.cache.RequestCacheSerializerImpl(json) }
        val encryption = mockk<bosca.security.encryption.EncryptionService>()
        coEvery { encryption.encrypt(any(), any()) } coAnswers {
            bosca.security.encryption.EncryptionService.Encrypted(byteArrayOf(1), firstArg<ByteArray>())
        }
        coEvery { encryption.decrypt(any(), any()) } coAnswers { firstArg<bosca.security.encryption.EncryptionService.Encrypted>().data }
        val configurationService = bosca.configuration.service.ConfigurationServiceImpl(
            bosca.configuration.repository.ConfigurationRepositoryImpl(),
            bosca.configuration.repository.ConfigurationValueRepositoryImpl(),
            bosca.configuration.repository.ConfigurationPermissionRepositoryImpl(), encryption)
        val resourceService = bosca.ai.agents.service.AgentResourceServiceImpl(bosca.ai.agents.repository.AgentResourceRepositoryImpl())
        provides<bosca.configuration.service.ConfigurationService>(singleton = true) { configurationService }
        provides<bosca.ai.agents.service.AgentResourceService>(singleton = true) { resourceService }
        provides<MetadataService>(singleton = true) { metadataService }
        provides<DocumentService>(singleton = true) { documentService }
        provides<bosca.workops.repository.WorkOpsGuidanceRepository>(singleton = true) { bosca.workops.repository.WorkOpsGuidanceRepositoryImpl() }

        inConnection {
            connection().useStatement("create schema if not exists ai") { it.execute() }
            val migration = requireNotNull(javaClass.getResourceAsStream("/db/migrations/V9__agent_resources.sql")).use { it.readBytes().toString(Charsets.UTF_8) }
            connection().useStatement("drop table if exists ai.agent_resources") { it.execute() }
            connection().useStatement(migration) { it.execute() }
            connection().useStatement("delete from configurations where key like 'workops.guidance.%'") { it.execute() }
        }
        val service = WorkOpsGuidanceServiceImplProvider().get()
        provides<WorkOpsGuidanceService>(singleton = true) { service }
        return service
    }

    @Test
    fun `task progress preserves requirement order and authored positions`() = withDb {
        persistentContent()
        val project = seedProject()
        val plan = planService().create(CreateSpecPlanInput(UUID.random(), project, "Two requirements", "Scope", listOf(
            RequirementContentInput("Ordering first", "First body", listOf("First result")),
            RequirementContentInput("Ordering second", "Second body", listOf("Second result")),
        )), principalId, profileId)
        val before = requirementService.listByParent(RequirementParent.SPEC, plan.id, 0, 100)
        assertEquals(listOf(0, 0), before.map { it.sortOrder })
        val done = statusRepo.getAll().first { it.category == bosca.workops.model.workflow.StatusCategory.DONE }
        requirementService.syncStatusFromTask(assertNotNull(before.last().taskId), done.id, principalId, profileId)
        val after = requirementService.listByParent(RequirementParent.SPEC, plan.id, 0, 100)
        assertEquals(before.first().id, after.first().id)
        assertEquals(before.map { it.id }, after.map { it.id })
        assertEquals(before.associate { it.id to it.sortOrder }, after.associate { it.id to it.sortOrder })
    }


    @Test
    fun `completion rejects evidence changed concurrently after validation`() = runBlocking {
        inConnection {
        persistentContent()
        val guidance = realGuidance()
        provides<bosca.workops.repository.SpecRepository>(singleton = true) { specRepo }
        provides<TaskService>(singleton = true) { taskService }
        provides<bosca.workops.repository.StatusRepository>(singleton = true) { statusRepo }
        provides<SpecCommentService>(singleton = true) { specCommentService }
        provides<SpecContextService>(singleton = true) { specContextService }
        provides<bosca.workops.repository.SpecTaskGenerationRepository>(singleton = true) { specTaskGenRepo }
        val completion = SpecCompletionServiceImplProvider().get()
        specService = newSpecService(completion)
        val project = seedProject()
        var first = planService().create(CreateSpecPlanInput(UUID.random(), project, "Verified plan", "Scope", listOf(RequirementContentInput("Behavior", "Implementation", listOf("Regression passes")))), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, first.id, 0, 100).single()
        val done = statusRepo.getAll().first { it.category == bosca.workops.model.workflow.StatusCategory.DONE }
        val states = workflowRepo.listStates(first.workflowId)
        val doneState = states.first { it.statusId == done.id }
        val transition = workflowRepo.listTransitions(first.workflowId).first { it.toStateId == doneState.id }
        val inProgress = statusRepo.getAll().first { it.category == bosca.workops.model.workflow.StatusCategory.IN_PROGRESS }
        val progressState = states.first { it.statusId == inProgress.id }
        val start = workflowRepo.listTransitions(first.workflowId).first { it.toStateId == progressState.id }
        first = specService.transition(first.id, start.id, first.version, principalId, profileId)
        bosca.cache.withRequestCache {
            assertFailsWith<bosca.workops.model.WorkOpsValidationException> { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
        }
        assertEquals(first.statusId, assertNotNull(specRepo.getActiveById(first.id)).statusId)
        connection().useStatement("update workops.task set status_id = ?::uuid where id = ?::uuid") { it.setString(1, done.id.toString()); it.setString(2, requirement.taskId.toString()); it.executeUpdate() }
        connection().useStatement("update workops.requirement set status_id = ?::uuid where id = ?::uuid") { it.setString(1, done.id.toString()); it.setString(2, requirement.id.toString()); it.executeUpdate() }
        val currentRequirement = assertNotNull(requirementService.getById(requirement.id))
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val effective = bosca.cache.withRequestCache { guidance.effective(project) }
        suspend fun body(id: UUID) = assertNotNull(metadataService.getById(id)).let { meta -> assertNotNull(documentService.getDocument(id, meta.version)).let { doc -> bosca.workops.model.spec.ReviewDocument(doc.title, json.encodeToJsonElement(Content.serializer(), assertNotNull(doc.content))) } }
        val specBody = body(first.metadataId)
        val requirementBody = body(requirement.metadataId)
        val review = bosca.workops.model.spec.SpecReviewRecord(specId = first.id.toString(), note = "Interfaces and representative callers reviewed", snapshot = bosca.workops.model.spec.SpecReviewSnapshot(project.toString(), specBody, listOf(bosca.workops.model.spec.ReviewRequirement(requirement.id.toString(), requirement.key, requirementBody, listOf("Regression passes"))), first.programId?.toString(), first.parentSpecId?.toString(), first.gitRepositoryId?.toString(), first.gitPath, emptyList(), effective.revision), interfaces = listOf(bosca.workops.model.spec.ReviewedInterface("Service.kt", "interface Service")))
        val reviewed = specCommentService.add(first.id, SpecCommentInput(content = "## WorkOps review\n\n```json\n" + json.encodeToString(bosca.workops.model.spec.SpecReviewRecord.serializer(), review) + "\n```"), principalId, profileId)
        val cycle = bosca.workops.model.spec.TddEvidence(bosca.workops.model.spec.TddRun("test red", bosca.workops.model.spec.ValidationOutcome.FAILED, "Expected assertion failed"), bosca.workops.model.spec.TddRun("test green", bosca.workops.model.spec.ValidationOutcome.PASSED, "Assertions passed"), bosca.workops.model.spec.TddRun("test refactor", bosca.workops.model.spec.ValidationOutcome.PASSED, "Assertions passed"))
        val validation = bosca.workops.model.spec.ValidationRecord(requirementId = requirement.id.toString(), taskId = task.id.toString(), taskVersion = task.version, specContentHash = specBody.fingerprint(), requirementContentHash = requirementBody.fingerprint(), checks = listOf(bosca.workops.model.spec.ValidationCheck("Regression passes", bosca.workops.model.spec.ValidationOutcome.PASSED, "Regression passed", cycle)), interfaceReviewId = reviewed.id, testQuality = bosca.workops.model.spec.TestQualityEvidence("Behavior assertions reviewed", listOf(bosca.workops.model.spec.CoverageEvidence("Production", "coverage command", "coverage.xml", 100, 0, 100, 0, "None"))), guidanceRevision = effective.revision)
        val evidence = specCommentService.add(first.id, SpecCommentInput(content = "## WorkOps validation\n\n```json\n" + json.encodeToString(bosca.workops.model.spec.ValidationRecord.serializer(), validation) + "\n```"), principalId, profileId)

        assertTrue(bosca.cache.withRequestCache { completion.issues(first, listOf(currentRequirement)) }.isEmpty())
        val validated = kotlinx.coroutines.CompletableDeferred<Unit>()
        val changed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val paused = object : SpecCompletionService {
            override suspend fun issues(spec: bosca.workops.model.spec.Spec, requirements: List<bosca.workops.model.requirement.Requirement>): List<String> {
                val issues = completion.issues(spec, requirements)
                assertTrue(issues.isEmpty())
                validated.complete(Unit)
                changed.await()
                return issues
            }
        }
        val service = newSpecService(paused)
        kotlinx.coroutines.coroutineScope {
            val writer = async {
                validated.await()
                inConnection {
                    taskService.update(task.id, bosca.workops.model.task.UpdateTaskInput(summary = "Changed after verification", expectedVersion = task.version), principalId, profileId)
                }
                changed.complete(Unit)
            }
            assertFailsWith<bosca.workops.model.OptimisticLockFailedException> {
                bosca.cache.withRequestCache { service.transition(first.id, transition.id, first.version, principalId, profileId) }
            }
            writer.await()
            assertEquals(first.statusId, assertNotNull(specRepo.getActiveById(first.id)).statusId)
        }
        val finalTask = assertNotNull(taskService.getById(task.id))
        assertEquals(task.version + 1, finalTask.version)
        assertTrue(bosca.cache.withRequestCache { completion.issues(first, listOf(currentRequirement)) }.any { "stale" in it })
        }
    }

    @Test
    fun `active child counts and parent revisions follow statuses moves deletion and rollback`() = withDb {
        persistentContent()
        val project = seedProject()
        val parent = specService.create(CreateSpecInput(projectId = project, name = "Parent"), principalId, profileId, profileId)
        val other = specService.create(CreateSpecInput(projectId = project, name = "Other"), principalId, profileId, profileId)
        var child = specService.create(CreateSpecInput(projectId = project, parentSpecId = parent.id, name = "Child"), principalId, profileId, profileId)
        suspend fun revision(id: UUID) = assertNotNull(specService.getById(id)).completionVersion
        suspend fun assertCounts(id: UUID, total: Long, done: Long) {
            assertEquals(total, specService.countChildren(id))
            assertEquals(done, specService.countDoneChildren(id))
            assertEquals(total != done, specRepo.hasIncompleteChildren(id))
        }
        assertCounts(parent.id, 1, 0)
        val statusId = UUID.random()
        connection().useStatement("insert into workops.status (id, name, category, color_hex) values (?::uuid, 'Child done', 'DONE', '#008800')") {
            it.setString(1, statusId.toString()); it.executeUpdate()
        }
        val beforeStatus = revision(parent.id)
        connection().useStatement("update workops.spec set status_id = ?::uuid where id = ?::uuid") {
            it.setString(1, statusId.toString()); it.setString(2, child.id.toString()); it.executeUpdate()
        }
        assertTrue(revision(parent.id) > beforeStatus)
        assertCounts(parent.id, 1, 1)
        val beforeCategory = revision(parent.id)
        connection().useStatement("update workops.status set category = 'CANCELLED' where id = ?::uuid") { it.setString(1, statusId.toString()); it.executeUpdate() }
        assertTrue(revision(parent.id) > beforeCategory)
        assertCounts(parent.id, 1, 0)
        val beforeRollback = revision(parent.id)
        val originalFailure = IllegalStateException("rollback child category")
        assertEquals(originalFailure, assertFailsWith<IllegalStateException> { transaction {
            connection().useStatement("update workops.status set category = 'DONE' where id = ?::uuid") { it.setString(1, statusId.toString()); it.executeUpdate() }
            assertCounts(parent.id, 1, 1)
            assertEquals(beforeRollback, revision(parent.id))
            throw originalFailure
        } })
        assertEquals(beforeRollback, revision(parent.id))
        assertCounts(parent.id, 1, 0)
        val beforeMove = revision(parent.id)
        val destinationBefore = revision(other.id)
        child = specService.update(child.id, UpdateSpecInput(parentSpecId = other.id, expectedVersion = child.version), principalId, profileId)
        assertCounts(parent.id, 0, 0)
        assertCounts(other.id, 1, 0)
        assertTrue(revision(parent.id) > beforeMove)
        assertTrue(revision(other.id) > destinationBefore)
        child = specService.softDelete(child.id, child.version, principalId, profileId)
        assertCounts(other.id, 0, 0)
        child = specService.restore(child.id, child.version, principalId, profileId)
        assertCounts(other.id, 1, 0)
        val beforeHardDelete = revision(other.id)
        connection().useStatement("delete from workops.spec where id = ?::uuid") { it.setString(1, child.id.toString()); it.executeUpdate() }
        assertCounts(other.id, 0, 0)
        assertTrue(revision(other.id) > beforeHardDelete)
    }

    @Test
    fun `completion revision covers persisted contributors and rolls back with mutations`() = withDb {
        persistentContent()
        val project = seedProject()
        val plan = planService().create(CreateSpecPlanInput(UUID.random(), project, "Revision coverage", "Scope", listOf(
            RequirementContentInput("Revision requirement", "Body", listOf("Result")),
        )), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, plan.id, 0, 100).single()
        suspend fun current() = assertNotNull(specRepo.getActiveById(plan.id))
        suspend fun invalidates(change: suspend () -> Unit) {
            val before = current()
            change()
            val after = current()
            assertTrue(after.completionVersion > before.completionVersion)
            assertEquals(before.version, after.version, "Completion inputs do not change the ordinary spec version")
        }
        invalidates { val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId))); taskService.update(task.id, bosca.workops.model.task.UpdateTaskInput(summary = "Revision edit", expectedVersion = task.version), principalId, profileId) }
        invalidates { val req = assertNotNull(requirementService.getById(requirement.id)); requirementService.update(req.id, UpdateRequirementInput(sortOrder = 3, expectedVersion = req.version), principalId, profileId) }
        invalidates { val meta = assertNotNull(metadataService.getById(plan.metadataId)); metadataService.setDocument(meta, DocumentInput(title = "Changed scope", content = MarkdownConverter.fromMarkdown("Changed"))) }
        val context = specContextService.add(plan.id, CreateSpecContextInput(SpecContextType.EXTERNAL_URI, "https://example.test/revision"), principalId, profileId)
        invalidates { specContextService.remove(plan.id, context.id, principalId, profileId) }
        var comment = specCommentService.add(plan.id, SpecCommentInput(content = "Recorded evidence"), principalId, profileId)
        invalidates { specCommentService.setStatus(plan.id, comment.id, bosca.comments.model.CommentStatus.BLOCKED, principalId, profileId) }
        invalidates { specCommentService.delete(plan.id, comment.id, principalId, profileId) }
        invalidates { comment = specCommentService.add(plan.id, SpecCommentInput(content = "New evidence"), principalId, profileId) }
        val beforeLike = current().completionVersion
        specCommentService.like(plan.id, comment.id, profileId)
        assertEquals(beforeLike, current().completionVersion, "Likes do not change completion evidence")
        invalidates { specService.generateTasks(plan.id, 1, GenerationSource.MANUAL, principalId, profileId, null) }
        val generated = specTaskGenRepo.listBySpec(plan.id, 0, 100).single().generatedTaskIds.single()
        invalidates { val task = assertNotNull(taskService.getById(generated)); taskService.update(task.id, bosca.workops.model.task.UpdateTaskInput(summary = "Generated edit", expectedVersion = task.version), principalId, profileId) }
        invalidates { val req = assertNotNull(requirementService.getById(requirement.id)); requirementService.softDelete(req.id, req.version, principalId, profileId) }
        invalidates { val req = assertNotNull(requirementService.getByIdIncludingDeleted(requirement.id)); requirementService.restore(req.id, req.version, principalId, profileId) }
        invalidates { connection().useStatement("update metadata set deleted = true where id = ?::uuid") { it.setString(1, requirement.metadataId.toString()); it.executeUpdate() } }
        invalidates { connection().useStatement("update metadata set deleted = false where id = ?::uuid") { it.setString(1, requirement.metadataId.toString()); it.executeUpdate() } }
        val beforeVersionExercise = current().completionVersion
        assertFailsWith<IllegalStateException> { transaction {
            // Core owns version archiving; isolate the WorkOps hook and restore all DDL/data by rollback.
            connection().useStatement("alter table metadata disable trigger metadata_versions") { it.execute() }
            invalidates {
                connection().useStatement("update metadata set version = version + 1 where id = ?::uuid") { it.setString(1, requirement.metadataId.toString()); it.executeUpdate() }
                connection().useStatement("set constraints all immediate") { it.execute() }
                connection().useStatement("set constraints all deferred") { it.execute() }
            }
            val beforeHistoricalEdit = current().completionVersion
            val meta = assertNotNull(metadataService.getById(requirement.metadataId))
            writeDocument(meta.copy(version = 1), DocumentInput(title = "Historical edit", content = Content()))
            assertEquals(beforeHistoricalEdit, current().completionVersion, "Historical document versions do not affect completion")
            error("Rollback isolated version exercise")
        } }
        assertEquals(beforeVersionExercise, current().completionVersion)
        assertEquals(1, metadataService.getById(requirement.metadataId)?.version)
        val beforeStatusChange = current().completionVersion
        assertFailsWith<IllegalStateException> { transaction {
            connection().useStatement("update workops.status set category = 'CANCELLED' where id = ?::uuid") { it.setString(1, current().statusId.toString()); it.executeUpdate() }
            assertEquals(beforeStatusChange, current().completionVersion, "Invalidation waits until commit")
            connection().useStatement("set constraints all immediate") { it.execute() }
            assertTrue(current().completionVersion > beforeStatusChange)
            error("Restore status definition")
        } }
        assertEquals(beforeStatusChange, current().completionVersion)
        val beforeRollback = current().completionVersion
        val originalTask = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        assertFailsWith<IllegalStateException> { transaction {
            taskService.update(originalTask.id, bosca.workops.model.task.UpdateTaskInput(summary = "Rolled back edit", expectedVersion = originalTask.version), principalId, profileId)
            error("Rollback")
        } }
        assertEquals(beforeRollback, current().completionVersion)
        assertEquals(originalTask.summary, taskService.getById(originalTask.id)?.summary)
        val destination = planService().create(CreateSpecPlanInput(UUID.random(), project, "Destination revision", "Scope", listOf(RequirementContentInput("Destination requirement", "Body", listOf("Result")))), principalId, profileId)
        val destinationBefore = assertNotNull(specRepo.getActiveById(destination.id)).completionVersion
        invalidates { val req = assertNotNull(requirementService.getById(requirement.id)); requirementService.moveToParent(req.id, RequirementParent.SPEC, destination.id, req.version, principalId, profileId) }
        assertTrue(assertNotNull(specRepo.getActiveById(destination.id)).completionVersion > destinationBefore)
        invalidates { connection().useStatement("delete from metadata where id = ?::uuid") { it.setString(1, plan.metadataId.toString()); it.executeUpdate() } }

    }

    private fun newSpecService(completion: SpecCompletionService) = SpecServiceImpl(
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
            metadataService = metadataService,
            documentService = documentService,
            specTaskGenerationRepository = specTaskGenRepo,
            projectPermissionEvaluator = projectPermissionEvaluator,
            programPermissionEvaluator = programPermissionEvaluator,
            completion = completion,
            json = json,
        )

    private fun planService(requirements: RequirementService = requirementService) = SpecPlanServiceImpl(
        specService, requirements, metadataService, PlanCreationRepositoryImpl(), json,
    )

    @Test
    fun `real completion provider rejects missing and stale evidence before persisting DONE`() = withDb {
        persistentContent()
        val guidance = realGuidance()
        provides<bosca.workops.repository.SpecRepository>(singleton = true) { specRepo }
        provides<TaskService>(singleton = true) { taskService }
        provides<bosca.workops.repository.StatusRepository>(singleton = true) { statusRepo }
        provides<SpecCommentService>(singleton = true) { specCommentService }
        provides<SpecContextService>(singleton = true) { specContextService }
        provides<bosca.workops.repository.SpecTaskGenerationRepository>(singleton = true) { specTaskGenRepo }
        val completion = SpecCompletionServiceImplProvider().get()
        specService = newSpecService(completion)
        val project = seedProject()
        var first = planService().create(CreateSpecPlanInput(UUID.random(), project, "Verified plan", "Scope", listOf(RequirementContentInput("Behavior", "Implementation", listOf("Regression passes")))), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, first.id, 0, 100).single()
        val done = statusRepo.getAll().first { it.category == bosca.workops.model.workflow.StatusCategory.DONE }
        val states = workflowRepo.listStates(first.workflowId)
        val doneState = states.first { it.statusId == done.id }
        val transition = workflowRepo.listTransitions(first.workflowId).first { it.toStateId == doneState.id }
        val inProgress = statusRepo.getAll().first { it.category == bosca.workops.model.workflow.StatusCategory.IN_PROGRESS }
        val progressState = states.first { it.statusId == inProgress.id }
        val start = workflowRepo.listTransitions(first.workflowId).first { it.toStateId == progressState.id }
        first = specService.transition(first.id, start.id, first.version, principalId, profileId)
        bosca.cache.withRequestCache {
            assertFailsWith<bosca.workops.model.WorkOpsValidationException> { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
        }
        assertEquals(first.statusId, assertNotNull(specRepo.getActiveById(first.id)).statusId)
        connection().useStatement("update workops.task set status_id = ?::uuid where id = ?::uuid") { it.setString(1, done.id.toString()); it.setString(2, requirement.taskId.toString()); it.executeUpdate() }
        connection().useStatement("update workops.requirement set status_id = ?::uuid where id = ?::uuid") { it.setString(1, done.id.toString()); it.setString(2, requirement.id.toString()); it.executeUpdate() }
        val currentRequirement = assertNotNull(requirementService.getById(requirement.id))
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val effective = bosca.cache.withRequestCache { guidance.effective(project) }
        suspend fun body(id: UUID) = assertNotNull(metadataService.getById(id)).let { meta -> assertNotNull(documentService.getDocument(id, meta.version)).let { doc -> bosca.workops.model.spec.ReviewDocument(doc.title, json.encodeToJsonElement(Content.serializer(), assertNotNull(doc.content))) } }
        val specBody = body(first.metadataId)
        val requirementBody = body(requirement.metadataId)
        val review = bosca.workops.model.spec.SpecReviewRecord(specId = first.id.toString(), note = "Interfaces and representative callers reviewed", snapshot = bosca.workops.model.spec.SpecReviewSnapshot(project.toString(), specBody, listOf(bosca.workops.model.spec.ReviewRequirement(requirement.id.toString(), requirement.key, requirementBody, listOf("Regression passes"))), first.programId?.toString(), first.parentSpecId?.toString(), first.gitRepositoryId?.toString(), first.gitPath, emptyList(), effective.revision), interfaces = listOf(bosca.workops.model.spec.ReviewedInterface("Service.kt", "interface Service")))
        val reviewed = specCommentService.add(first.id, SpecCommentInput(content = "## WorkOps review\n\n```json\n" + json.encodeToString(bosca.workops.model.spec.SpecReviewRecord.serializer(), review) + "\n```"), principalId, profileId)
        val cycle = bosca.workops.model.spec.TddEvidence(bosca.workops.model.spec.TddRun("test red", bosca.workops.model.spec.ValidationOutcome.FAILED, "Expected assertion failed"), bosca.workops.model.spec.TddRun("test green", bosca.workops.model.spec.ValidationOutcome.PASSED, "Assertions passed"), bosca.workops.model.spec.TddRun("test refactor", bosca.workops.model.spec.ValidationOutcome.PASSED, "Assertions passed"))
        val validation = bosca.workops.model.spec.ValidationRecord(requirementId = requirement.id.toString(), taskId = task.id.toString(), taskVersion = task.version, specContentHash = specBody.fingerprint(), requirementContentHash = requirementBody.fingerprint(), checks = listOf(bosca.workops.model.spec.ValidationCheck("Regression passes", bosca.workops.model.spec.ValidationOutcome.PASSED, "Regression passed", cycle)), interfaceReviewId = reviewed.id, testQuality = bosca.workops.model.spec.TestQualityEvidence("Behavior assertions reviewed", listOf(bosca.workops.model.spec.CoverageEvidence("Production", "coverage command", "coverage.xml", 100, 0, 100, 0, "None"))), guidanceRevision = effective.revision)
        val evidence = specCommentService.add(first.id, SpecCommentInput(content = "## WorkOps validation\n\n```json\n" + json.encodeToString(bosca.workops.model.spec.ValidationRecord.serializer(), validation) + "\n```"), principalId, profileId)
        assertTrue(bosca.cache.withRequestCache { completion.issues(first, listOf(currentRequirement)) }.isEmpty())
        for (documentId in listOf(first.metadataId, requirement.metadataId)) {
            connection().useStatement("update metadata set deleted = true where id = ?::uuid") { it.setString(1, documentId.toString()); it.executeUpdate() }
            val history = specService.listHistory(first.id, 0, 100)
            bosca.cache.withRequestCache {
                val failure = assertFailsWith<bosca.workops.model.WorkOpsValidationException> { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
                assertTrue(failure.message.orEmpty().lowercase().contains("document is missing"))
            }
            assertEquals(first.statusId, specService.getById(first.id)?.statusId)
            assertEquals(first.version, specService.getById(first.id)?.version)
            assertEquals(history, specService.listHistory(first.id, 0, 100))
            connection().useStatement("update metadata set deleted = false where id = ?::uuid") { it.setString(1, documentId.toString()); it.executeUpdate() }
            assertTrue(bosca.cache.withRequestCache { completion.issues(first, listOf(currentRequirement)) }.isEmpty())
        }
        connection().useStatement("update workops.task set version = version + 1 where id = ?::uuid") { it.setString(1, task.id.toString()); it.executeUpdate() }
        bosca.cache.withRequestCache {
            assertFailsWith<bosca.workops.model.WorkOpsValidationException> { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
        }
        assertEquals(first.version, assertNotNull(specRepo.getActiveById(first.id)).version)
        specCommentService.add(first.id, SpecCommentInput(content = "## WorkOps validation\n\n```json\n" + json.encodeToString(bosca.workops.model.spec.ValidationRecord.serializer(), validation.copy(taskVersion = task.version + 1)) + "\n```"), principalId, profileId)
        val childService = newSpecService(mockk(relaxed = true))
        var completedChild = childService.create(CreateSpecInput(projectId = project, name = "Completed child", parentSpecId = first.id), principalId, profileId, profileId)
        completedChild = childService.transition(completedChild.id, start.id, completedChild.version, principalId, profileId)
        completedChild = childService.transition(completedChild.id, transition.id, completedChild.version, principalId, profileId)
        val unfinishedChild = childService.create(CreateSpecInput(projectId = project, name = "Unfinished child", parentSpecId = first.id), principalId, profileId, profileId)
        completedChild = childService.softDelete(completedChild.id, completedChild.version, principalId, profileId)
        assertEquals(listOf(unfinishedChild.id), specService.listChildren(first.id, 0, 100).map { it.id })
        assertEquals(1L, specService.countChildren(first.id))
        assertEquals(0L, specService.countDoneChildren(first.id))
        val childFailure = assertFailsWith<bosca.workops.model.WorkOpsValidationException> {
            bosca.cache.withRequestCache { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
        }
        assertTrue(childFailure.message.orEmpty().contains("Child specifications are incomplete"))
        val otherParent = childService.create(CreateSpecInput(projectId = project, name = "Other parent"), principalId, profileId, profileId)
        childService.update(unfinishedChild.id, UpdateSpecInput(parentSpecId = otherParent.id, expectedVersion = unfinishedChild.version), principalId, profileId)
        completedChild = childService.restore(completedChild.id, completedChild.version, principalId, profileId)
        assertEquals(listOf(completedChild.id), specService.listChildren(first.id, 0, 100).map { it.id })
        assertEquals(1L, specService.countDoneChildren(first.id))
        val completed = bosca.cache.withRequestCache { specService.transition(first.id, transition.id, first.version, principalId, profileId) }
        assertEquals(done.id, completed.statusId)
        assertEquals(first.version + 1, completed.version)
        assertTrue(specService.listHistory(first.id, 0, 100).any { it.changes.toString().contains("transition_id") })
    }

    @Test
    fun `atomic plan replay persists populated documents tasks and a single receipt`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val input = CreateSpecPlanInput(UUID.random(), projectId, "Complete plan", "Plan scope", listOf(
            RequirementContentInput("Deliverable", "Implementation", listOf("Regression passes")),
        ))
        val service = planService()
        val first = service.create(input, principalId, profileId)
        assertEquals(first.id, service.create(input, principalId, profileId).id)
        val requirements = requirementService.listByParent(RequirementParent.SPEC, first.id, 0, 100)
        assertEquals(1, requirements.size)
        val requirement = requirements.single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        assertEquals("Deliverable", task.summary)
        assertEquals(input.requirements.single().body(), task.descriptionMarkdown)
        val document = assertNotNull(documentService.getDocument(requirement.metadataId, 1))
        assertEquals("Deliverable", document.title)
        assertTrue(MarkdownConverter.toMarkdown(assertNotNull(document.content)).contains("Regression passes"))
        assertEquals(RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID, document.templateMetadataId)
        assertFailsWith<IllegalArgumentException> { service.create(input.copy(name = "Different plan"), principalId, profileId) }
        assertEquals(1, specService.listByProject(projectId, 0, 100).size)
    }

    @Test
    fun `failed later requirement rolls back whole plan and retry can succeed`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val input = CreateSpecPlanInput(UUID.random(), projectId, "Rollback", "Scope", listOf(
            RequirementContentInput("First", "Body", listOf("Pass")), RequirementContentInput("Second", "Body", listOf("Pass")),
        ))
        val failing = object : RequirementService by requirementService {
            override suspend fun createWithContent(parentType: RequirementParent, parentId: UUID, input: RequirementContentInput, actingPrincipalId: UUID, actingProfileId: UUID?): bosca.workops.model.requirement.Requirement {
                val saved = requirementService.createWithContent(parentType, parentId, input, actingPrincipalId, actingProfileId)
                if (input.name == "Second") error("Failure after real requirement and task writes")
                return saved
            }
        }
        assertFailsWith<IllegalStateException> { planService(failing).create(input, principalId, profileId) }
        assertTrue(specService.listByProject(projectId, 0, 100).isEmpty())
        assertEquals(0L, connection().useStatement("select count(*) from workops.plan_creation") { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } })
        assertEquals(0L, connection().useStatement("select count(*) from workops.task where project_id = ?::uuid") { it.setString(1, projectId.toString()); it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } })
        assertEquals(0L, connection().useStatement("select count(*) from metadata where name in ('Rollback','First','Second')") { it.executeQuery().use { rows -> rows.next(); rows.getLong(1) } })
        val created = planService().create(input, principalId, profileId)
        assertEquals(2L, requirementService.countByParent(RequirementParent.SPEC, created.id))
    }

    @Test
    fun `ambiguous Markdown criteria fail before plan creation or content replacement writes`() = withDb {
        persistentContent()
        val project = seedProject()
        val valid = RequirementContentInput("Original", "Body", listOf("Pass"))
        val invalid = valid.copy(markdownContent = "## Acceptance criteria ##\n\n- Obsolete")
        val requestId = UUID.random()
        val input = CreateSpecPlanInput(requestId, project, "Plan", "Scope", listOf(invalid))
        assertFailsWith<IllegalArgumentException> { planService().create(input, principalId, profileId) }
        // Reusing the request with corrected input proves rejection did not reserve a receipt.
        val spec = planService().create(input.copy(requirements = listOf(valid)), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 100).single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val history = requirementService.listHistory(requirement.id, 0, 100)
        assertFailsWith<IllegalArgumentException> {
            requirementService.setContent(requirement.id, SetRequirementContentInput(invalid, requirement.version, task.version, 1), principalId, profileId)
        }
        assertEquals("Original", documentService.getDocument(requirement.metadataId, 1)?.title)
        assertEquals(valid.body(), taskService.getById(task.id)?.descriptionMarkdown)
        assertEquals(task.version, taskService.getById(task.id)?.version)
        assertEquals(requirement.version, requirementService.getById(requirement.id)?.version)
        assertEquals(history, requirementService.listHistory(requirement.id, 0, 100))
        val additionId = UUID.random()
        assertFailsWith<IllegalArgumentException> { planService().addRequirement(spec.id, additionId, invalid, principalId, profileId) }
        assertEquals(1L, requirementService.countByParent(RequirementParent.SPEC, spec.id))
        val example = valid.copy(name = "Example", markdownContent = "```markdown\n## Acceptance criteria\n```")
        val added = planService().addRequirement(spec.id, additionId, example, principalId, profileId)
        assertEquals("Example", documentService.getDocument(added.metadataId, 1)?.title)
        assertEquals(example.body(), taskService.getById(assertNotNull(added.taskId))?.descriptionMarkdown)
    }

    @Test
    fun `task version conflict rolls back requirement document version and history`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val spec = planService().create(CreateSpecPlanInput(UUID.random(), projectId, "Atomic edit", "Scope", listOf(
            RequirementContentInput("Original", "Body", listOf("Pass")),
        )), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 100).single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val input = SetRequirementContentInput(RequirementContentInput("Edited", "Changed body", listOf("New check")), requirement.version, task.version - 1, 1)
        val historyCount = requirementService.listHistory(requirement.id, 0, 100).size
        assertFailsWith<bosca.workops.model.OptimisticLockFailedException> { requirementService.setContent(requirement.id, input, principalId, profileId) }
        assertEquals("Original", documentService.getDocument(requirement.metadataId, 1)?.title)
        assertEquals(requirement.version, requirementService.getById(requirement.id)?.version)
        assertEquals(historyCount, requirementService.listHistory(requirement.id, 0, 100).size)
        requirementService.setContent(requirement.id, input.copy(expectedTaskVersion = task.version), principalId, profileId)
        assertEquals("Edited", documentService.getDocument(requirement.metadataId, 1)?.title)
        assertEquals(input.content.body(), taskService.getById(task.id)?.descriptionMarkdown)
        assertEquals(historyCount + 1, requirementService.listHistory(requirement.id, 0, 100).size)
    }

    @Test
    fun `failed requirement content replacement rolls back earlier task writes history and completion revision`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val spec = planService().create(CreateSpecPlanInput(UUID.random(), projectId, "Rollback edit", "Scope", listOf(
            RequirementContentInput("Original", "Body", listOf("Pass")),
        )), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 100).single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val document = assertNotNull(documentService.getDocument(requirement.metadataId, 1))
        val revision = specService.getById(spec.id)?.completionVersion
        val history = requirementService.listHistory(requirement.id, 0, 100)
        val taskHistory = taskService.listHistory(task.id, 0, 100)
        val input = SetRequirementContentInput(RequirementContentInput("Edited", "Changed body", listOf("New check")), requirement.version, task.version, 1)
        suspend fun unchanged() {
            assertEquals(task, taskService.getById(task.id))
            assertEquals(requirement, requirementService.getById(requirement.id))
            val actualDocument = assertNotNull(documentService.getDocument(requirement.metadataId, 1))
            assertEquals(document.title, actualDocument.title)
            assertEquals(json.encodeToJsonElement(Content.serializer(), assertNotNull(document.content)), json.encodeToJsonElement(Content.serializer(), assertNotNull(actualDocument.content)))
            assertEquals(revision, specService.getById(spec.id)?.completionVersion)
            assertEquals(history, requirementService.listHistory(requirement.id, 0, 100))
            assertEquals(taskHistory, taskService.listHistory(task.id, 0, 100))
        }
        assertFailsWith<bosca.workops.model.OptimisticLockFailedException> {
            requirementService.setContent(requirement.id, input.copy(expectedVersion = requirement.version - 1), principalId, profileId)
        }
        unchanged()
        for (failure in listOf(IllegalStateException("Document write failed"), kotlinx.coroutines.CancellationException("Request cancelled"))) {
            coEvery { metadataService.setDocument(any(), any(), any()) } coAnswers {
                writeDocument(firstArg(), arg(1))
                throw failure
            }
            val actual = assertFailsWith<Exception> { requirementService.setContent(requirement.id, input, principalId, profileId) }
            assertTrue(actual === failure)
            unchanged()
        }
        requirementService.softDelete(requirement.id, requirement.version, principalId, profileId)
        assertFailsWith<bosca.workops.model.WorkOpsNotFoundException> {
            requirementService.setContent(requirement.id, input, principalId, profileId)
        }
        assertEquals(task, taskService.getById(task.id))
        assertEquals(taskHistory, taskService.listHistory(task.id, 0, 100))
    }

    @Test
    fun `concurrent task edit and requirement content replacement produce an optimistic conflict without deadlock`() = withDb {
        concurrentContentEdit(taskEdit = true)
    }

    @Test
    fun `concurrent requirement edit and content replacement produce an optimistic conflict without deadlock`() = withDb {
        concurrentContentEdit(taskEdit = false)
    }

    private suspend fun concurrentContentEdit(taskEdit: Boolean) {
        persistentContent()
        val projectId = seedProject()
        val spec = planService().create(CreateSpecPlanInput(UUID.random(), projectId, "Concurrent edit", "Scope", listOf(
            RequirementContentInput("Original", "Body", listOf("Pass")),
        )), principalId, profileId)
        val requirement = requirementService.listByParent(RequirementParent.SPEC, spec.id, 0, 100).single()
        val task = assertNotNull(taskService.getById(assertNotNull(requirement.taskId)))
        val input = SetRequirementContentInput(RequirementContentInput("Edited", "Changed body", listOf("New check")), requirement.version, task.version, 1)
        val document = assertNotNull(documentService.getDocument(requirement.metadataId, 1))
        val history = requirementService.listHistory(requirement.id, 0, 100)
        val locked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val competingPid = kotlinx.coroutines.CompletableDeferred<Int>()
        val blocked = kotlinx.coroutines.CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val writer = async {
                inConnection { transaction {
                    // Pause the competing UPDATE at its row-lock boundary, before its completion trigger.
                    val table = if (taskEdit) "task" else "requirement"
                    val target = if (taskEdit) task.id else requirement.id
                    connection().useStatement("select id from workops.$table where id = ?::uuid for update") {
                        it.setString(1, target.toString()); it.executeQuery().use { rows -> assertTrue(rows.next()) }
                    }
                    locked.complete(Unit)
                    blocked.await()
                    if (taskEdit) taskService.update(task.id, bosca.workops.model.task.UpdateTaskInput(descriptionMarkdown = "Concurrent task edit", expectedVersion = task.version), principalId, profileId)
                    else requirementService.update(requirement.id, UpdateRequirementInput(sortOrder = 10, expectedVersion = requirement.version), principalId, profileId)
                } }
            }
            val replacement = async {
                locked.await()
                try {
                    inConnection { transaction {
                        competingPid.complete(connection().useStatement("select pg_backend_pid()") { it.executeQuery().use { rows -> rows.next(); rows.getInt(1) } })
                        requirementService.setContent(requirement.id, input, principalId, profileId)
                    } }
                    null
                } catch (failure: kotlinx.coroutines.CancellationException) { throw failure }
                catch (failure: Exception) { failure }
            }
            try {
                val pid = competingPid.await()
                kotlinx.coroutines.withTimeout(10_000) {
                    inConnection {
                        while (!connection().useStatement("select cardinality(pg_blocking_pids(?)) > 0") {
                            it.setInt(1, pid); it.executeQuery().use { rows -> rows.next(); rows.getBoolean(1) }
                        }) kotlinx.coroutines.yield()
                    }
                }
            } finally { blocked.complete(Unit) }
            writer.await()
            val failure = replacement.await()
            assertTrue(failure is bosca.workops.model.OptimisticLockFailedException, "Expected optimistic conflict, got $failure")
        }
        assertEquals(if (taskEdit) "Concurrent task edit" else task.descriptionMarkdown, taskService.getById(task.id)?.descriptionMarkdown)
        assertEquals(task.version + if (taskEdit) 1 else 0, taskService.getById(task.id)?.version)
        val actualDocument = assertNotNull(documentService.getDocument(requirement.metadataId, 1))
        assertEquals(document.title, actualDocument.title)
        assertEquals(json.encodeToJsonElement(Content.serializer(), assertNotNull(document.content)), json.encodeToJsonElement(Content.serializer(), assertNotNull(actualDocument.content)))
        assertEquals(requirement.version + if (taskEdit) 0 else 1, requirementService.getById(requirement.id)?.version)
        assertEquals(if (taskEdit) requirement.sortOrder else 10, requirementService.getById(requirement.id)?.sortOrder)
        assertEquals(history.size + if (taskEdit) 0 else 1, requirementService.listHistory(requirement.id, 0, 100).size)
    }

    @Test
    fun `concurrent identical plan requests commit one spec and one requirement`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val input = CreateSpecPlanInput(UUID.random(), projectId, "Concurrent", "Scope", listOf(
            RequirementContentInput("Deliverable", "Body", listOf("Pass")),
        ))
        // Each simulated request owns its own connection-manager context.
        kotlinx.coroutines.coroutineScope {
            val first = async(kotlinx.coroutines.Dispatchers.Default) {
                var result: UUID? = null
                withDb { result = planService().create(input, principalId, profileId).id }
                result
            }
            val second = async(kotlinx.coroutines.Dispatchers.Default) {
                var result: UUID? = null
                withDb { result = planService().create(input, principalId, profileId).id }
                result
            }
            assertEquals(first.await(), second.await())
        }
        val spec = specService.listByProject(projectId, 0, 100).single()
        assertEquals(1L, requirementService.countByParent(RequirementParent.SPEC, spec.id))
    }

    @Test
    fun `requirement addition replay is principal scoped and deleted targets are not recreated`() = withDb {
        persistentContent()
        val projectId = seedProject()
        val service = planService()
        val spec = service.create(CreateSpecPlanInput(UUID.random(), projectId, "Additions", "Scope", listOf(
            RequirementContentInput("First", "Body", listOf("Pass")),
        )), principalId, profileId)
        val requestId = UUID.random()
        val input = RequirementContentInput("Added", "Body", listOf("Pass"))
        val first = service.addRequirement(spec.id, requestId, input, principalId, profileId)
        assertEquals(first.id, service.addRequirement(spec.id, requestId, input, principalId, profileId).id)
        assertFailsWith<IllegalArgumentException> { service.addRequirement(spec.id, requestId, input.copy(name = "Changed"), principalId, profileId) }
        val differentPrincipal = service.addRequirement(spec.id, requestId, input, UUID.random(), profileId)
        assertTrue(first.id != differentPrincipal.id)
        requirementService.softDelete(first.id, first.version, principalId, profileId)
        assertFailsWith<IllegalStateException> { service.addRequirement(spec.id, requestId, input, principalId, profileId) }
    }

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

private suspend fun WorkOpsGuidanceService.saveCurrent(projectId: UUID?, input: bosca.workops.model.guidance.WorkOpsGuidanceInput) = save(projectId, input, effective(projectId).revision)
private suspend fun WorkOpsGuidanceService.resetCurrent(projectId: UUID?) = reset(projectId, effective(projectId).revision)
