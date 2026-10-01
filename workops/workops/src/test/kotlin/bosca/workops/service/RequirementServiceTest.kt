package bosca.workops.service

import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.security.service.AuthenticationContext
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.project.Project
import bosca.workops.model.requirement.CreateRequirementInput
import bosca.workops.model.requirement.Requirement
import bosca.workops.model.requirement.RequirementParent
import bosca.workops.model.requirement.UpdateRequirementInput
import bosca.workops.model.permission.RequirementPermission
import bosca.workops.model.spec.Spec
import bosca.workops.model.task.Priority
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Task
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.Workflow
import bosca.content.metadata.service.DocumentService
import bosca.content.metadata.service.MetadataService
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.RequirementHistoryRepository
import bosca.workops.repository.RequirementKeyCounterRepository
import bosca.workops.repository.RequirementRepository
import bosca.workops.repository.SpecRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.WorkflowRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RequirementServiceTest {

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
    }

    private val requirementRepository = mockk<RequirementRepository>()
    private val requirementHistoryRepository = mockk<RequirementHistoryRepository>(relaxUnitFun = true)
    private val keyCounterRepository = mockk<RequirementKeyCounterRepository>(relaxUnitFun = true)
    private val projectRepository = mockk<ProjectRepository>()
    private val specRepository = mockk<SpecRepository>()
    private val taskRepository = mockk<TaskRepository>()
    private val statusRepository = mockk<StatusRepository>()
    private val priorityRepository = mockk<PriorityRepository>()
    private val workflowRepository = mockk<WorkflowRepository>()
    private val taskService = mockk<TaskService>()
    private val metadataService = mockk<MetadataService>()
    private val documentService = mockk<DocumentService>()
    private val requirementPermissionRepository = mockk<bosca.workops.repository.RequirementPermissionRepository>()
    private val specPermissionEvaluator = mockk<SpecPermissionEvaluator>()
    private val taskPermissionEvaluator = mockk<TaskPermissionEvaluator>()
    private val json = Json { ignoreUnknownKeys = true }

    private val service = RequirementServiceImpl(
        requirementRepository = requirementRepository,
        requirementHistoryRepository = requirementHistoryRepository,
        keyCounterRepository = keyCounterRepository,
        projectRepository = projectRepository,
        specRepository = specRepository,
        taskRepository = taskRepository,
        taskService = taskService,
        metadataService = metadataService,
        documentService = documentService,
        statusRepository = statusRepository,
        priorityRepository = priorityRepository,
        workflowRepository = workflowRepository,
        requirementPermissionRepository = requirementPermissionRepository,
        specPermissionEvaluator = specPermissionEvaluator,
        taskPermissionEvaluator = taskPermissionEvaluator,
        json = json,
    )

    private val principalId = UUID.random()
    private val profileId = UUID.random()
    private val projectId = UUID.random()
    private val specId = UUID.random()
    private val taskId = UUID.random()
    private val metadataId = UUID.random()
    private val statusId = UUID.random()
    private val priorityId = UUID.random()
    private val workflowId = UUID.random()

    private val todoStatus = Status(id = statusId, name = "To Do", category = StatusCategory.TODO, colorHex = "#ccc")
    private val mediumPriority = Priority(id = priorityId, name = "Medium", iconKey = "mid", colorHex = "#ff0", displayOrder = 2)
    private val sampleWorkflow = Workflow(id = workflowId, name = "Default")

    private val sampleProject = Project(
        id = projectId,
        programId = UUID.random(),
        key = "PROJ",
        name = "Test Project",
        ownerProfileId = profileId,
    )

    private val sampleSpec = Spec(
        id = specId, key = "PROJ-SPEC-1", metadataId = UUID.random(), projectId = projectId,
        statusId = statusId, workflowId = workflowId, ownerProfileId = profileId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private val sampleTask = Task(
        id = taskId, key = "PROJ-1", projectId = projectId, taskTypeId = UUID.random(),
        statusId = statusId, priorityId = priorityId, summary = "Test task",
        reporterProfileId = profileId, createdByPrincipalId = principalId,
        modifiedByPrincipalId = principalId,
    )

    private fun sampleRequirement(key: String = "PROJ-REQ-1") = Requirement(
        id = UUID.random(), key = key, metadataId = metadataId,
        parentType = RequirementParent.SPEC, parentId = specId,
        statusId = statusId, workflowId = workflowId, priorityId = priorityId,
        createdByPrincipalId = principalId, modifiedByPrincipalId = principalId,
    )

    private fun setupDefaults() {
        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { priorityRepository.getAll() } returns listOf(mediumPriority)
        coEvery { workflowRepository.listWorkflows() } returns listOf(sampleWorkflow)
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        coEvery { documentService.getDocument(any(), any()) } returns null
        coEvery { taskService.create(any(), any(), any(), any()) } returns sampleTask
        // Caller-supplied metadata resolves to a real Metadata so create() can bind the template.
        coEvery { metadataService.getById(metadataId) } returns mockk(relaxed = true) {
            every { id } returns metadataId
            every { version } returns 1
        }
        coEvery { metadataService.setDocumentTemplate(any(), any(), any()) } just Runs
    }

    @Test
    fun `requirement history helper encodes every nullable before and after combination`() {
        val method = RequirementServiceImpl::class.java.declaredMethods.single {
            it.name == "addIfChangedUuid" && it.parameterCount == 4
        }.apply { isAccessible = true }
        fun invoke(before: UUID?, after: UUID?): List<bosca.workops.model.audit.FieldChange> {
            val changes = mutableListOf<bosca.workops.model.audit.FieldChange>()
            method.invoke(service, changes, "field", before, after)
            return changes
        }
        val before = UUID.random()
        val after = UUID.random()

        assertTrue(invoke(null, null).isEmpty())
        assertEquals(1, invoke(null, after).size)
        assertEquals(1, invoke(before, null).size)
        assertEquals(1, invoke(before, after).size)
    }

    @Test
    fun `create with spec parent validates spec exists and mints key`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        val result = service.create(
            input = CreateRequirementInput(
                metadataId = metadataId,
                parentType = RequirementParent.SPEC,
                parentId = specId,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals("PROJ-REQ-1", result.key)
        assertEquals(RequirementParent.SPEC, result.parentType)
        assertEquals(specId, result.parentId)
        assertEquals(taskId, result.taskId)
        coVerify(exactly = 1) { taskService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `create with task parent validates task exists`() = runTest {
        setupDefaults()
        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        val result = service.create(
            input = CreateRequirementInput(
                metadataId = metadataId,
                parentType = RequirementParent.TASK,
                parentId = taskId,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(RequirementParent.TASK, result.parentType)
        assertEquals(taskId, result.parentId)
    }

    @Test
    fun `create throws when spec parent not found`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                input = CreateRequirementInput(
                    metadataId = metadataId,
                    parentType = RequirementParent.SPEC,
                    parentId = specId,
                ),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
            )
        }
    }

    @Test
    fun `create throws when task parent not found`() = runTest {
        setupDefaults()
        coEvery { taskRepository.getActiveById(taskId) } returns null

        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                input = CreateRequirementInput(
                    metadataId = metadataId,
                    parentType = RequirementParent.TASK,
                    parentId = taskId,
                ),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
            )
        }
    }

    @Test
    fun `update applies priority change and writes history`() = runTest {
        val existing = sampleRequirement()
        val newPriorityId = UUID.random()
        coEvery { requirementRepository.getActiveById(existing.id) } returns existing
        coEvery { requirementRepository.updateCore(any(), any(), any(), any(), any(), any(), any()) } returns
            existing.copy(priorityId = newPriorityId, version = 1)
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        val result = service.update(
            id = existing.id,
            input = UpdateRequirementInput(priorityId = newPriorityId, expectedVersion = 0),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        assertEquals(newPriorityId, result.priorityId)
        coVerify(exactly = 1) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `update throws on version mismatch`() = runTest {
        val existing = sampleRequirement()
        coEvery { requirementRepository.getActiveById(existing.id) } returns existing
        coEvery { requirementRepository.updateCore(any(), any(), any(), any(), any(), any(), any()) } returns null

        assertFailsWith<OptimisticLockFailedException> {
            service.update(
                id = existing.id,
                input = UpdateRequirementInput(expectedVersion = 99),
                actingPrincipalId = principalId,
                actingProfileId = profileId,
            )
        }
    }

    @Test
    fun `countByParent delegates to repository`() = runTest {
        coEvery { requirementRepository.countByParent(RequirementParent.SPEC, specId) } returns 5L
        val count = service.countByParent(RequirementParent.SPEC, specId)
        assertEquals(5L, count)
    }

    @Test
    fun `syncStatusFromTask updates linked and child requirements`() = runTest {
        val newStatusId = UUID.random()
        val linkedReq = sampleRequirement("PROJ-REQ-1").copy(taskId = taskId)
        val childReq = sampleRequirement("PROJ-REQ-2").copy(parentType = RequirementParent.TASK, parentId = taskId)
        coEvery { requirementRepository.syncStatusByTaskId(taskId, newStatusId, principalId) } returns
            linkedReq.copy(statusId = newStatusId, version = 1)
        coEvery { requirementRepository.listByParent(RequirementParent.TASK, taskId, 0, any()) } returns listOf(childReq)
        coEvery { requirementRepository.syncStatusByParentTask(taskId, newStatusId, principalId) } returns Unit
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.syncStatusFromTask(taskId, newStatusId, principalId, profileId)

        coVerify(exactly = 1) { requirementRepository.syncStatusByTaskId(taskId, newStatusId, principalId) }
        coVerify(exactly = 1) { requirementRepository.syncStatusByParentTask(taskId, newStatusId, principalId) }
        coVerify(exactly = 2) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `REQUIREMENT_TEMPLATE_ID matches the UUID seeded in V26 migration`() {
        val migrationSql = RequirementServiceTest::class.java
            .classLoader
            .getResourceAsStream("db/migrations/V26__spec_document_templates.sql")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error(
                "V26__spec_document_templates.sql must be on the test classpath. " +
                "If you renamed or removed it, fix the constant in RequirementServiceImpl too — " +
                "requirements depend on this seed for documents.template_metadata_id FK."
            )
        assertTrue(
            migrationSql.contains(RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID.toString()),
            "V26 migration must seed a document template at the UUID referenced by " +
            "RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID (${RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID}). " +
            "If the seeded UUID changed, update the constant — otherwise requirement creation " +
            "will violate the documents.template_metadata_id FK."
        )
    }

    @Test
    fun `create without metadataId auto-creates metadata bound to seeded Requirement template`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        val reqMetadataInputSlot = io.mockk.slot<bosca.content.metadata.model.MetadataInput>()
        coEvery {
            metadataService.add(parent = null, collectionItemAttributes = null, input = capture(reqMetadataInputSlot))
        } answers {
            mockk(relaxed = true) {
                every { id } returns UUID.random()
                every { version } returns 1
            }
        }

        service.create(
            input = CreateRequirementInput(
                metadataId = null,
                name = "My New Requirement",
                parentType = RequirementParent.SPEC,
                parentId = specId,
            ),
            actingPrincipalId = principalId,
            actingProfileId = profileId,
        )

        val input = reqMetadataInputSlot.captured
        assertEquals("bosca/v-document", input.contentType)
        val doc = input.document
        assertNotNull(doc, "requirement metadata must include a DocumentInput")
        // Bug 1: documents.content has NOT NULL constraint — never pass null content.
        assertNotNull(doc.content,
            "DocumentInput.content must not be null (documents.content has a NOT NULL constraint)")
        // Bug 2: must bind to the seeded Requirement Document Template UUID, not a freshly-created
        // template. The seed lives in V26__spec_document_templates.sql with this exact UUID.
        // documents has a FK to document_templates(metadata_id, version); using any other id
        // (e.g. a freshly inserted metadata without a document_templates row) violates it.
        assertEquals(RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID, doc.templateMetadataId,
            "requirement metadata must bind to the seeded Requirement Document Template UUID (V26 seed)")
        assertEquals(RequirementServiceImpl.REQUIREMENT_TEMPLATE_VERSION, doc.templateMetadataVersion,
            "requirement metadata must bind to the seeded template version")

        // The template must also be applied via setDocumentTemplate, which is what
        // merges the template's default attributes (notably `type`) onto the metadata.
        coVerify(exactly = 1) {
            metadataService.setDocumentTemplate(any(), RequirementServiceImpl.REQUIREMENT_TEMPLATE_ID, RequirementServiceImpl.REQUIREMENT_TEMPLATE_VERSION)
        }
    }

    @Test
    fun `syncStatusFromTask silently returns when no linked requirement`() = runTest {
        coEvery { requirementRepository.syncStatusByTaskId(any(), any(), any()) } returns null
        coEvery { requirementRepository.listByParent(RequirementParent.TASK, any(), 0, any()) } returns emptyList()

        service.syncStatusFromTask(taskId, UUID.random(), principalId, profileId)

        coVerify(exactly = 0) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }
    }

    // --- isParentAllowed cascade ---

    @Test
    fun `isParentAllowed delegates to specPermissionEvaluator for SPEC parent`() = runTest {
        val req = sampleRequirement()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery {
            specPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                sampleSpec,
                bosca.security.model.PermissionAction.VIEW,
            )
        } returns true

        assertTrue(service.isParentAllowed(null, req, bosca.security.model.PermissionAction.VIEW))
    }

    @Test
    fun `isParentAllowed delegates to taskPermissionEvaluator for TASK parent`() = runTest {
        val req = sampleRequirement().copy(parentType = RequirementParent.TASK, parentId = taskId)
        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery {
            taskPermissionEvaluator.isAllowed(
                any<AuthenticationContext>(),
                sampleTask,
                bosca.security.model.PermissionAction.EDIT,
            )
        } returns true

        assertTrue(service.isParentAllowed(null, req, bosca.security.model.PermissionAction.EDIT))
    }

    @Test
    fun `isParentAllowed returns false when SPEC parent not found`() = runTest {
        val req = sampleRequirement()
        coEvery { specRepository.getActiveById(specId) } returns null

        assertEquals(
            false,
            service.isParentAllowed(null, req, bosca.security.model.PermissionAction.VIEW),
        )
    }

    @Test
    fun `isParentAllowed returns false when TASK parent not found`() = runTest {
        val req = sampleRequirement().copy(parentType = RequirementParent.TASK, parentId = taskId)
        coEvery { taskRepository.getActiveById(taskId) } returns null

        assertEquals(
            false,
            service.isParentAllowed(null, req, bosca.security.model.PermissionAction.VIEW),
        )
    }

    @Test
    fun `permission batching direct reads and bounded lists preserve repository semantics`() = runTest {
        val first = sampleRequirement()
        val second = sampleRequirement("PROJ-REQ-2")
        val permission = RequirementPermission(first.id, UUID.random(), PermissionAction.VIEW)
        val batch = Batch<UUID, List<EntityPermission>>(listOf(first.id, second.id))
        coEvery { requirementPermissionRepository.getByRequirementId(first.id) } returns listOf(permission)
        coEvery { requirementPermissionRepository.getByRequirementIds(listOf(first.id, second.id)) } returns listOf(permission)
        coEvery { requirementRepository.getActiveById(first.id) } returns first
        coEvery { requirementRepository.getById(second.id) } returns second.copy(deletedAt = OffsetDateTime.now())
        coEvery { requirementRepository.getActiveByKey(first.key) } returns first
        coEvery { requirementRepository.getByTaskId(taskId) } returns first
        coEvery { requirementRepository.getByIds(listOf(first.id, second.id)) } returns listOf(first, second)
        coEvery { requirementRepository.listByParent(RequirementParent.SPEC, specId, 0, 200) } returns listOf(first, second)

        assertEquals(listOf(permission), service.getPermissions(first))
        service.addPermissionsToBatch(batch)
        assertEquals(listOf(permission), batch.getData(first.id))
        assertEquals(emptyList(), batch.getData(second.id))
        assertEquals(first, service.getById(first.id))
        assertTrue(service.getByIdIncludingDeleted(second.id)?.isDeleted == true)
        assertEquals(first, service.getByKey(first.key))
        assertEquals(first, service.getByTaskId(taskId))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(first, second), service.getByIds(listOf(first.id, second.id)))
        assertEquals(listOf(first, second), service.listByParent(RequirementParent.SPEC, specId, -2, 500))
    }

    @Test
    fun `update supports clearing nullable values and omits empty history`() = runTest {
        val existing = sampleRequirement().copy(
            assigneeProfileId = UUID.random(),
            sortOrder = 4,
            externalReferences = JsonObject(mapOf("source" to JsonPrimitive("brief"))),
        )
        val cleared = existing.copy(
            assigneeProfileId = null,
            sortOrder = 9,
            externalReferences = null,
            version = 2,
        )
        coEvery { requirementRepository.getActiveById(existing.id) } returns existing
        coEvery {
            requirementRepository.updateCore(
                existing.id,
                existing.priorityId,
                null,
                9,
                null,
                principalId,
                1,
            )
        } returns cleared
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        assertEquals(
            cleared,
            service.update(
                existing.id,
                UpdateRequirementInput(
                    clearAssignee = true,
                    sortOrder = 9,
                    clearExternalReferences = true,
                    expectedVersion = 1,
                ),
                principalId,
                profileId,
            ),
        )
        coVerify(exactly = 1) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }

        val unchanged = sampleRequirement().copy(version = 3)
        coEvery { requirementRepository.getActiveById(unchanged.id) } returns unchanged
        coEvery {
            requirementRepository.updateCore(
                unchanged.id,
                unchanged.priorityId,
                null,
                unchanged.sortOrder,
                null,
                principalId,
                3,
            )
        } returns unchanged.copy(version = 4)
        clearMocks(requirementHistoryRepository, answers = false, recordedCalls = true)
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        service.update(unchanged.id, UpdateRequirementInput(expectedVersion = 3), principalId, null)
        coVerify(exactly = 0) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }

        val assignedId = UUID.random()
        val references = JsonObject(mapOf("source" to JsonPrimitive("support")))
        val assigned = unchanged.copy(
            assigneeProfileId = assignedId,
            externalReferences = references,
            version = 4,
        )
        coEvery {
            requirementRepository.updateCore(
                unchanged.id,
                unchanged.priorityId,
                assignedId,
                unchanged.sortOrder,
                references,
                principalId,
                3,
            )
        } returns assigned
        assertEquals(
            assigned,
            service.update(
                unchanged.id,
                UpdateRequirementInput(
                    assigneeProfileId = assignedId,
                    externalReferences = references,
                    expectedVersion = 3,
                ),
                principalId,
                profileId,
            ),
        )

        val missingId = UUID.random()
        coEvery { requirementRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(missingId, UpdateRequirementInput(expectedVersion = 0), principalId, null)
        }
    }

    @Test
    fun `delete and restore are idempotent while distinguishing missing and stale versions`() = runTest {
        val active = sampleRequirement().copy(version = 1)
        val deleted = active.copy(deletedAt = OffsetDateTime.now(), version = 2)
        coEvery { requirementRepository.softDelete(active.id, principalId, 1) } returns deleted
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        assertEquals(deleted, service.softDelete(active.id, 1, principalId, profileId))

        coEvery { requirementRepository.softDelete(deleted.id, principalId, 2) } returns null
        coEvery { requirementRepository.getById(deleted.id) } returns deleted
        assertEquals(deleted, service.softDelete(deleted.id, 2, principalId, null))

        val missingId = UUID.random()
        coEvery { requirementRepository.softDelete(missingId, principalId, 0) } returns null
        coEvery { requirementRepository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.softDelete(missingId, 0, principalId, null) }

        coEvery { requirementRepository.softDelete(active.id, principalId, 99) } returns null
        coEvery { requirementRepository.getById(active.id) } returns active
        assertFailsWith<OptimisticLockFailedException> { service.softDelete(active.id, 99, principalId, null) }

        val restored = deleted.copy(deletedAt = null, version = 3)
        coEvery { requirementRepository.restore(deleted.id, principalId, 2) } returns restored
        assertEquals(restored, service.restore(deleted.id, 2, principalId, profileId))

        coEvery { requirementRepository.restore(restored.id, principalId, 3) } returns null
        coEvery { requirementRepository.getById(restored.id) } returns restored
        assertEquals(restored, service.restore(restored.id, 3, principalId, null))

        coEvery { requirementRepository.restore(missingId, principalId, 0) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.restore(missingId, 0, principalId, null) }

        coEvery { requirementRepository.restore(deleted.id, principalId, 99) } returns null
        coEvery { requirementRepository.getById(deleted.id) } returns deleted
        assertFailsWith<OptimisticLockFailedException> { service.restore(deleted.id, 99, principalId, null) }
    }

    @Test
    fun `move validates both parent types and records only actual parent changes`() = runTest {
        val existing = sampleRequirement().copy(version = 4)
        val moved = existing.copy(parentType = RequirementParent.TASK, parentId = taskId, version = 5)
        coEvery { requirementRepository.getActiveById(existing.id) } returns existing
        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery {
            requirementRepository.moveToParent(existing.id, RequirementParent.TASK, taskId, principalId, 4)
        } returns moved
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        assertEquals(moved, service.moveToParent(existing.id, RequirementParent.TASK, taskId, 4, principalId, profileId))

        val same = existing.copy(version = 6)
        coEvery { requirementRepository.getActiveById(same.id) } returns same
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery {
            requirementRepository.moveToParent(same.id, RequirementParent.SPEC, specId, principalId, 6)
        } returns same.copy(version = 7)
        clearMocks(requirementHistoryRepository, answers = false, recordedCalls = true)
        coEvery { requirementHistoryRepository.add(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        service.moveToParent(same.id, RequirementParent.SPEC, specId, 6, principalId, null)
        coVerify(exactly = 0) { requirementHistoryRepository.add(any(), any(), any(), any(), any()) }

        val missingId = UUID.random()
        coEvery { requirementRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.moveToParent(missingId, RequirementParent.SPEC, specId, 0, principalId, null)
        }

        coEvery { requirementRepository.getActiveById(existing.id) } returns existing
        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery {
            requirementRepository.moveToParent(existing.id, RequirementParent.TASK, taskId, principalId, 99)
        } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.moveToParent(existing.id, RequirementParent.TASK, taskId, 99, principalId, null)
        }
    }

    @Test
    fun `history paging is bounded`() = runTest {
        coEvery { requirementHistoryRepository.listByRequirement(any(), 0, 200) } returns emptyList()
        assertTrue(service.listHistory(UUID.random(), -8, 999).isEmpty())
    }

    @Test
    fun `create inherits reporter from each parent kind and derives linked task summaries`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returnsMany listOf(4L, 5L)
        val blankDocument = mockk<bosca.content.metadata.model.Document>() {
            every { title } returns "  "
            every { content } returns null
        }
        val titledDocument = mockk<bosca.content.metadata.model.Document>() {
            every { title } returns "Customer can export"
            every { content } returns null
        }
        coEvery { documentService.getDocument(metadataId, 1) } returnsMany listOf(blankDocument, titledDocument)
        val taskInputs = mutableListOf<CreateTaskInput>()
        val reporterIds = mutableListOf<UUID>()
        coEvery { taskService.create(capture(taskInputs), principalId, null, capture(reporterIds)) } returns sampleTask
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        service.create(
            CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
            principalId,
            null,
        )
        service.create(
            CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.TASK, parentId = taskId),
            principalId,
            null,
        )

        assertEquals(listOf(sampleSpec.ownerProfileId, sampleTask.reporterProfileId), reporterIds)
        assertEquals("REQ PROJ-REQ-4", taskInputs[0].summary)
        assertEquals("Customer can export", taskInputs[1].summary)
        assertEquals(priorityId, taskInputs[0].priorityId)
        assertEquals(projectId, taskInputs[1].projectId)
    }

    @Test
    fun `create recovers an absent key counter and treats document lookup failure as no document`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returnsMany listOf(null, 1L)
        coEvery { keyCounterRepository.initialize(projectId) } just Runs
        coEvery { documentService.getDocument(metadataId, 1) } throws IllegalStateException("content unavailable")
        val inputSlot = slot<CreateTaskInput>()
        coEvery { taskService.create(capture(inputSlot), principalId, profileId, profileId) } returns sampleTask
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        val created = service.create(
            CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
            principalId,
            profileId,
        )

        assertEquals("PROJ-REQ-1", created.key)
        assertEquals("REQ PROJ-REQ-1", inputSlot.captured.summary)
        coVerify(exactly = 1) { keyCounterRepository.initialize(projectId) }
    }

    @Test
    fun `create reports an exhausted key counter and missing supplied metadata`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns null
        coEvery { keyCounterRepository.initialize(projectId) } just Runs

        assertFailsWith<IllegalStateException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
                principalId,
                profileId,
            )
        }

        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { metadataService.getById(metadataId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
                principalId,
                profileId,
            )
        }
    }

    @Test
    fun `create preserves document cancellation and reports invalid project and seed state`() = runTest {
        setupDefaults()
        coEvery { specRepository.getActiveById(specId) } returns sampleSpec
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { documentService.getDocument(metadataId, 1) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
                principalId,
                profileId,
            )
        }

        val projectlessSpec = sampleSpec.copy(projectId = null)
        coEvery { specRepository.getActiveById(projectlessSpec.id) } returns projectlessSpec
        assertFailsWith<IllegalStateException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = projectlessSpec.id),
                principalId,
                profileId,
            )
        }

        coEvery { taskRepository.getActiveById(taskId) } returns sampleTask
        coEvery { projectRepository.getById(projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.TASK, parentId = taskId),
                principalId,
                profileId,
            )
        }

        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { workflowRepository.listWorkflows() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.TASK, parentId = taskId),
                principalId,
                profileId,
            )
        }

        coEvery { statusRepository.getAll() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(
                CreateRequirementInput(
                    metadataId = metadataId,
                    parentType = RequirementParent.TASK,
                    parentId = taskId,
                    workflowId = workflowId,
                ),
                principalId,
                profileId,
            )
        }

        coEvery { statusRepository.getAll() } returns listOf(todoStatus)
        coEvery { priorityRepository.getAll() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(
                CreateRequirementInput(
                    metadataId = metadataId,
                    parentType = RequirementParent.TASK,
                    parentId = taskId,
                    workflowId = workflowId,
                ),
                principalId,
                profileId,
            )
        }
    }

    @Test
    fun `create reports parents that disappear between validation and dependent reads`() = runTest {
        coEvery { specRepository.getActiveById(specId) } returnsMany listOf(sampleSpec, null)
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
                principalId,
                profileId,
            )
        }

        coEvery { taskRepository.getActiveById(taskId) } returnsMany listOf(sampleTask, null)
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.TASK, parentId = taskId),
                principalId,
                profileId,
            )
        }

        setupDefaults()
        coEvery { projectRepository.getById(projectId) } returns sampleProject
        coEvery { keyCounterRepository.reserveNext(projectId) } returns 1L
        coEvery { requirementRepository.add(any()) } answers { firstArg<Requirement>().copy(id = UUID.random()) }

        coEvery { specRepository.getActiveById(specId) } returnsMany listOf(sampleSpec, sampleSpec, null)
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.SPEC, parentId = specId),
                principalId,
                null,
            )
        }

        coEvery { taskRepository.getActiveById(taskId) } returnsMany listOf(sampleTask, sampleTask, null)
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateRequirementInput(metadataId = metadataId, parentType = RequirementParent.TASK, parentId = taskId),
                principalId,
                null,
            )
        }
    }
}
