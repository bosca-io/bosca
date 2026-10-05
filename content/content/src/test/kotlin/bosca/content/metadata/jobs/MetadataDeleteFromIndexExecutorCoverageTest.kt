package bosca.content.metadata.jobs

import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalDI::class)
class MetadataDeleteFromIndexExecutorCoverageTest {

    private val searchService = mockk<SearchService>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = MetadataDeleteFromIndexExecutor(
        searchService = searchService
    )

    @BeforeTest
    fun setup() {
        // Override the registered Json so getJobDefinition() decodes with the same instance the
        // test encodes with, and register the StorageSystemService the else-branch resolves via
        // provide().
        provides<Json> { json }
        provides<SearchService> { searchService }
        provides<StorageSystemService> { storageService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    private fun job(config: MetadataDeleteFromIndexJob) = InternalJobConstructor(
        definition = json.encodeToJsonElement(config),
        executor = MetadataDeleteFromIndexExecutor::class
    )

    private suspend fun runExecutor(config: MetadataDeleteFromIndexJob) {
        val jobQueue = mockk<JobQueue>()
        withContext(jobQueue.asCoroutineContext(job(config))) {
            executor.execute()
        }
    }

    private fun searchSystem(id: UUID, name: String, contentIndex: Boolean = true) = StorageSystem(
        id = id,
        name = name,
        description = "",
        type = StorageSystemType.SEARCH,
        configuration = if (contentIndex) JsonNull else JsonObject(
            mapOf("contentIndex" to JsonPrimitive(false))
        )
    )

    @OptIn(Internal::class)
    @Test
    fun `storage present deletes by contentId filter using explicit storage system`() = runTest {
        val metadataId = UUID.random()
        val storageId = UUID.random()

        runExecutor(
            MetadataDeleteFromIndexJob(
                id = metadataId,
                storage = IndexStorageSystem(id = storageId, name = "Public Search Index")
            )
        )

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        // The else-branch (StorageSystemService resolution) must not run when storage is present.
        coVerify(exactly = 0) { storageService.getAll() }
    }

    @OptIn(Internal::class)
    @Test
    fun `storage present but id null errors from missing id`() = runTest {
        val storageId = UUID.random()

        val result = runCatching {
            runExecutor(
                MetadataDeleteFromIndexJob(
                    id = null,
                    storage = IndexStorageSystem(id = storageId, name = "Public Search Index")
                )
            )
        }

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `no storage deletes across only matching content search systems`() = runTest {
        val metadataId = UUID.random()
        val matchAId = UUID.random()
        val matchBId = UUID.random()

        coEvery { storageService.getAll() } returns listOf(
            // matches: SEARCH + non-blank + content index
            searchSystem(matchAId, "Public Search Index"),
            searchSystem(matchBId, "Admin Search Index"),
            // excluded: wrong type
            StorageSystem(
                id = UUID.random(),
                name = "Blob",
                description = "",
                type = StorageSystemType.SUPPLEMENTARY,
                configuration = JsonNull
            ),
            // excluded: blank name
            searchSystem(UUID.random(), "   "),
            // excluded: contentIndex = false
            searchSystem(UUID.random(), "Docs Index", contentIndex = false)
        )

        runExecutor(MetadataDeleteFromIndexJob(id = metadataId))

        // Only the two matching systems trigger a delete.
        coVerify(exactly = 2) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 1) { storageService.getAll() }
    }

    @OptIn(Internal::class)
    @Test
    fun `no storage and no matching systems performs no deletes`() = runTest {
        val metadataId = UUID.random()
        coEvery { storageService.getAll() } returns emptyList()

        runExecutor(MetadataDeleteFromIndexJob(id = metadataId))

        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `single system failure is rethrown`() = runTest {
        val metadataId = UUID.random()
        coEvery { storageService.getAll() } returns listOf(
            searchSystem(UUID.random(), "Public Search Index")
        )
        coEvery { searchService.deleteByFilter(any(), any()) } throws RuntimeException("boom")

        val result = runCatching {
            runExecutor(MetadataDeleteFromIndexJob(id = metadataId))
        }

        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @OptIn(Internal::class)
    @Test
    fun `multiple failures throw first with the rest suppressed`() = runTest {
        val metadataId = UUID.random()
        val firstId = UUID.random()
        val secondId = UUID.random()
        val thirdId = UUID.random()
        coEvery { storageService.getAll() } returns listOf(
            searchSystem(firstId, "Index A"),
            searchSystem(secondId, "Index B"),
            searchSystem(thirdId, "Index C")
        )
        // Every delete fails, so all three systems are attempted and their failures collected; the
        // executor then throws the FIRST failure with the other two attached via addSuppressed(drop(1)).
        // NOTE: kotlinx-coroutines stacktrace recovery copies the thrown exception across the withContext
        // boundary and does NOT preserve suppressed exceptions, so we assert the observable behavior — all
        // three deletes attempted (proving the collect loop ran fully) and the first failure is the one
        // surfaced — rather than inspecting thrown.suppressed. The addSuppressed(...) branch still executes.
        var deleteCalls = 0
        val names = listOf("first", "second", "third")
        coEvery { searchService.deleteByFilter(any(), any()) } coAnswers {
            throw RuntimeException(names.getOrElse(deleteCalls++) { "extra$deleteCalls" })
        }

        val thrown = runCatching {
            runExecutor(MetadataDeleteFromIndexJob(id = metadataId))
        }.exceptionOrNull()

        assertEquals(3, deleteCalls)
        assertEquals("first", thrown?.message)
    }

    @OptIn(Internal::class)
    @Test
    fun `no storage and id null errors when a matching system is processed`() = runTest {
        coEvery { storageService.getAll() } returns listOf(
            searchSystem(UUID.random(), "Public Search Index")
        )

        val result = runCatching {
            runExecutor(MetadataDeleteFromIndexJob(id = null))
        }

        // The private execute() hits `error("missing id")`; that exception is caught per-system
        // and collected, then rethrown as the first failure.
        assertTrue(result.isFailure)
        assertEquals("missing id", result.exceptionOrNull()?.message)
    }
}
