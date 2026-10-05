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
import bosca.workops.model.search.SpecSearchDocument
import bosca.workops.model.spec.Spec
import bosca.workops.model.workflow.Status
import bosca.workops.model.workflow.StatusCategory
import bosca.workops.repository.StatusRepository
import bosca.workops.service.ProjectService
import bosca.workops.service.SpecService
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

class SpecIndexExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val queue = mockk<JobQueue>(relaxed = true)
    private val searchService = mockk<SearchService>()
    private val storageService = mockk<StorageSystemService>()
    private val specService = mockk<SpecService>()
    private val projectService = mockk<ProjectService>()
    private val statusRepository = mockk<StatusRepository>()

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SearchService>(singleton = true) { searchService }
        provides<StorageSystemService>(singleton = true) { storageService }
        provides<SpecService>(singleton = true) { specService }
        provides<ProjectService>(singleton = true) { projectService }
        provides<StatusRepository>(singleton = true) { statusRepository }
        coEvery { searchService.deleteByFilter(any(), any()) } just Runs
        coEvery { searchService.index(any<IndexStorageSystem>(), any<JsonElement>()) } just Runs
    }

    @AfterTest
    fun teardown() {
        ProviderRegistry.clear()
    }

    @Test
    fun `missing spec id exits before resolving index services`() = runTest {
        execute(SpecIndexJob())

        coVerify(exactly = 0) { storageService.getAll() }
        coVerify(exactly = 0) { specService.getById(any()) }
    }

    @Test
    fun `delete only targets named search systems and ignores ineligible storage`() = runTest {
        val specId = UUID.random()
        val system = storage("specs", StorageSystemType.SEARCH)
        coEvery { storageService.getAll() } returns listOf(
            system,
            storage("", StorageSystemType.SEARCH),
            storage("archive", StorageSystemType.SUPPLEMENTARY),
        )

        execute(SpecIndexJob(specId = specId, deleteOnly = true))

        coVerify(exactly = 1) {
            searchService.deleteByFilter(
                IndexStorageSystem(system.id, system.name),
                SearchFilter.eq("id", specId.toString()),
            )
        }
        coVerify(exactly = 0) { specService.getById(any()) }
    }

    @Test
    fun `missing and deleted specs remove stale search documents`() = runTest {
        val specId = UUID.random()
        val system = storage("specs", StorageSystemType.SEARCH)
        val deleted = spec(id = specId).copy(deletedAt = OffsetDateTime.now())
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { specService.getById(specId) } returnsMany listOf(null, deleted)

        execute(SpecIndexJob(specId = specId))
        execute(SpecIndexJob(specId = specId))

        coVerify(exactly = 2) {
            searchService.deleteByFilter(
                IndexStorageSystem(system.id, system.name),
                SearchFilter.eq("id", specId.toString()),
            )
        }
        coVerify(exactly = 0) { projectService.getById(any()) }
    }

    @Test
    fun `active spec indexes a fully enriched document`() = runTest {
        val system = storage("specs", StorageSystemType.SEARCH)
        val projectId = UUID.random()
        val spec = spec(projectId = projectId).copy(
            programId = UUID.random(),
            parentSpecId = UUID.random(),
            labelIds = listOf(UUID.random()),
            watcherProfileIds = listOf(UUID.random()),
        )
        val project = Project(
            id = projectId,
            programId = UUID.random(),
            key = "GIT",
            name = "Git",
            ownerProfileId = UUID.random(),
        )
        val status = Status(
            id = spec.statusId,
            name = "In Progress",
            category = StatusCategory.IN_PROGRESS,
            colorHex = "#123456",
        )
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { projectService.getById(projectId) } returns project
        coEvery { statusRepository.getById(spec.statusId) } returns status
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(SpecIndexJob(specId = spec.id))

        val indexed = json.decodeFromJsonElement(SpecSearchDocument.serializer(), document.captured)
        assertEquals(spec.id.toString(), indexed.id)
        assertEquals(spec.metadataId, indexed.metadataId)
        assertEquals("GIT", indexed.projectKey)
        assertEquals("In Progress", indexed.statusName)
        assertEquals("IN_PROGRESS", indexed.statusCategory)
        assertEquals(spec.labelIds.map(UUID::toString), indexed.labelIds)
        assertEquals(spec.watcherProfileIds.map(UUID::toString), indexed.watcherProfileIds)
        assertFalse(indexed.deleted)
    }

    @Test
    fun `spec without project and status indexes stable fallbacks`() = runTest {
        val system = storage("specs", StorageSystemType.SEARCH)
        val spec = spec(projectId = null)
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { statusRepository.getById(spec.statusId) } returns null
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(SpecIndexJob(specId = spec.id))

        val indexed = json.decodeFromJsonElement(SpecSearchDocument.serializer(), document.captured)
        assertNull(indexed.projectId)
        assertNull(indexed.projectKey)
        assertEquals("", indexed.statusName)
        assertEquals("TODO", indexed.statusCategory)
        coVerify(exactly = 0) { projectService.getById(any()) }
    }

    @Test
    fun `missing referenced project leaves its search key null`() = runTest {
        val system = storage("specs", StorageSystemType.SEARCH)
        val projectId = UUID.random()
        val spec = spec(projectId = projectId)
        val document = slot<JsonElement>()
        coEvery { storageService.getAll() } returns listOf(system)
        coEvery { specService.getById(spec.id) } returns spec
        coEvery { projectService.getById(projectId) } returns null
        coEvery { statusRepository.getById(spec.statusId) } returns null
        coEvery { searchService.index(any<IndexStorageSystem>(), capture(document)) } just Runs

        execute(SpecIndexJob(specId = spec.id))

        val indexed = json.decodeFromJsonElement(SpecSearchDocument.serializer(), document.captured)
        assertEquals(projectId, indexed.projectId)
        assertNull(indexed.projectKey)
    }

    private suspend fun execute(definition: SpecIndexJob) {
        val job = Job(definition, SpecIndexExecutor::class)
        withContext(queue.asCoroutineContext(job)) {
            SpecIndexExecutor().execute()
        }
    }

    private fun storage(name: String, type: StorageSystemType) = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "$name storage",
        type = type,
        configuration = JsonObject(emptyMap()),
    )

    private fun spec(
        id: UUID = UUID.random(),
        projectId: UUID? = UUID.random(),
    ): Spec {
        val principalId = UUID.random()
        return Spec(
            id = id,
            key = "GIT-SPEC-6",
            metadataId = UUID.random(),
            projectId = projectId,
            statusId = UUID.random(),
            workflowId = UUID.random(),
            ownerProfileId = UUID.random(),
            createdByPrincipalId = principalId,
            modifiedByPrincipalId = principalId,
        )
    }
}
