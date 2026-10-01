@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.service

import bosca.content.metadata.model.Metadata
import bosca.content.metadata.service.MetadataService
import bosca.di.ObjectProvider
import bosca.graphql.Batch
import bosca.security.model.EntityPermission
import bosca.security.model.PermissionAction
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.serialization.UUIDSerializer
import bosca.workops.model.OptimisticLockFailedException
import bosca.workops.model.PendingPhaseImplementationException
import bosca.workops.model.WorkOpsArchivedException
import bosca.workops.model.WorkOpsNotFoundException
import bosca.workops.model.WorkOpsValidationException
import bosca.workops.model.WorkflowTransitionNotAvailableException
import bosca.workops.model.audit.FieldChange
import bosca.workops.model.notification.NotificationDeliveryRequested
import bosca.workops.model.notification.dispatch as dispatchNotification
import bosca.workops.model.project.Project
import bosca.workops.model.task.CreateTaskInput
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskCreated
import bosca.workops.model.task.TaskDeleted
import bosca.workops.model.task.TaskTransitioned
import bosca.workops.model.task.TaskType
import bosca.workops.model.task.TaskTypeScheme
import bosca.workops.model.task.TaskUpdated
import bosca.workops.model.task.UpdateTaskInput
import bosca.workops.model.task.dispatch
import bosca.workops.model.workflow.PostFunction
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.model.workflow.Workflow
import bosca.workops.model.workflow.WorkflowState
import bosca.workops.model.workflow.WorkflowTransition
import bosca.workops.repository.TaskHistoryRepository
import bosca.workops.repository.TaskPermissionRepository
import bosca.workops.repository.TaskRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TaskServiceEdgeTest {

    private val taskRepository = mockk<TaskRepository>()
    private val historyRepository = mockk<TaskHistoryRepository>(relaxed = true)
    private val projectService = mockk<ProjectService>()
    private val programService = mockk<ProgramService>(relaxed = true)
    private val taskTypeService = mockk<TaskTypeService>(relaxed = true)
    private val taskTypeSchemeService = mockk<TaskTypeSchemeService>(relaxed = true)
    private val statusService = mockk<StatusService>(relaxed = true)
    private val priorityService = mockk<PriorityService>(relaxed = true)
    private val workflowService = mockk<WorkflowService>(relaxed = true)
    private val customFieldService = mockk<TaskCustomFieldService>(relaxed = true)
    private val permissionRepository = mockk<TaskPermissionRepository>()
    private val affectedProjectService = mockk<TaskAffectedProjectService>(relaxed = true)
    private val requirementProvider = mockk<ObjectProvider<RequirementService>>(relaxed = true)
    private val metadataService = mockk<MetadataService>()
    private val sprintService = mockk<SprintService>(relaxed = true)
    private val projectPermissionEvaluator = mockk<ProjectPermissionEvaluator>()
    private val json = Json {
        serializersModule = SerializersModule { contextual(UUIDSerializer()) }
    }
    private val service = TaskServiceImpl(
        taskRepository,
        historyRepository,
        projectService,
        programService,
        taskTypeService,
        taskTypeSchemeService,
        statusService,
        priorityService,
        workflowService,
        WorkflowEvaluator(),
        customFieldService,
        permissionRepository,
        affectedProjectService,
        requirementProvider,
        metadataService,
        sprintService,
        projectPermissionEvaluator,
        json,
    )

    @BeforeTest
    fun setup() {
        mockkStatic("bosca.db.ConnectionManagerKt")
        coEvery { bosca.db.transaction(any<suspend () -> Any?>()) } coAnswers {
            firstArg<suspend () -> Any?>().invoke()
        }
        mockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        coEvery { any<NotificationDeliveryRequested>().dispatchNotification() } just Runs
        mockkStatic("bosca.workops.model.task.TaskDeletedExtKt")
        coEvery { any<TaskDeleted>().dispatch() } just Runs
        mockkStatic("bosca.workops.model.task.TaskCreatedExtKt")
        coEvery { any<TaskCreated>().dispatch() } just Runs
        mockkStatic("bosca.workops.model.task.TaskUpdatedExtKt")
        coEvery { any<TaskUpdated>().dispatch() } just Runs
        mockkStatic("bosca.workops.model.task.TaskTransitionedExtKt")
        coEvery { any<TaskTransitioned>().dispatch() } just Runs
    }

    @AfterTest
    fun teardown() {
        unmockkStatic("bosca.workops.model.task.TaskTransitionedExtKt")
        unmockkStatic("bosca.workops.model.task.TaskUpdatedExtKt")
        unmockkStatic("bosca.workops.model.task.TaskCreatedExtKt")
        unmockkStatic("bosca.workops.model.task.TaskDeletedExtKt")
        unmockkStatic("bosca.workops.model.notification.NotificationDeliveryRequestedExtKt")
        unmockkStatic("bosca.db.ConnectionManagerKt")
    }

    @Test
    fun `task reads bound pages short circuit empty ids and delegate parent permissions`() = runTest {
        val task = task()
        val project = project(id = task.projectId)
        val permission = bosca.workops.model.permission.TaskPermission(task.id, UUID.random(), PermissionAction.VIEW)
        val secondId = UUID.random()
        val batch = Batch<UUID, List<EntityPermission>>(listOf(task.id, secondId))
        coEvery { taskRepository.listByProject(task.projectId, 0, 200) } returns listOf(task)
        coEvery { taskRepository.listByAffectedProject(task.projectId, 0, 1) } returns listOf(task)
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { taskRepository.getById(task.id) } returns task.copy(deletedAt = OffsetDateTime.now())
        coEvery { taskRepository.getActiveByKey(task.key) } returns task
        coEvery { taskRepository.getByIds(listOf(task.id)) } returns listOf(task)
        coEvery { permissionRepository.getByTaskId(task.id) } returns listOf(permission)
        coEvery { permissionRepository.getByTaskIds(listOf(task.id, secondId)) } returns listOf(permission)
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { projectPermissionEvaluator.isAllowed(null, project, PermissionAction.EDIT) } returns true

        assertEquals(listOf(task), service.listByProject(task.projectId, -4, 500))
        assertEquals(listOf(task), service.listByAffectedProject(task.projectId, -1, 0))
        assertEquals(task, service.getById(task.id))
        assertTrue(service.getByIdIncludingDeleted(task.id)?.isDeleted == true)
        assertEquals(task, service.getByKey(task.key))
        assertTrue(service.getByIds(emptyList()).isEmpty())
        assertEquals(listOf(task), service.getByIds(listOf(task.id)))
        assertEquals(listOf(permission), service.getPermissions(task))
        service.addPermissionsToBatch(batch)
        assertEquals(listOf(permission), batch.getData(task.id))
        assertEquals(emptyList(), batch.getData(secondId))
        assertTrue(service.isParentAllowed(null, task, PermissionAction.EDIT))

        val orphan = task(projectId = UUID.random())
        coEvery { projectService.getById(orphan.projectId) } returns null
        assertEquals(false, service.isParentAllowed(null, orphan, PermissionAction.VIEW))
    }

    @Test
    fun `history helpers encode every nullable before and after combination`() {
        fun invoke(name: String, before: Any?, after: Any?): List<FieldChange> {
            val changes = mutableListOf<FieldChange>()
            val method = TaskServiceImpl::class.java.declaredMethods.single {
                it.name == name && it.parameterCount == 4
            }.apply { isAccessible = true }
            method.invoke(service, changes, "field", before, after)
            return changes
        }

        listOf("addIfChanged", "addIfChangedUuid", "addIfChangedDate", "addIfChangedLong").forEach { name ->
            val (before, after) = when (name) {
                "addIfChanged" -> "before" to "after"
                "addIfChangedUuid" -> UUID.random() to UUID.random()
                "addIfChangedDate" -> OffsetDateTime.now() to OffsetDateTime.now().plusSeconds(1)
                else -> 41L to 42L
            }
            assertTrue(invoke(name, null, null).isEmpty())
            assertEquals(1, invoke(name, null, after).size)
            assertEquals(1, invoke(name, before, null).size)
            assertEquals(1, invoke(name, before, after).size)
        }
    }

    @Test
    fun `task creation resolves seeded defaults composes fields and attaches sprint and affected projects`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val project = project()
        val status = Status(UUID.random(), "To Do", category = StatusCategory.TODO, colorHex = "#ccc")
        val priority = Priority(UUID.random(), "Medium", iconKey = "medium", colorHex = "#ff0", displayOrder = 2)
        val sprintId = UUID.random()
        val affectedIds = listOf(UUID.random(), UUID.random())
        val fields = mapOf("impact" to JsonPrimitive("high"))
        coEvery { projectService.getById(project.id) } returnsMany listOf(project, null)
        coEvery { statusService.list() } returns listOf(status)
        coEvery { priorityService.list() } returns listOf(priority)
        coEvery {
            customFieldService.composeForCreate(project, TaskServiceImpl.DEFAULT_TASK_TYPE_ID_TASK, fields)
        } returns JsonObject(fields)
        coEvery { projectService.reserveNextTaskSequence(project.id) } returns 12
        coEvery { taskRepository.add(any()) } answers { firstArg<Task>().copy(id = UUID.random(), version = 1) }

        val created = service.create(
            CreateTaskInput(
                projectId = project.id,
                summary = "Ship export",
                sprintId = sprintId,
                customFields = fields,
                affectedProjectIds = affectedIds,
            ),
            principalId,
            profileId,
            profileId,
        )

        assertEquals("WORK-12", created.key)
        assertEquals(TaskServiceImpl.DEFAULT_TASK_TYPE_ID_TASK, created.taskTypeId)
        assertEquals(status.id, created.statusId)
        assertEquals(priority.id, created.priorityId)
        assertEquals(JsonObject(fields), created.customFieldValues)
        coVerify(exactly = 1) { sprintService.addTask(sprintId, created.id) }
        affectedIds.forEach { affectedId ->
            coVerify(exactly = 1) { affectedProjectService.add(created.id, affectedId) }
        }
        coVerify(exactly = 1) { historyRepository.add(created.id, any(), principalId, profileId, any()) }
    }

    @Test
    fun `task creation honors scheme default and explicit type while rejecting stale scheme members`() = runTest {
        val principalId = UUID.random()
        val reporterId = UUID.random()
        val schemeId = UUID.random()
        val defaultTypeId = UUID.random()
        val explicitTypeId = UUID.random()
        val project = project().copy(defaultTaskTypeSchemeId = schemeId)
        val scheme = TaskTypeScheme(
            id = schemeId,
            name = "Engineering",
            taskTypeIds = listOf(defaultTypeId, explicitTypeId),
            defaultTaskTypeId = defaultTypeId,
        )
        val statusId = UUID.random()
        val priorityId = UUID.random()
        coEvery { projectService.getById(project.id) } returns project
        coEvery { taskTypeSchemeService.getById(schemeId) } returns scheme
        coEvery { taskTypeService.getById(defaultTypeId) } returns mockk<TaskType>(relaxed = true)
        coEvery { taskTypeService.getById(explicitTypeId) } returns mockk<TaskType>(relaxed = true)
        coEvery { customFieldService.composeForCreate(project, any(), emptyMap()) } returns JsonObject(emptyMap())
        coEvery { projectService.reserveNextTaskSequence(project.id) } returnsMany listOf(1, 2)
        coEvery { taskRepository.add(any()) } answers { firstArg<Task>().copy(id = UUID.random(), version = 1) }

        val defaulted = service.create(
            CreateTaskInput(projectId = project.id, statusId = statusId, priorityId = priorityId, summary = "Default"),
            principalId,
            null,
            reporterId,
        )
        val explicit = service.create(
            CreateTaskInput(
                projectId = project.id,
                taskTypeId = explicitTypeId,
                statusId = statusId,
                priorityId = priorityId,
                summary = "Explicit",
            ),
            principalId,
            null,
            reporterId,
        )
        assertEquals(defaultTypeId, defaulted.taskTypeId)
        assertEquals(explicitTypeId, explicit.taskTypeId)

        val excludedTypeId = UUID.random()
        assertFailsWith<WorkOpsValidationException> {
            service.create(
                CreateTaskInput(
                    projectId = project.id,
                    taskTypeId = excludedTypeId,
                    statusId = statusId,
                    priorityId = priorityId,
                    summary = "Excluded",
                ),
                principalId,
                null,
                reporterId,
            )
        }

        coEvery { taskTypeService.getById(explicitTypeId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(
                CreateTaskInput(
                    projectId = project.id,
                    taskTypeId = explicitTypeId,
                    statusId = statusId,
                    priorityId = priorityId,
                    summary = "Stale",
                ),
                principalId,
                null,
                reporterId,
            )
        }
    }

    @Test
    fun `task creation reports missing archived and seed configuration states`() = runTest {
        val principalId = UUID.random()
        val reporterId = UUID.random()
        val project = project()
        listOf("   ", "x".repeat(256)).forEach { invalidSummary ->
            assertFailsWith<WorkOpsValidationException> {
                service.create(
                    CreateTaskInput(projectId = project.id, summary = invalidSummary),
                    principalId,
                    null,
                    reporterId,
                )
            }
        }
        coEvery { projectService.getById(project.id) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.create(CreateTaskInput(projectId = project.id, summary = "Missing"), principalId, null, reporterId)
        }

        val archived = project.copy(archivedAt = OffsetDateTime.now())
        coEvery { projectService.getById(project.id) } returns archived
        assertFailsWith<WorkOpsArchivedException> {
            service.create(CreateTaskInput(projectId = project.id, summary = "Archived"), principalId, null, reporterId)
        }

        coEvery { projectService.getById(project.id) } returns project
        coEvery { statusService.list() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(CreateTaskInput(projectId = project.id, summary = "No status"), principalId, null, reporterId)
        }

        val status = Status(UUID.random(), "To Do", category = StatusCategory.TODO, colorHex = "#ccc")
        coEvery { statusService.list() } returns listOf(status)
        coEvery { priorityService.list() } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.create(CreateTaskInput(projectId = project.id, summary = "No priority"), principalId, null, reporterId)
        }

        val missingSchemeId = UUID.random()
        val missingSchemeProject = project.copy(defaultTaskTypeSchemeId = missingSchemeId)
        val priority = Priority(UUID.random(), "Medium", iconKey = "medium", colorHex = "#ff0", displayOrder = 2)
        coEvery { projectService.getById(project.id) } returnsMany listOf(missingSchemeProject, null)
        coEvery { priorityService.list() } returns listOf(priority)
        coEvery { taskTypeSchemeService.getById(missingSchemeId) } returns null
        coEvery {
            customFieldService.composeForCreate(missingSchemeProject, TaskServiceImpl.DEFAULT_TASK_TYPE_ID_TASK, emptyMap())
        } returns JsonObject(emptyMap())
        coEvery { projectService.reserveNextTaskSequence(project.id) } returns 1
        coEvery { taskRepository.add(any()) } answers { firstArg<Task>().copy(id = UUID.random(), version = 1) }

        val defaulted = service.create(
            CreateTaskInput(projectId = project.id, summary = "Missing scheme fallback"),
            principalId,
            null,
            reporterId,
        )
        assertEquals(TaskServiceImpl.DEFAULT_TASK_TYPE_ID_TASK, defaulted.taskTypeId)
    }

    @Test
    fun `due notification dispatch claims each due task and validates claim state`() = runTest {
        val dueAt = OffsetDateTime.parse("2026-08-20T12:00:00Z")
        val first = task(dueDate = dueAt)
        val second = task(dueDate = dueAt.plusDays(1))
        coEvery { taskRepository.findDueForNotification(1) } returns listOf(first, second)
        coEvery { taskRepository.markDueNotified(first.id, dueAt) } returns 1
        coEvery { taskRepository.markDueNotified(second.id, second.dueDate!!) } returns 1

        assertEquals(2, service.dispatchDueNotifications(0))
        coVerify(exactly = 2) { any<NotificationDeliveryRequested>().dispatchNotification() }

        val disappeared = task(dueDate = dueAt)
        coEvery { taskRepository.findDueForNotification(10) } returns listOf(disappeared)
        coEvery { taskRepository.markDueNotified(disappeared.id, dueAt) } returns 0
        assertFailsWith<IllegalStateException> { service.dispatchDueNotifications(10) }

        val invalid = task(dueDate = null)
        coEvery { taskRepository.findDueForNotification(1_000) } returns listOf(invalid)
        assertFailsWith<IllegalArgumentException> { service.dispatchDueNotifications(2_000) }
    }

    @Test
    fun `task history bounds pages`() = runTest {
        val taskId = UUID.random()
        coEvery { historyRepository.listByTask(taskId, 0, 200) } returns emptyList()
        assertTrue(service.listHistory(taskId, -1, 500).isEmpty())
    }

    @Test
    fun `soft delete is idempotent and distinguishes missing from stale tasks`() = runTest {
        val principalId = UUID.random()
        val sprintId = UUID.random()
        val active = task(sprintId = sprintId)
        val deleted = active.copy(deletedAt = OffsetDateTime.now(), version = 2)
        coEvery { taskRepository.softDelete(active.id, principalId, 1) } returns deleted
        coEvery { projectService.getById(active.projectId) } returns null
        assertEquals(deleted, service.softDelete(active.id, 1, principalId, null))
        coVerify { sprintService.removeTask(sprintId, active.id) }

        val alreadyDeleted = task().copy(deletedAt = OffsetDateTime.now())
        coEvery { taskRepository.softDelete(alreadyDeleted.id, principalId, 5) } returns null
        coEvery { taskRepository.getById(alreadyDeleted.id) } returns alreadyDeleted
        coEvery { projectService.getById(alreadyDeleted.projectId) } returns null
        assertSame(alreadyDeleted, service.softDelete(alreadyDeleted.id, 5, principalId, null))

        val missingId = UUID.random()
        coEvery { taskRepository.softDelete(missingId, principalId, 0) } returns null
        coEvery { taskRepository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.softDelete(missingId, 0, principalId, null) }

        val stale = task()
        coEvery { taskRepository.softDelete(stale.id, principalId, 0) } returns null
        coEvery { taskRepository.getById(stale.id) } returns stale
        assertFailsWith<OptimisticLockFailedException> { service.softDelete(stale.id, 0, principalId, null) }
    }

    @Test
    fun `restore is idempotent and distinguishes missing from stale tasks`() = runTest {
        val principalId = UUID.random()
        val deleted = task().copy(deletedAt = OffsetDateTime.now(), version = 2)
        val restored = deleted.copy(deletedAt = null, version = 3)
        coEvery { taskRepository.restore(deleted.id, principalId, 2) } returns restored
        assertEquals(restored, service.restore(deleted.id, 2, principalId, UUID.random()))

        val alreadyActive = task()
        coEvery { taskRepository.restore(alreadyActive.id, principalId, 7) } returns null
        coEvery { taskRepository.getById(alreadyActive.id) } returns alreadyActive
        assertSame(alreadyActive, service.restore(alreadyActive.id, 7, principalId, null))

        val missingId = UUID.random()
        coEvery { taskRepository.restore(missingId, principalId, 0) } returns null
        coEvery { taskRepository.getById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.restore(missingId, 0, principalId, null) }

        coEvery { taskRepository.restore(deleted.id, principalId, 99) } returns null
        coEvery { taskRepository.getById(deleted.id) } returns deleted
        assertFailsWith<OptimisticLockFailedException> { service.restore(deleted.id, 99, principalId, null) }
    }

    @Test
    fun `create document validates ownership and records the new metadata id`() = runTest {
        val principalId = UUID.random()
        val active = task()
        val metadata = mockk<Metadata>()
        val metadataId = UUID.random()
        every { metadata.id } returns metadataId
        val updated = active.copy(metadataId = metadataId, version = 2)
        coEvery { taskRepository.getActiveById(active.id) } returns active
        coEvery { metadataService.add(null, null, any()) } returns metadata
        coEvery { taskRepository.setMetadataId(active.id, metadataId, principalId, 1) } returns updated

        assertEquals(updated, service.createDocument(active.id, 1, principalId, null))

        val missingId = UUID.random()
        coEvery { taskRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> { service.createDocument(missingId, 0, principalId, null) }

        coEvery { taskRepository.getActiveById(updated.id) } returns updated
        assertFailsWith<WorkOpsValidationException> { service.createDocument(updated.id, 2, principalId, null) }

        val lockId = UUID.random()
        val lockTask = active.copy(id = lockId)
        coEvery { taskRepository.getActiveById(lockId) } returns lockTask
        coEvery { taskRepository.setMetadataId(lockId, metadataId, principalId, 3) } returns null
        assertFailsWith<OptimisticLockFailedException> { service.createDocument(lockId, 3, principalId, null) }
    }

    @Test
    fun `task update applies explicit values clears nullable fields and avoids no-op writes`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val oldSprintId = UUID.random()
        val populated = task(sprintId = oldSprintId).copy(
            descriptionMarkdown = "Before",
            assigneeProfileId = UUID.random(),
            parentTaskId = UUID.random(),
            dueDate = OffsetDateTime.parse("2026-08-25T12:00:00Z"),
            startDate = OffsetDateTime.parse("2026-08-20T12:00:00Z"),
            originalEstimateSeconds = 3_600,
            remainingEstimateSeconds = 1_800,
        )
        val cleared = populated.copy(
            summary = "Cleared",
            descriptionMarkdown = "After",
            assigneeProfileId = null,
            parentTaskId = null,
            sprintId = null,
            dueDate = null,
            startDate = null,
            originalEstimateSeconds = null,
            remainingEstimateSeconds = null,
            version = 2,
        )
        coEvery { taskRepository.getActiveById(populated.id) } returns populated
        coEvery {
            taskRepository.updateCore(
                populated.id,
                "Cleared",
                "After",
                populated.descriptionHtml,
                populated.taskTypeId,
                null,
                populated.priorityId,
                populated.statusId,
                null,
                null,
                null,
                null,
                null,
                null,
                principalId,
                1,
            )
        } returns cleared
        coEvery { projectService.getById(populated.projectId) } returns null

        val result = service.update(
            populated.id,
            UpdateTaskInput(
                summary = "Cleared",
                descriptionMarkdown = "After",
                clearAssignee = true,
                clearSprintId = true,
                clearDueDate = true,
                clearStartDate = true,
                clearOriginalEstimate = true,
                clearRemainingEstimate = true,
                clearParentTask = true,
                expectedVersion = 1,
            ),
            principalId,
            profileId,
        )
        assertEquals(cleared, result)
        coVerify { sprintService.removeTask(oldSprintId, populated.id) }
        coVerify(exactly = 1) { historyRepository.add(populated.id, any(), principalId, profileId, any()) }

        val newSprintId = UUID.random()
        val newAssigneeId = UUID.random()
        val newParentId = UUID.random()
        val newPriorityId = UUID.random()
        val dueDate = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val startDate = OffsetDateTime.parse("2026-08-30T12:00:00Z")
        val empty = task().copy(
            descriptionMarkdown = null,
            assigneeProfileId = null,
            parentTaskId = null,
            sprintId = null,
            dueDate = null,
            startDate = null,
            originalEstimateSeconds = null,
            remainingEstimateSeconds = null,
        )
        val populatedResult = empty.copy(
            assigneeProfileId = newAssigneeId,
            parentTaskId = newParentId,
            sprintId = newSprintId,
            dueDate = dueDate,
            startDate = startDate,
            originalEstimateSeconds = 7_200,
            remainingEstimateSeconds = 5_400,
            priorityId = newPriorityId,
            version = 2,
        )
        coEvery { taskRepository.getActiveById(empty.id) } returns empty
        coEvery { taskRepository.updateCore(empty.id, any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), 1) } returns populatedResult
        coEvery { projectService.getById(empty.projectId) } returns null
        assertEquals(
            populatedResult,
            service.update(
                empty.id,
                UpdateTaskInput(
                    assigneeProfileId = newAssigneeId,
                    priorityId = newPriorityId,
                    sprintId = newSprintId,
                    dueDate = dueDate,
                    startDate = startDate,
                    originalEstimateSeconds = 7_200,
                    remainingEstimateSeconds = 5_400,
                    parentTaskId = newParentId,
                    expectedVersion = 1,
                ),
                principalId,
                null,
            ),
        )
        coVerify { sprintService.addTask(newSprintId, empty.id) }

        val unchanged = task()
        coEvery { taskRepository.getActiveById(unchanged.id) } returns unchanged
        assertSame(
            unchanged,
            service.update(unchanged.id, UpdateTaskInput(expectedVersion = 1), principalId, null),
        )
        assertSame(
            unchanged,
            service.update(
                unchanged.id,
                UpdateTaskInput(taskTypeId = unchanged.taskTypeId, expectedVersion = 1),
                principalId,
                null,
            ),
        )
        coVerify(exactly = 0) { taskRepository.updateCore(unchanged.id, any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `task update rejects missing stale invalid and concurrently changed tasks`() = runTest {
        val principalId = UUID.random()
        val missingId = UUID.random()
        coEvery { taskRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(missingId, UpdateTaskInput(expectedVersion = 0), principalId, null)
        }

        val stale = task()
        coEvery { taskRepository.getActiveById(stale.id) } returns stale
        assertFailsWith<OptimisticLockFailedException> {
            service.update(stale.id, UpdateTaskInput(summary = "Changed", expectedVersion = 99), principalId, null)
        }
        assertFailsWith<WorkOpsValidationException> {
            service.update(stale.id, UpdateTaskInput(summary = " ", expectedVersion = 1), principalId, null)
        }
        assertFailsWith<WorkOpsValidationException> {
            service.update(stale.id, UpdateTaskInput(summary = "x".repeat(256), expectedVersion = 1), principalId, null)
        }

        val changedTypeId = UUID.random()
        coEvery { projectService.getById(stale.projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.update(
                stale.id,
                UpdateTaskInput(taskTypeId = changedTypeId, expectedVersion = 1),
                principalId,
                null,
            )
        }

        val lockLost = task()
        coEvery { taskRepository.getActiveById(lockLost.id) } returns lockLost
        coEvery { taskRepository.updateCore(lockLost.id, any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), 1) } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.update(lockLost.id, UpdateTaskInput(summary = "Changed", expectedVersion = 1), principalId, null)
        }
    }

    @Test
    fun `custom field updates distinguish missing stale unscoped no-op and lost writes`() = runTest {
        val principalId = UUID.random()
        val missingId = UUID.random()
        val values = JsonObject(mapOf("impact" to JsonPrimitive("high")))
        coEvery { taskRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.setCustomFieldValues(missingId, values, 1, principalId, null)
        }

        val task = task()
        coEvery { taskRepository.getActiveById(task.id) } returns task
        assertFailsWith<OptimisticLockFailedException> {
            service.setCustomFieldValues(task.id, values, 99, principalId, null)
        }

        coEvery { projectService.getById(task.projectId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.setCustomFieldValues(task.id, values, 1, principalId, null)
        }

        val project = project(task.projectId)
        coEvery { projectService.getById(task.projectId) } returns project
        coEvery { customFieldService.composeForCreate(project, task.taskTypeId, values) } returns task.customFieldValues
        assertSame(task, service.setCustomFieldValues(task.id, values, 1, principalId, null))

        coEvery { customFieldService.composeForCreate(project, task.taskTypeId, values) } returns values
        coEvery { taskRepository.setCustomFieldValues(task.id, values, principalId, 1) } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.setCustomFieldValues(task.id, values, 1, principalId, null)
        }
    }

    @Test
    fun `task transition applies the supported post function plan as one audited change`() = runTest {
        val principalId = UUID.random()
        val profileId = UUID.random()
        val task = task().copy(
            descriptionMarkdown = "Before",
            assigneeProfileId = UUID.random(),
            resolutionId = UUID.random(),
            resolutionAt = OffsetDateTime.parse("2026-08-01T12:00:00Z"),
        )
        val workflowId = UUID.random()
        val currentStateId = UUID.random()
        val targetStateId = UUID.random()
        val targetStatusId = UUID.random()
        val transitionId = UUID.random()
        val priorityId = UUID.random()
        val dueDate = OffsetDateTime.parse("2026-09-10T12:00:00Z")
        val startDate = OffsetDateTime.parse("2026-09-01T12:00:00Z")
        val postFunctions = listOf(
            PostFunction.SetField("summary", JsonPrimitive("After")),
            PostFunction.SetField("description_markdown", JsonPrimitive("Details")),
            PostFunction.SetField("priority_id", JsonPrimitive(priorityId.toString())),
            PostFunction.SetField("assignee_profile_id", JsonPrimitive(UUID.random().toString())),
            PostFunction.SetField("due_date", JsonPrimitive(dueDate.toString())),
            PostFunction.SetField("start_date", JsonPrimitive(startDate.toString())),
            PostFunction.SetResolution(UUID.random()),
            PostFunction.AssignToReporter,
            PostFunction.AssignToCurrentUser,
            PostFunction.Unassign,
            PostFunction.ClearResolution,
        )
        val transition = WorkflowTransition(
            id = transitionId,
            workflowId = workflowId,
            name = "Complete",
            fromStateIds = listOf(currentStateId.toString()),
            toStateId = targetStateId,
            conditions = JsonArray(emptyList()),
            validators = JsonArray(emptyList()),
            postFunctions = json.encodeToJsonElement(ListSerializer(PostFunction.serializer()), postFunctions),
        )
        val currentState = WorkflowState(currentStateId, workflowId, task.statusId, 0)
        val targetState = WorkflowState(targetStateId, workflowId, targetStatusId, 1)
        val updated = task.copy(
            statusId = targetStatusId,
            summary = "After",
            descriptionMarkdown = "Details",
            priorityId = priorityId,
            assigneeProfileId = null,
            resolutionId = null,
            resolutionAt = null,
            dueDate = dueDate,
            startDate = startDate,
            version = 2,
        )
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
            Workflow(workflowId, "Workflow"),
            currentState,
            listOf(transition),
        )
        coEvery { workflowService.countUnresolvedSubtasks(task.id) } returns 0
        coEvery { workflowService.listStates(workflowId) } returns listOf(targetState)
        coEvery {
            taskRepository.applyTransition(
                task.id,
                targetStatusId,
                null,
                null,
                null,
                "After",
                "Details",
                priorityId,
                dueDate,
                startDate,
                task.parentTaskId,
                task.epicTaskId,
                principalId,
                1,
            )
        } returns updated
        val requirementService = mockk<RequirementService>(relaxed = true)
        coEvery { requirementProvider.get() } returns requirementService
        coEvery { projectService.getById(task.projectId) } returns null

        assertEquals(
            updated,
            service.transition(
                task.id,
                transitionId,
                1,
                principalId,
                profileId,
                setOf(PermissionAction.EDIT),
                UUID.random(),
                "Done",
            ),
        )
        coVerify { requirementService.syncStatusFromTask(updated.id, targetStatusId, principalId, profileId) }
        coVerify(exactly = 1) { historyRepository.add(task.id, any(), principalId, profileId, any()) }
    }

    @Test
    fun `task transition rejects unavailable incomplete unsupported and stale plans`() = runTest {
        val principalId = UUID.random()
        val missingId = UUID.random()
        coEvery { taskRepository.getActiveById(missingId) } returns null
        assertFailsWith<WorkOpsNotFoundException> {
            service.transition(missingId, UUID.random(), 0, principalId, null, emptySet(), null, null)
        }

        val task = task()
        val workflowId = UUID.random()
        val currentStateId = UUID.random()
        val targetStateId = UUID.random()
        val transitionId = UUID.random()
        val workflow = Workflow(workflowId, "Workflow")
        val currentState = WorkflowState(currentStateId, workflowId, task.statusId, 0)
        coEvery { taskRepository.getActiveById(task.id) } returns task
        coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
            workflow,
            currentState,
            emptyList(),
        )
        assertFailsWith<WorkflowTransitionNotAvailableException> {
            service.transition(task.id, transitionId, 1, principalId, null, emptySet(), null, null)
        }

        fun transition(from: List<String>, postFunctions: List<PostFunction> = emptyList()) = WorkflowTransition(
            id = transitionId,
            workflowId = workflowId,
            name = "Move",
            fromStateIds = from,
            toStateId = targetStateId,
            postFunctions = json.encodeToJsonElement(ListSerializer(PostFunction.serializer()), postFunctions),
        )
        val unreachable = transition(listOf(UUID.random().toString()))
        coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
            workflow,
            currentState,
            listOf(unreachable),
        )
        assertFailsWith<WorkflowTransitionNotAvailableException> {
            service.transition(task.id, transitionId, 1, principalId, null, emptySet(), null, null)
        }

        coEvery { workflowService.countUnresolvedSubtasks(task.id) } returns 0
        val reachable = transition(listOf(currentStateId.toString()))
        coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
            workflow,
            currentState,
            listOf(reachable),
        )
        coEvery { workflowService.listStates(workflowId) } returns emptyList()
        assertFailsWith<IllegalStateException> {
            service.transition(task.id, transitionId, 1, principalId, null, emptySet(), null, null)
        }

        val targetState = WorkflowState(targetStateId, workflowId, UUID.random(), 1)
        coEvery { workflowService.listStates(workflowId) } returns listOf(targetState)
        listOf(
            PostFunction.SetField("unsupported", JsonPrimitive("value")),
            PostFunction.AddComment("comment"),
            PostFunction.EmitWebhook(UUID.random()),
            PostFunction.RunScript("script"),
        ).forEach { postFunction ->
            val unsupported = transition(listOf(currentStateId.toString()), listOf(postFunction))
            coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
                workflow,
                currentState,
                listOf(unsupported),
            )
            assertFailsWith<PendingPhaseImplementationException> {
                service.transition(task.id, transitionId, 1, principalId, null, emptySet(), null, null)
            }
        }

        val nullableFields = transition(
            listOf(currentStateId.toString()),
            listOf(
                PostFunction.SetField("assignee_profile_id", JsonNull),
                PostFunction.SetField("due_date", JsonNull),
                PostFunction.SetField("start_date", JsonNull),
            ),
        )
        coEvery { workflowService.resolveWorkflowForTask(task) } returns WorkflowResolution(
            workflow,
            currentState,
            listOf(nullableFields),
        )
        coEvery {
            taskRepository.applyTransition(
                task.id,
                targetState.statusId,
                null,
                task.resolutionId,
                task.resolutionAt,
                task.summary,
                task.descriptionMarkdown,
                task.priorityId,
                null,
                null,
                task.parentTaskId,
                task.epicTaskId,
                principalId,
                1,
            )
        } returns null
        assertFailsWith<OptimisticLockFailedException> {
            service.transition(task.id, transitionId, 1, principalId, null, emptySet(), null, null)
        }
    }

    private fun task(
        id: UUID = UUID.random(),
        projectId: UUID = UUID.random(),
        dueDate: OffsetDateTime? = null,
        sprintId: UUID? = null,
    ) = Task(
        id = id,
        key = "WORK-1",
        projectId = projectId,
        taskTypeId = UUID.random(),
        statusId = UUID.random(),
        priorityId = UUID.random(),
        summary = "Work",
        reporterProfileId = UUID.random(),
        sprintId = sprintId,
        dueDate = dueDate,
        createdByPrincipalId = UUID.random(),
        modifiedByPrincipalId = UUID.random(),
        version = 1,
    )

    private fun project(id: UUID = UUID.random()) = Project(
        id = id,
        programId = UUID.random(),
        key = "WORK",
        name = "Work",
        ownerProfileId = UUID.random(),
    )
}
