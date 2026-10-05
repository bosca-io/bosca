@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.workops.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
import bosca.search.service.SearchService
import bosca.serialization.OffsetDateTime
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.workops.model.project.Project
import bosca.workops.model.search.TaskSearchDocument
import bosca.workops.model.task.Priority
import bosca.workops.model.task.Resolution
import bosca.workops.model.task.Task
import bosca.workops.model.task.TaskType
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.PriorityRepository
import bosca.workops.repository.ProjectRepository
import bosca.workops.repository.ResolutionRepository
import bosca.workops.repository.StatusRepository
import bosca.workops.repository.TaskRepository
import bosca.workops.repository.TaskTypeRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TaskIndexExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>(relaxed = true)
    private val searchService = mockk<SearchService>()
    private val storageService = mockk<StorageSystemService>()
    private val taskRepository = mockk<TaskRepository>()
    private val projectRepository = mockk<ProjectRepository>()
    private val taskTypeRepository = mockk<TaskTypeRepository>()
    private val statusRepository = mockk<StatusRepository>()
    private val priorityRepository = mockk<PriorityRepository>()
    private val resolutionRepository = mockk<ResolutionRepository>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SearchService>(singleton = true) { searchService }
        provides<StorageSystemService>(singleton = true) { storageService }
        provides<TaskRepository>(singleton = true) { taskRepository }
        provides<ProjectRepository>(singleton = true) { projectRepository }
        provides<TaskTypeRepository>(singleton = true) { taskTypeRepository }
        provides<StatusRepository>(singleton = true) { statusRepository }
        provides<PriorityRepository>(singleton = true) { priorityRepository }
        provides<ResolutionRepository>(singleton = true) { resolutionRepository }
        coEvery { searchService.deleteByFilter(any(), any()) } just Runs
        coEvery { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) } just Runs
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `missing task id exits before resolving index services`() = runTest {
        execute(TaskIndexJob())

        coVerify(exactly = 0) { storageService.getAll() }
        coVerify(exactly = 0) { taskRepository.getById(any()) }
    }

    @Test
    fun `delete only targets every named search system and filters the rest`() = runTest {
        val taskId = UUID.random()
        val first = storage("tasks-primary", StorageSystemType.SEARCH)
        val second = storage("tasks-secondary", StorageSystemType.SEARCH)
        coEvery { storageService.getAll() } returns listOf(
            first,
            storage("", StorageSystemType.SEARCH),
            storage("archive", StorageSystemType.SUPPLEMENTARY),
            second,
        )

        execute(TaskIndexJob(taskId = taskId, deleteOnly = true))

        coVerify(exactly = 1) {
            searchService.deleteByFilter(
                IndexStorageSystem(first.id, first.name),
                SearchFilter.eq("id", taskId.toString()),
            )
        }
        coVerify(exactly = 1) {
            searchService.deleteByFilter(
                IndexStorageSystem(second.id, second.name),
                SearchFilter.eq("id", taskId.toString()),
            )
        }
        coVerify(exactly = 0) { taskRepository.getById(any()) }
    }

    @Test
    fun `missing and deleted tasks remove stale search documents`() = runTest {
        val taskId = UUID.random()
        val system = storage("tasks", StorageSystemType.SEARCH)
        val deleted = task(id = taskId).copy(deletedAt = OffsetDateTime.now())
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { taskRepository.getById(taskId) } returnsMany listOf(null, deleted)

        execute(TaskIndexJob(taskId = taskId))
        execute(TaskIndexJob(taskId = taskId))

        coVerify(exactly = 2) {
            searchService.deleteByFilter(
                IndexStorageSystem(system.id, system.name),
                SearchFilter.eq("id", taskId.toString()),
            )
        }
        coVerify(exactly = 0) { projectRepository.getById(any()) }
    }

    @Test
    fun `active task indexes a fully enriched document`() = runTest {
        val system = storage("tasks", StorageSystemType.SEARCH)
        val resolutionId = UUID.random()
        val task = task(resolutionId = resolutionId).copy(
            descriptionMarkdown = "Delivery details",
            assigneeProfileId = UUID.random(),
            labelIds = listOf(UUID.random()),
            componentIds = listOf(UUID.random()),
            watcherProfileIds = listOf(UUID.random()),
            sprintId = UUID.random(),
            dueDate = OffsetDateTime.now().plusDays(2),
        )
        val project = Project(
            id = task.projectId,
            programId = UUID.random(),
            key = "GIT",
            name = "Git",
            ownerProfileId = UUID.random(),
        )
        val taskType = TaskType(
            id = task.taskTypeId,
            name = "Story",
            iconKey = "story",
            colorHex = "#123456",
        )
        val status = Status(
            id = task.statusId,
            name = "In Review",
            category = StatusCategory.IN_PROGRESS,
            colorHex = "#654321",
        )
        val priority = Priority(
            id = task.priorityId,
            name = "High",
            iconKey = "high",
            colorHex = "#ff0000",
            displayOrder = 4,
        )
        val resolution = Resolution(id = resolutionId, name = "Done", displayOrder = 0)
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { taskRepository.getById(task.id) } returns task
        coEvery { projectRepository.getById(task.projectId) } returns project
        coEvery { taskTypeRepository.getById(task.taskTypeId) } returns taskType
        coEvery { statusRepository.getById(task.statusId) } returns status
        coEvery { priorityRepository.getById(task.priorityId) } returns priority
        coEvery { resolutionRepository.getById(resolutionId) } returns resolution
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(TaskIndexJob(taskId = task.id))

        val indexed = json.decodeFromJsonElement(TaskSearchDocument.serializer(), document.captured)
        assertEquals(task.id.toString(), indexed.id)
        assertEquals("GIT", indexed.projectKey)
        assertEquals("Story", indexed.taskTypeName)
        assertEquals("In Review", indexed.statusName)
        assertEquals("IN_PROGRESS", indexed.statusCategory)
        assertEquals("High", indexed.priorityName)
        assertEquals("Done", indexed.resolutionName)
        assertEquals(task.labelIds.map(UUID::toString), indexed.labelIds)
        assertEquals(task.componentIds.map(UUID::toString), indexed.componentIds)
        assertEquals(task.watcherProfileIds.map(UUID::toString), indexed.watcherProfileIds)
        assertFalse(indexed.deleted)
    }

    @Test
    fun `missing task relationships index stable fallbacks`() = runTest {
        val system = storage("tasks", StorageSystemType.SEARCH)
        val task = task()
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { taskRepository.getById(task.id) } returns task
        coEvery { projectRepository.getById(task.projectId) } returns null
        coEvery { taskTypeRepository.getById(task.taskTypeId) } returns null
        coEvery { statusRepository.getById(task.statusId) } returns null
        coEvery { priorityRepository.getById(task.priorityId) } returns null
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(TaskIndexJob(taskId = task.id))

        val indexed = json.decodeFromJsonElement(TaskSearchDocument.serializer(), document.captured)
        assertEquals("", indexed.projectKey)
        assertEquals("", indexed.taskTypeName)
        assertEquals("", indexed.statusName)
        assertEquals("TODO", indexed.statusCategory)
        assertEquals("", indexed.priorityName)
        assertNull(indexed.resolutionId)
        assertNull(indexed.resolutionName)
        coVerify(exactly = 0) { resolutionRepository.getById(any()) }
    }

    @Test
    fun `missing referenced resolution leaves its search name null`() = runTest {
        val system = storage("tasks", StorageSystemType.SEARCH)
        val resolutionId = UUID.random()
        val task = task(resolutionId = resolutionId)
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { taskRepository.getById(task.id) } returns task
        coEvery { projectRepository.getById(task.projectId) } returns null
        coEvery { taskTypeRepository.getById(task.taskTypeId) } returns null
        coEvery { statusRepository.getById(task.statusId) } returns null
        coEvery { priorityRepository.getById(task.priorityId) } returns null
        coEvery { resolutionRepository.getById(resolutionId) } returns null
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(TaskIndexJob(taskId = task.id))

        val indexed = json.decodeFromJsonElement(TaskSearchDocument.serializer(), document.captured)
        assertEquals(resolutionId, indexed.resolutionId)
        assertNull(indexed.resolutionName)
    }

    private suspend fun execute(definition: TaskIndexJob) {
        val job = Job(definition, TaskIndexExecutor::class)
        withContext(queue.asCoroutineContext(job)) {
            TaskIndexExecutor().execute()
        }
    }

    private fun storage(name: String, type: StorageSystemType) = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "$name storage",
        type = type,
        configuration = JsonObject(emptyMap()),
    )

    private fun task(
        id: UUID = UUID.random(),
        resolutionId: UUID? = null,
    ): Task {
        val principalId = UUID.random()
        return Task(
            id = id,
            key = "GIT-66",
            projectId = UUID.random(),
            taskTypeId = UUID.random(),
            statusId = UUID.random(),
            priorityId = UUID.random(),
            summary = "Raise coverage",
            reporterProfileId = UUID.random(),
            resolutionId = resolutionId,
            resolutionAt = resolutionId?.let { OffsetDateTime.now() },
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }
}
