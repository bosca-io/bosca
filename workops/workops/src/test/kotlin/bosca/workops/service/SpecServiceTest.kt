package bosca.workops.service

import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkflowTransitionNotAvailableException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.audit.SpecHistoryEntry
import bosca.workops.model.spec.CreateSpecInput
import bosca.workops.model.spec.GenerationSource
import bosca.workops.model.spec.Spec
import bosca.workops.model.spec.SpecTaskGeneration
import bosca.workops.model.spec.UpdateSpecInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.permission.SpecPermission
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.ProgramRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.SpecHistoryRepository
import bosca.workops.repository.SpecKeyCounterRepository
import bosca.workops.repository.SpecRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.WorkflowRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpecServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        bosca.di.ProviderRegistry.clear()
        bosca.di.provides<bosca.pubsub.PubSubService>(singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<bosca.sharedqueue.jobs.JobQueue>(name = "workops", singleton = true) { mockk(relaxed = true) }
        bosca.di.provides<kotlinx.serialization.json.Json>(singleton = true) { Json }
        service = SpecServiceImpl(
            specRepository = specRepository,
            specHistoryRepository = specHistoryRepository,
            keyCounterRepository = keyCounterRepository,
            projectRepository = projectRepository,
            programRepository = programRepository,
            statusRepository = statusRepository,
            workflowRepository = workflowRepository,
            specPermissionRepository = specPermissionRepository,
            requirementRepository = requirementRepository,
            taskService = taskService,
            metadataService = metadataService,
            documentService = documentService,
            specTaskGenerationRepository = specTaskGenerationRepository,
            projectPermissionEvaluator = projectPermissionEvaluator,
            programPermissionEvaluator = programPermissionEvaluator,
            json = json,
        )
        // Caller-supplied metadata resolves to a real Metadata so create() can bind the template.
        coEvery { metadataService.getById(metadataId) } returns mockk(relaxed = true) {
            every { id } returns metadataId
            every { version } returns 1
        }
    }

    private val specRepository = mockk<SpecRepository>()
    private val specHistoryRepository = mockk<SpecHistoryRepository>(relaxUnitFun = true)
    private val keyCounterRepository = mockk<SpecKeyCounterRepository>(relaxUnitFun = true)
    private val projectRepository = mockk<ProjectRepository>()
    private val programRepository = mockk<ProgramRepository>()
    private val statusRepository = mockk<StatusRepository>()
    private val workflowRepository = mockk<WorkflowRepository>()
    private val specPermissionRepository = mockk<bosca.workops.repository.SpecPermissionRepository>()
    private val requirementRepository = mockk<bosca.workops.repository.RequirementRepository>()
    private val taskService = mockk<TaskService>()
    private val documentService = mockk<bosca.content.metadata.service.DocumentService>(relaxed = true)
    private val specTaskGenerationRepository = mockk<bosca.workops.repository.SpecTaskGenerationRepository>()
    private val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
    private val programPermissionEvaluator = mockk<ProgramPermissionEvaluator>()
    private val json = Json { ignoreUnknownKeys = true }
    private val metadataService = mockk<bosca.content.metadata.service.MetadataService>(relaxed = true)

    private lateinit var service: SpecServiceImpl

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val projectId = UUID.random()
    private val metadataId = UUID.random()
    private val statusId = UUID.random()
    private val workflowId = UUID.random()

    private val sampleProject = bosca.workops.model.project.Project(
        id = projectId,
        programId = UUID.random(),
        key = "PROJ",
        name = "Test Project",
        ownerProfileId = profileId,
    )

    private val todoStatus = Status(
        id = statusId,
        name = "To Do",
        category = StatusCategory.TODO,
        colorHex = "#cccccc",
    )

    private val sampleWorkflow = Workflow(
        id = workflowId,
        name = "Default",
    )

    private fun sampleSpec(key: String = "PROJ-SPEC-1") = Spec(
        id = UUID.random(),
        key = key,
        metadataId = metadataId,
        projectId = projectId,
        statusId = statusId,
        workflowId = workflowId,
        ownerProfileId = profileId,
        createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    @Test
    fun `spec history helpers encode every nullable before and after combination`() {
        fun invoke(name: String, before: Any?, after: Any?): List<FieldChange> {
            val changes = mutableListOf<FieldChange>()
            val method = SpecServiceImpl::class.java.declaredMethods.single {
                it.name == name && it.parameterCount == 4
            }.apply { isAccessible = true }
            method.invoke(service, changes, "field", before, after)
            return changes
        }

        listOf(
            Triple("addIfChanged", "before", "after"),
            Triple("addIfChangedUuid", UUID.random(), UUID.random()),
        ).forEach { (name, before, after) ->
            assertTrue(invoke(name, null, null).isEmpty())
            assertEquals(1, invoke(name, null, after).size)
            assertEquals(1, invoke(name, before, null).size)
            assertEquals(1, invoke(name, before, after).size)
        }
    }

    @Test
    fun `create mints key and writes history`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { workflowRepository.listWorkflows() } returns listOf(sampleWorkflow)
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        val specSlot = slot<Spec>()
        coEvery { specRepository.add(capture(specSlot)) } answers { specSlot.captured.copy(id = UUID.random()) }
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.create(
            input = CreateSpecInput(metadataId = metadataId, projectId = projectId),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )

        assertEquals("PROJ-SPEC-1", result.key, "key should be minted from counter")
        assertEquals(metadataId, result.metadataId, "metadataId should match input")
        coVerify(exactly = 1) { keyCounterRepository.reserveNext(projectId) }
        coVerify(exactly = 1) { specHistoryRepository.add(any(), any(), eq(principalId), eq(profileId), any()) }
    }

    @Test
    fun `create initializes counter when reserveNext returns null`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { workflowRepository.listWorkflows() } returns listOf(sampleWorkflow)
        coEvery { keyCounterRepository.reserveNext(projectId) } returnsMany listOf(null, 1L)
        coEvery { keyCounterRepository.initialize(projectId) } just Runs
        coEvery { specRepository.add(any()) } answers { firstArg<Spec>().copy(id = UUID.random()) }
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.create(
            input = CreateSpecInput(metadataId = metadataId, projectId = projectId),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )

        assertEquals("PROJ-SPEC-1", result.key)
        coVerify(exactly = 1) { keyCounterRepository.initialize(projectId) }
    }

    @Test
    fun `create throws when projectId is null`() = runTest {
        assertFailsWith<WorkOpsValidationException> {
            service.create(
                input = CreateSpecInput(metadataId = metadataId, projectId = null),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
                ownerProfileId = profileId,
            )
        }
    }

    @Test
    fun `create throws when project not found`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                input = CreateSpecInput(metadataId = metadataId, projectId = projectId),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
                ownerProfileId = profileId,
            )
        }
    }

    @Test
    fun `update applies changes and writes history`() = runTest {
        val existing = sampleSpec()
        val newOwnerId = UUID.random()
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        coEvery { specRepository.updateCore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            existing.copy(ownerProfileId = newOwnerId, version = 1)
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.update(
            id = existing.id,
            input = UpdateSpecInput(ownerProfileId = newOwnerId, expectedVersion = 0),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(newOwnerId, result.ownerProfileId, "owner should be updated")
        assertEquals(1, result.version, "version should be bumped")
        coVerify(exactly = 1) { specHistoryRepository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update throws on version mismatch`() = runTest {
        val existing = sampleSpec()
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        coEvery { specRepository.updateCore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns null

        assertFailsWith<OptimisticLockFailedException> {
            service.update(
                id = existing.id,
                input = UpdateSpecInput(expectedVersion = 99),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
            )
        }
    }

    @Test
    fun `update reports a missing spec before attempting a write`() = runTest {
        val missingId = UUID.random()
        coEvery { specRepository.getActiveById(missingId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.update(missingId, UpdateSpecInput(expectedVersion = 0), principalId, profileId)
        }
        coVerify(exactly = 0) {
            specRepository.updateCore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `update skips history when nothing changed`() = runTest {
        val parentId = UUID.random()
        val existing = sampleSpec().copy(parentSpecId = parentId)
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        coEvery { specRepository.updateCore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            existing.copy(version = 1)

        service.update(
            id = existing.id,
            input = UpdateSpecInput(parentSpecId = parentId, expectedVersion = 0),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        coVerify(exactly = 0) { specHistoryRepository.add(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { specRepository.getActiveById(parentId) }
    }

    @Test
    fun `softDelete is idempotent for already-deleted spec`() = runTest {
        val existing = sampleSpec().copy(deletedAt = OffsetDateTime.now())
        coEvery { specRepository.softDelete(existing.id, principalId, 0) } returns null
        coEvery { specRepository.getById(existing.id) } returns existing
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.softDelete(existing.id, 0, principalId, profileId)
        assertNotNull(result.deletedAt, "already-deleted spec should be returned as-is")
    }

    @Test
    fun `softDelete throws optimistic lock for an active spec rejected by the repository`() = runTest {
        val existing = sampleSpec()
        coEvery { specRepository.softDelete(existing.id, principalId, existing.version) } returns null
        coEvery { specRepository.getById(existing.id) } returns existing

        assertFailsWith<OptimisticLockFailedException> {
            service.softDelete(existing.id, existing.version, principalId, profileId)
        }
    }

    @Test
    fun `softDelete throws not found for missing spec`() = runTest {
        val missingId = UUID.random()
        coEvery { specRepository.softDelete(missingId, principalId, 0) } returns null
        coEvery { specRepository.getById(missingId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.softDelete(missingId, 0, principalId, profileId)
        }
    }

    @Test
    fun `restore throws optimistic lock on version mismatch`() = runTest {
        val existing = sampleSpec().copy(deletedAt = OffsetDateTime.now())
        coEvery { specRepository.restore(existing.id, principalId, 99) } returns null
        coEvery { specRepository.getById(existing.id) } returns existing

        assertFailsWith<OptimisticLockFailedException> {
            service.restore(existing.id, 99, principalId, profileId)
        }
    }

    @Test
    fun `restore distinguishes missing idempotent and successful outcomes`() = runTest {
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val missingId = UUID.random()
        coEvery { specRepository.restore(missingId, principalId, 0) } returns null
        coEvery { specRepository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.restore(missingId, 0, principalId, profileId)
        }

        val active = sampleSpec()
        coEvery { specRepository.restore(active.id, principalId, active.version) } returns null
        coEvery { specRepository.getById(active.id) } returns active
        assertEquals(active, service.restore(active.id, active.version, principalId, profileId))

        val parentId = UUID.random()
        val deleted = sampleSpec().copy(parentSpecId = parentId, deletedAt = OffsetDateTime.now())
        val restored = deleted.copy(deletedAt = null, version = deleted.version + 1)
        coEvery { specRepository.restore(deleted.id, principalId, deleted.version) } returns restored
        coEvery { specRepository.incrementChildCount(parentId) } just Runs
        assertEquals(restored, service.restore(deleted.id, deleted.version, principalId, profileId))
        coVerify(exactly = 1) { specRepository.incrementChildCount(parentId) }
    }

    @Test
    fun `getById returns null for missing spec`() = runTest {
        coEvery { specRepository.getActiveById(any()) } returns null
        val result = service.getById(UUID.random())
        assertEquals(null, result)
    }

    @Test
    fun `listHistory delegates with clamped pagination`() = runTest {
        coEvery { specHistoryRepository.listBySpec(any(), any(), any()) } returns emptyList()
        service.listHistory(UUID.random(), -5, 999)
        coVerify { specHistoryRepository.listBySpec(any(), eq(0L), eq(200)) }
    }

    @Test
    fun `spec reads batch permissions and clamp scoped pages`() = runTest {
        val first = sampleSpec()
        val second = sampleSpec("PROJ-SPEC-2")
        val permission = SpecPermission(first.id, UUID.random(), PermissionAction.VIEW)
        val batch = Batch<UUID, List<EntityPermission>>(listOf(first.id, second.id))
        coEvery { specPermissionRepository.getBySpecId(first.id) } returns listOf(permission)
        coEvery { specPermissionRepository.getBySpecIds(listOf(first.id, second.id)) } returns listOf(permission)
        coEvery { specRepository.getById(second.id) } returns second.copy(deletedAt = OffsetDateTime.now())
        coEvery { specRepository.getActiveByKey(first.key) } returns first
        coEvery { specRepository.getByIds(listOf(first.id, second.id)) } returns listOf(first, second)
        coEvery { specRepository.listByProject(projectId, 0, 200) } returns listOf(first)
        coEvery { specRepository.listByProgram(any(), 0, 1) } returns listOf(second)
        coEvery { specRepository.listByOwner(profileId, 0, 200) } returns listOf(first, second)
        coEvery { specRepository.countByParentSpec(first.id) } returns 2

        assertEquals(listOf(permission), service.getPermissions(first))
        service.addPermissionsToBatch(batch)
        assertEquals(listOf(permission), batch.getData(first.id))
        assertEquals(emptyList(), batch.getData(second.id))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(first, second), service.getByIds(listOf(first.id, second.id)))
        assertTrue(service.getByIdIncludingDeleted(second.id)?.isDeleted == true)
        assertEquals(first, service.getByKey(first.key))
        assertEquals(listOf(first), service.listByProject(projectId, -5, 999))
        assertEquals(listOf(second), service.listByProgram(UUID.random(), -1, 0))
        assertEquals(listOf(first, second), service.listByOwner(profileId, -2, 500))
        assertEquals(2, service.countChildren(first.id))
    }

    @Test
    fun `spec update records introduced and cleared nullable hierarchy and git values`() = runTest {
        val existing = sampleSpec().copy(projectId = null)
        val programId = UUID.random()
        val parentId = UUID.random()
        val repositoryId = UUID.random()
        val populated = existing.copy(
            programId = programId,
            parentSpecId = parentId,
            sortOrder = 7,
            gitRepositoryId = repositoryId,
            gitPath = "specs/feature.md",
            version = 2,
        )
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        coEvery { specRepository.getActiveById(parentId) } returns null
        coEvery { specRepository.updateCore(existing.id, programId, null, parentId, 7, existing.ownerProfileId, repositoryId, "specs/feature.md", null, principalId, 1) } returns populated
        coEvery { specRepository.incrementChildCount(parentId) } just Runs
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        assertEquals(
            populated,
            service.update(
                existing.id,
                UpdateSpecInput(
                    programId = programId,
                    parentSpecId = parentId,
                    sortOrder = 7,
                    gitRepositoryId = repositoryId,
                    gitPath = "specs/feature.md",
                    expectedVersion = 1,
                ),
                principalId,
                profileId,
            ),
        )

        val cleared = populated.copy(
            programId = null,
            parentSpecId = null,
            gitRepositoryId = null,
            gitPath = null,
            version = 3,
        )
        coEvery { specRepository.getActiveById(populated.id) } returns populated
        coEvery { specRepository.updateCore(populated.id, null, null, null, 7, populated.ownerProfileId, null, null, null, principalId, 2) } returns cleared
        coEvery { specRepository.decrementChildCount(parentId) } just Runs

        assertEquals(
            cleared,
            service.update(
                populated.id,
                UpdateSpecInput(
                    clearProgramId = true,
                    clearParentSpecId = true,
                    clearGitRepositoryId = true,
                    clearGitPath = true,
                    expectedVersion = 2,
                ),
                principalId,
                null,
            ),
        )
        coVerify(exactly = 2) { specHistoryRepository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `spec creation reports missing workflow status and self parent cycle`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { workflowRepository.listWorkflows() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(CreateSpecInput(metadataId = metadataId, projectId = projectId), principalId, null, profileId)
        }

        coEvery { statusRepository.getAll() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(
                CreateSpecInput(metadataId = metadataId, projectId = projectId, workflowId = workflowId),
                principalId,
                null,
                profileId,
            )
        }

        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        assertFailsWith<bosca.workops.model.SpecCycleException> {
            service.create(
                CreateSpecInput(
                    metadataId = metadataId,
                    projectId = projectId,
                    workflowId = workflowId,
                    parentSpecId = UUID.NIL,
                ),
                principalId,
                null,
                profileId,
            )
        }
    }

    @Test
    fun `spec creation reports exhausted key initialization and missing supplied metadata`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns null
        coEvery { keyCounterRepository.initialize(projectId) } just Runs

        assertTrue(
            assertFailsWith<IllegalStateException> {
                service.create(
                    CreateSpecInput(metadataId = metadataId, projectId = projectId, workflowId = workflowId),
                    principalId,
                    null,
                    profileId,
                )
            }.message.orEmpty().contains("initialization failed"),
        )

        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateSpecInput(metadataId = metadataId, projectId = projectId, workflowId = workflowId),
                principalId,
                null,
                profileId,
            )
        }
    }

    @Test
    fun `SPEC_TEMPLATE_ID matches the UUID seeded in V26 migration`() {
        val migrationSql = SpecServiceTest::class.java
            .classLoader
            .getResourceAsStream("db/migrations/V26__spec_document_templates.sql")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error(
                "V26__spec_document_templates.sql must be on the test classpath. " +
                "If you renamed or removed it, fix the constant in SpecServiceImpl too — " +
                "specs depend on this seed for documents.template_metadata_id FK."
            )
        assertTrue(
            migrationSql.contains(SpecServiceImpl.SPEC_TEMPLATE_ID.toString()),
            "V26 migration must seed a document template at the UUID referenced by " +
            "SpecServiceImpl.SPEC_TEMPLATE_ID (${SpecServiceImpl.SPEC_TEMPLATE_ID}). " +
            "If the seeded UUID changed, update SpecServiceImpl.SPEC_TEMPLATE_ID to match — " +
            "otherwise spec creation will violate the documents.template_metadata_id FK."
        )
    }

    @Test
    fun `create without metadataId auto-creates metadata bound to seeded Spec template`() = runTest {
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { workflowRepository.listWorkflows() } returns listOf(sampleWorkflow)
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { specRepository.add(any()) } answers { firstArg<Spec>().copy(id = UUID.random()) }
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val metadataInputs = mutableListOf<bosca.content.metadata.model.MetadataInput>()
        coEvery {
            metadataService.add(parent = null, collectionItemAttributes = null, input = capture(metadataInputs))
        } answers {
            mockk(relaxed = true) {
                every { id } returns UUID.random()
                every { version } returns 1
            }
        }

        service.create(
            input = CreateSpecInput(metadataId = null, name = "My New Spec", projectId = projectId),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )

        service.create(
            input = CreateSpecInput(metadataId = null, name = null, projectId = projectId),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
            ownerProfileId = profileId,
        )

        assertEquals(listOf("My New Spec", "PROJ-SPEC-1"), metadataInputs.map { it.name })
        val input = metadataInputs.first()
        assertEquals("bosca/v-document", input.contentType)
        val doc = input.document
        assertNotNull(doc, "spec metadata must include a DocumentInput")

        // Bug 1: documents.content has NOT NULL constraint — never pass null content.
        assertNotNull(doc.content,
            "DocumentInput.content must not be null (documents.content has a NOT NULL constraint)")

        // Bug 2: must bind to the seeded Spec Document Template UUID, not a freshly-created
        // template. The seed lives in V26__spec_document_templates.sql with this exact UUID.
        // documents has a FK to document_templates(metadata_id, version); using any other id
        // (e.g. a freshly inserted metadata without a document_templates row) violates it.
        assertEquals(SpecServiceImpl.SPEC_TEMPLATE_ID, doc.templateMetadataId,
            "spec metadata must bind to the seeded Spec Document Template UUID (V26 seed)")
        assertEquals(SpecServiceImpl.SPEC_TEMPLATE_VERSION, doc.templateMetadataVersion,
            "spec metadata must bind to the seeded template version")

        // The template must also be applied via setDocumentTemplate, which is what
        // merges the template's default attributes (notably `type`) onto the metadata.
        coVerify(exactly = 2) {
            metadataService.setDocumentTemplate(any(), SpecServiceImpl.SPEC_TEMPLATE_ID, SpecServiceImpl.SPEC_TEMPLATE_VERSION)
        }
    }

    @Test
    fun `update rejects a hierarchy deeper than the supported limit`() = runTest {
        val existing = sampleSpec()
        val parentIds = List(256) { UUID.random() }
        val beyondLimitId = UUID.random()
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        parentIds.forEachIndexed { index, id ->
            coEvery { specRepository.getActiveById(id) } returns sampleSpec().copy(
                id = id,
                parentSpecId = parentIds.getOrNull(index + 1) ?: beyondLimitId,
            )
        }

        assertFailsWith<bosca.workops.model.SpecCycleException> {
            service.update(
                existing.id,
                UpdateSpecInput(parentSpecId = parentIds.first(), expectedVersion = existing.version),
                principalId,
                profileId,
            )
        }
        coVerify(exactly = 0) {
            specRepository.updateCore(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // --- isParentAllowed cascade ---

    @Test
    fun `isParentAllowed delegates to projectPermissionEvaluator when spec has a project`() = runTest {
        val spec = sampleSpec()
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery {
            projectPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                sampleProject,
                bosca.security.model.PermissionAction.VIEW,
            )
        } returns true

        assertTrue(service.isParentAllowed(null, spec, bosca.security.model.PermissionAction.VIEW))
    }

    @Test
    fun `isParentAllowed returns false when project not found`() = runTest {
        val spec = sampleSpec()
        coEvery { projectRepository.getById(projectId) } returns null

        assertEquals(
            false,
            service.isParentAllowed(null, spec, bosca.security.model.PermissionAction.VIEW),
        )
    }

    @Test
    fun `isParentAllowed delegates to programPermissionEvaluator for program-scoped specs`() = runTest {
        val programId = UUID.random()
        val program = bosca.workops.model.project.Program(
            id = programId,
            portfolioId = UUID.random(),
            key = "PROG",
            name = "Test Program",
            ownerProfileId = profileId,
        )
        val spec = sampleSpec().copy(projectId = null, programId = programId)
        coEvery { programRepository.getById(programId) } returns program
        coEvery {
            programPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                program,
                bosca.security.model.PermissionAction.VIEW,
            )
        } returns true

        assertTrue(service.isParentAllowed(null, spec, bosca.security.model.PermissionAction.VIEW))
    }

    @Test
    fun `isParentAllowed returns false when spec has no parent`() = runTest {
        val spec = sampleSpec().copy(projectId = null, programId = null)

        assertEquals(
            false,
            service.isParentAllowed(null, spec, bosca.security.model.PermissionAction.VIEW),
        )
    }

    @Test
    fun `isParentAllowed returns false when program parent is missing`() = runTest {
        val programId = UUID.random()
        val spec = sampleSpec().copy(projectId = null, programId = programId)
        coEvery { programRepository.getById(programId) } returns null

        assertEquals(false, service.isParentAllowed(null, spec, PermissionAction.VIEW))
    }

    @Test
    fun `isParentAllowed propagates parent denial`() = runTest {
        val spec = sampleSpec()
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery {
            projectPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                sampleProject,
                bosca.security.model.PermissionAction.VIEW,
            )
        } returns false

        assertEquals(
            false,
            service.isParentAllowed(null, spec, bosca.security.model.PermissionAction.VIEW),
        )
    }

    @Test
    fun `transition supports specific and wildcard edges and updates parent completion counters`() = runTest {
        val parentId = UUID.random()
        val doneStatus = Status(
            id = UUID.random(),
            name = "Done",
            category = StatusCategory.DONE,
            colorHex = "#00aa00",
        )
        val todoState = WorkflowState(UUID.random(), workflowId, todoStatus.id, 0)
        val doneState = WorkflowState(UUID.random(), workflowId, doneStatus.id, 1)
        val complete = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Complete",
            fromStateIds = listOf(todoState.id.toString()),
            toStateId = doneState.id,
        )
        val reopen = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Reopen",
            fromStateIds = listOf("*"),
            toStateId = todoState.id,
        )
        val todoSpec = sampleSpec().copy(parentSpecId = parentId)
        val doneSpec = sampleSpec().copy(statusId = doneStatus.id, parentSpecId = parentId)
        val completed = todoSpec.copy(statusId = doneStatus.id, version = 1)
        val reopened = doneSpec.copy(statusId = todoStatus.id, version = 1)

        coEvery { workflowRepository.getWorkflowById(workflowId) } returns sampleWorkflow
        coEvery { workflowRepository.listStates(workflowId) } returns listOf(todoState, doneState)
        coEvery { workflowRepository.listTransitions(workflowId) } returnsMany listOf(
            listOf(complete),
            listOf(reopen),
        )
        coEvery { specRepository.getActiveById(todoSpec.id) } returns todoSpec
        coEvery { specRepository.getActiveById(doneSpec.id) } returns doneSpec
        coEvery { specRepository.applyTransition(todoSpec.id, doneStatus.id, principalId, 0) } returns completed
        coEvery { specRepository.applyTransition(doneSpec.id, todoStatus.id, principalId, 0) } returns reopened
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { statusRepository.getAll() } returns listOf(todoStatus, doneStatus)
        coEvery { specRepository.incrementChildDoneCount(parentId) } just Runs
        coEvery { specRepository.decrementChildDoneCount(parentId) } just Runs

        assertEquals(
            completed,
            service.transition(todoSpec.id, complete.id, 0, principalId, profileId, null),
        )
        assertEquals(
            reopened,
            service.transition(doneSpec.id, reopen.id, 0, principalId, profileId, null),
        )
        coVerify(exactly = 1) { specRepository.incrementChildDoneCount(parentId) }
        coVerify(exactly = 1) { specRepository.decrementChildDoneCount(parentId) }
    }

    @Test
    fun `transition without a parent can retain status and still records the transition`() = runTest {
        val state = WorkflowState(UUID.random(), workflowId, statusId, 0)
        val transition = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Refresh",
            fromStateIds = listOf(state.id.toString()),
            toStateId = state.id,
        )
        val spec = sampleSpec().copy(parentSpecId = null)
        val updated = spec.copy(version = 1)
        coEvery { specRepository.getActiveById(spec.id) } returns spec
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns sampleWorkflow
        coEvery { workflowRepository.listStates(workflowId) } returns listOf(state)
        coEvery { workflowRepository.listTransitions(workflowId) } returns listOf(transition)
        coEvery { specRepository.applyTransition(spec.id, statusId, principalId, 4) } returns updated
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        assertEquals(updated, service.transition(spec.id, transition.id, 4, principalId, null, null))
        coVerify(exactly = 0) { statusRepository.getAll() }
    }

    @Test
    fun `transition reports missing spec workflow and current state`() = runTest {
        val missingSpecId = UUID.random()
        coEvery { specRepository.getActiveById(missingSpecId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.transition(missingSpecId, UUID.random(), 0, principalId, profileId, null)
        }

        val missingWorkflowSpec = sampleSpec().copy(id = UUID.random(), workflowId = UUID.random())
        coEvery { specRepository.getActiveById(missingWorkflowSpec.id) } returns missingWorkflowSpec
        coEvery { workflowRepository.getWorkflowById(missingWorkflowSpec.workflowId) } returns null
        assertFailsWith<IllegalStateException> {
            service.transition(missingWorkflowSpec.id, UUID.random(), 0, principalId, profileId, null)
        }

        val missingStateSpec = sampleSpec().copy(id = UUID.random(), workflowId = UUID.random())
        val missingStateWorkflow = Workflow(id = missingStateSpec.workflowId, name = "No state")
        coEvery { specRepository.getActiveById(missingStateSpec.id) } returns missingStateSpec
        coEvery { workflowRepository.getWorkflowById(missingStateSpec.workflowId) } returns missingStateWorkflow
        coEvery { workflowRepository.listStates(missingStateSpec.workflowId) } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.transition(missingStateSpec.id, UUID.random(), 0, principalId, profileId, null)
        }
    }

    @Test
    fun `transition rejects unknown unreachable missing-target and stale transitions`() = runTest {
        val spec = sampleSpec()
        val currentState = WorkflowState(UUID.random(), workflowId, statusId, 0)
        val targetState = WorkflowState(UUID.random(), workflowId, UUID.random(), 1)
        coEvery { specRepository.getActiveById(spec.id) } returns spec
        coEvery { workflowRepository.getWorkflowById(workflowId) } returns sampleWorkflow
        coEvery { workflowRepository.listStates(workflowId) } returns listOf(currentState, targetState)

        val unknownId = UUID.random()
        coEvery { workflowRepository.listTransitions(workflowId) } returns emptyList()
        assertFailsWith<WorkflowTransitionNotAvailableException> {
            service.transition(spec.id, unknownId, 0, principalId, profileId, null)
        }

        val unreachable = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Unreachable",
            fromStateIds = listOf(UUID.random().toString()),
            toStateId = targetState.id,
        )
        coEvery { workflowRepository.listTransitions(workflowId) } returns listOf(unreachable)
        assertFailsWith<WorkflowTransitionNotAvailableException> {
            service.transition(spec.id, unreachable.id, 0, principalId, profileId, null)
        }

        val missingTarget = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Missing target",
            fromStateIds = listOf(currentState.id.toString()),
            toStateId = UUID.random(),
        )
        coEvery { workflowRepository.listTransitions(workflowId) } returns listOf(missingTarget)
        assertFailsWith<IllegalStateException> {
            service.transition(spec.id, missingTarget.id, 0, principalId, profileId, null)
        }

        val stale = WorkflowTransition(
            id = UUID.random(),
            workflowId = workflowId,
            name = "Advance",
            fromStateIds = listOf(currentState.id.toString()),
            toStateId = targetState.id,
        )
        coEvery { workflowRepository.listTransitions(workflowId) } returns listOf(stale)
        coEvery { specRepository.applyTransition(spec.id, targetState.statusId, principalId, 9) } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.transition(spec.id, stale.id, 9, principalId, profileId, null)
        }
    }

    @Test
    fun `updates can introduce and clear project and external references`() = runTest {
        val external = kotlinx.serialization.json.buildJsonObject { put("tracker", "EXT-1") }
        val existing = sampleSpec().copy(projectId = null)
        val populated = existing.copy(projectId = projectId, externalReferences = external, version = 1)
        coEvery { specRepository.getActiveById(existing.id) } returns existing
        coEvery {
            specRepository.updateCore(
                existing.id,
                null,
                projectId,
                null,
                0,
                existing.ownerProfileId,
                null,
                null,
                external,
                principalId,
                0,
            )
        } returns populated
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        assertEquals(
            populated,
            service.update(
                existing.id,
                UpdateSpecInput(projectId = projectId, externalReferences = external, expectedVersion = 0),
                principalId,
                profileId,
            ),
        )

        val cleared = populated.copy(projectId = null, externalReferences = null, version = 2)
        coEvery { specRepository.getActiveById(populated.id) } returns populated
        coEvery {
            specRepository.updateCore(
                populated.id,
                null,
                null,
                null,
                0,
                populated.ownerProfileId,
                null,
                null,
                null,
                principalId,
                1,
            )
        } returns cleared
        assertEquals(
            cleared,
            service.update(
                populated.id,
                UpdateSpecInput(clearProjectId = true, clearExternalReferences = true, expectedVersion = 1),
                principalId,
                null,
            ),
        )
    }

    @Test
    fun `task generation validates scope and derives summaries and descriptions from requirement documents`() = runTest {
        val missingId = UUID.random()
        coEvery { specRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.generateTasks(missingId, 1, GenerationSource.MANUAL, principalId, null)
        }

        val unscoped = sampleSpec().copy(id = UUID.random(), projectId = null)
        coEvery { specRepository.getActiveById(unscoped.id) } returns unscoped
        assertFailsWith<WorkOpsValidationException> {
            service.generateTasks(unscoped.id, 1, GenerationSource.MANUAL, principalId, null)
        }

        val spec = sampleSpec()
        val requirements = listOf("NO-DOC", "BLANK", "AUTHORED").map { suffix ->
            Requirement(
                id = UUID.random(),
                key = "$suffix-1",
                metadataId = UUID.random(),
                parentType = RequirementParent.SPEC,
                parentId = spec.id,
                statusId = statusId,
                workflowId = workflowId,
                priorityId = UUID.random(),
                createdByPrincipalId = principalId,
                modifiedByPrincipalId = principalId,
            )
        }
        coEvery { specRepository.getActiveById(spec.id) } returns spec
        coEvery {
            requirementRepository.listByParent(RequirementParent.SPEC, spec.id, 0, 200)
        } returns requirements
        coEvery { documentService.getDocument(requirements[0].metadataId, 1) } returns null
        coEvery { documentService.getDocument(requirements[1].metadataId, 1) } returns
            bosca.content.metadata.model.Document(
                metadataId = requirements[1].metadataId,
                version = 1,
                title = "   ",
                content = null,
            )
        coEvery { documentService.getDocument(requirements[2].metadataId, 1) } returns
            bosca.content.metadata.model.Document(
                metadataId = requirements[2].metadataId,
                version = 1,
                title = "Authored requirement",
                content = bosca.documents.Content(),
            )
        val inputs = mutableListOf<bosca.workops.model.task.CreateTaskInput>()
        coEvery { taskService.create(capture(inputs), principalId, null, spec.ownerProfileId) } answers
            { mockk { every { id } returns UUID.random() } }
        coEvery { specTaskGenerationRepository.add(any()) } answers {
            firstArg<SpecTaskGeneration>().copy(id = UUID.random())
        }
        coEvery { specHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val generation = service.generateTasks(spec.id, 7, GenerationSource.MANUAL, principalId, null)

        assertEquals(3, generation.generatedTaskIds.size)
        assertEquals(listOf("REQ NO-DOC-1", "REQ BLANK-1", "Authored requirement"), inputs.map { it.summary })
        assertEquals(listOf(null, null), inputs.take(2).map { it.descriptionMarkdown })
        assertNotNull(inputs.last().descriptionMarkdown)
    }
}
