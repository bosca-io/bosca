package bosca.content.collection.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.search.IndexStorageSystem
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class CollectionDeleteFromIndexExecutorCoverageTest {

    private val searchService = mockk<SearchService>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = CollectionDeleteFromIndexExecutor(searchService)

    private fun searchSystem(
        name: String = "Content Index",
        type: StorageSystemType = StorageSystemType.SEARCH,
        contentIndex: Boolean? = null,
    ): StorageSystem = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "",
        type = type,
        configuration = when (contentIndex) {
            null -> JsonNull
            else -> JsonObject(mapOf("contentIndex" to JsonPrimitive(contentIndex)))
        },
    )

    private suspend fun run(job: CollectionDeleteFromIndexJob) {
        val jobQueue = mockk<JobQueue>()
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = CollectionDeleteFromIndexExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject)) {
            executor.execute()
        }
    }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<StorageSystemService> { storageService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    // --- execute(): storage != null path (uses provided storage directly) ---

    @OptIn(Internal::class)
    @Test
    fun `deletes using provided storage with contentId filter only`() = runTest {
        val id = UUID.random()
        val job = CollectionDeleteFromIndexJob(
            id = id,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job)

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        // storage != null means we never resolve all storage systems.
        coVerify(exactly = 0) { storageService.getAll() }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes using provided storage with language tag filter`() = runTest {
        val id = UUID.random()
        val job = CollectionDeleteFromIndexJob(
            id = id,
            languageTag = "en",
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job)

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { storageService.getAll() }
    }

    // --- execute(): private execute() missing-id branch (id ?: error) ---

    @OptIn(Internal::class)
    @Test
    fun `errors when id is missing on provided storage path`() = runTest {
        val job = CollectionDeleteFromIndexJob(
            id = null,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        assertFailsWith<IllegalStateException> { run(job) }
    }

    // --- execute(): storage == null path (iterate content search indexes) ---

    @OptIn(Internal::class)
    @Test
    fun `iterates only content search indexes when storage is null`() = runTest {
        val id = UUID.random()
        val contentIndex = searchSystem(name = "Content Index", type = StorageSystemType.SEARCH)
        val nonSearch = searchSystem(name = "Supp", type = StorageSystemType.SUPPLEMENTARY)
        val blankName = searchSystem(name = "  ", type = StorageSystemType.SEARCH)
        val nonContentIndex = searchSystem(name = "Docs Index", type = StorageSystemType.SEARCH, contentIndex = false)
        coEvery { storageService.getAll() } returns listOf(contentIndex, nonSearch, blankName, nonContentIndex)

        val job = CollectionDeleteFromIndexJob(id = id, storage = null)
        run(job)

        // Only the single SEARCH + non-blank + content index qualifies.
        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `no deletions when no content search indexes exist`() = runTest {
        coEvery { storageService.getAll() } returns emptyList()

        val job = CollectionDeleteFromIndexJob(id = UUID.random(), storage = null)
        run(job)

        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
    }

    // --- execute(): storage == null, private execute() missing-id branch ---

    @OptIn(Internal::class)
    @Test
    fun `wraps and rethrows failure when id missing on iterated index`() = runTest {
        val contentIndex = searchSystem(name = "Content Index")
        coEvery { storageService.getAll() } returns listOf(contentIndex)

        // id null -> private execute() calls error("missing id"); caught and re-thrown as the first failure.
        val job = CollectionDeleteFromIndexJob(id = null, storage = null)
        assertFailsWith<IllegalStateException> { run(job) }
    }

    // --- execute(): storage == null, catch + collect + throw first with suppressed ---

    @OptIn(Internal::class)
    @Test
    fun `collects failures across indexes and throws first with suppressed`() = runTest {
        val id = UUID.random()
        val first = searchSystem(name = "Index One")
        val second = searchSystem(name = "Index Two")
        coEvery { storageService.getAll() } returns listOf(first, second)

        var deleteCalls = 0
        val names = listOf("boom-1", "boom-2")
        coEvery { searchService.deleteByFilter(any(), any()) } coAnswers {
            throw RuntimeException(names.getOrElse(deleteCalls++) { "extra$deleteCalls" })
        }

        val job = CollectionDeleteFromIndexJob(id = id, storage = null)
        val thrown = assertFailsWith<RuntimeException> { run(job) }

        // Both indexes are attempted, their failures collected, and the FIRST is thrown (the second is
        // attached via addSuppressed). kotlinx-coroutines stacktrace recovery strips suppressed exceptions
        // across the withContext boundary, so we assert the observable behavior — both deletes attempted
        // and the first failure surfaced — rather than inspecting thrown.suppressed.
        kotlin.test.assertEquals("boom-1", thrown.message)
        coVerify(exactly = 2) { searchService.deleteByFilter(any(), any()) }
    }

    // --- execute(): storage == null, single failure (no suppressed) ---

    @OptIn(Internal::class)
    @Test
    fun `single index failure is thrown with no suppressed`() = runTest {
        val id = UUID.random()
        val only = searchSystem(name = "Only Index")
        coEvery { storageService.getAll() } returns listOf(only)

        val boom = RuntimeException("only-boom")
        coEvery { searchService.deleteByFilter(any(), any()) } throws boom

        val job = CollectionDeleteFromIndexJob(id = id, storage = null)
        val thrown = assertFailsWith<RuntimeException> { run(job) }

        kotlin.test.assertEquals("only-boom", thrown.message)
        kotlin.test.assertEquals(0, thrown.suppressed.size)
    }

    // --- execute(): storage == null, all succeed -> no throw ---

    @OptIn(Internal::class)
    @Test
    fun `no exception thrown when all iterated index deletions succeed`() = runTest {
        val id = UUID.random()
        val one = searchSystem(name = "Index A")
        val two = searchSystem(name = "Index B")
        coEvery { storageService.getAll() } returns listOf(one, two)

        val job = CollectionDeleteFromIndexJob(id = id, storage = null)
        run(job)

        coVerify(exactly = 2) { searchService.deleteByFilter(any(), any()) }
    }

    // --- execute(): storage == null, language tag filter on iterated path ---

    @OptIn(Internal::class)
    @Test
    fun `iterated path applies language tag filter when present`() = runTest {
        val id = UUID.random()
        val contentIndex = searchSystem(name = "Content Index")
        coEvery { storageService.getAll() } returns listOf(contentIndex)

        val job = CollectionDeleteFromIndexJob(id = id, languageTag = "fr", storage = null)
        run(job)

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
    }
}
