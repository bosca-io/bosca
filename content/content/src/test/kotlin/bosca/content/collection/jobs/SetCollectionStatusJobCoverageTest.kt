package bosca.content.collection.jobs

import bosca.content.collection.model.Collection
import bosca.content.collection.service.CollectionService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

@OptIn(InternalDI::class)
class SetCollectionStatusJobCoverageTest {

    private val collectionService = mockk<CollectionService>()

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val executor = SetCollectionStatusJobExecutor(collectionService)

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        // AbstractJobExecutor.getJobDefinition() decodes the definition via the DI-provided Json.
        provides<Json> { json }
    }

    @AfterTest
    fun tearDown() {
        clearAllMocks()
        unmockkAll()
        ProviderRegistry.clear()
    }

    private fun collection(id: UUID): Collection = Collection(
        id = id,
        name = "collection",
        languageTag = "en",
        workflowStateId = "published",
        public = true,
        deleted = false,
        searchable = true,
    )

    @OptIn(Internal::class)
    private suspend fun run(config: SetCollectionStatusJob) {
        val jobQueue = mockk<JobQueue>()
        val job = InternalJobConstructor(
            definition = json.encodeToJsonElement(config),
            executor = SetCollectionStatusJobExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(job)) {
            executor.execute()
        }
    }

    @OptIn(Internal::class)
    @Test
    fun `returns early and sets nothing when collection not found`() = runTest {
        val id = UUID.random()
        coEvery { collectionService.getById(id) } returns null

        run(
            SetCollectionStatusJob(
                id = id,
                public = true,
                publicList = true,
                publicSupplementary = true,
                languageTag = "en",
            )
        )

        coVerify(exactly = 1) { collectionService.getById(id) }
        coVerify(exactly = 0) { collectionService.setPublic(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setPublicList(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setPublicSupplementary(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `sets all three flags when all present`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col
        coEvery { collectionService.setPublic(col.id, true, "es") } returns Unit
        coEvery { collectionService.setPublicList(col.id, false, "es") } returns Unit
        coEvery { collectionService.setPublicSupplementary(col.id, true, "es") } returns Unit

        run(
            SetCollectionStatusJob(
                id = id,
                public = true,
                publicList = false,
                publicSupplementary = true,
                languageTag = "es",
            )
        )

        coVerify(exactly = 1) { collectionService.setPublic(col.id, true, "es") }
        coVerify(exactly = 1) { collectionService.setPublicList(col.id, false, "es") }
        coVerify(exactly = 1) { collectionService.setPublicSupplementary(col.id, true, "es") }
    }

    @OptIn(Internal::class)
    @Test
    fun `sets nothing when all flags null`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col

        run(
            SetCollectionStatusJob(
                id = id,
                public = null,
                publicList = null,
                publicSupplementary = null,
            )
        )

        coVerify(exactly = 1) { collectionService.getById(id) }
        coVerify(exactly = 0) { collectionService.setPublic(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setPublicList(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setPublicSupplementary(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `sets only public when only public present with null languageTag`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col
        coEvery { collectionService.setPublic(col.id, false, null) } returns Unit

        run(
            SetCollectionStatusJob(
                id = id,
                public = false,
                publicList = null,
                publicSupplementary = null,
            )
        )

        coVerify(exactly = 1) { collectionService.setPublic(col.id, false, null) }
        coVerify(exactly = 0) { collectionService.setPublicList(any(), any(), any()) }
        coVerify(exactly = 0) { collectionService.setPublicSupplementary(any(), any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `sets only publicList and publicSupplementary when public null`() = runTest {
        val id = UUID.random()
        val col = collection(id)
        coEvery { collectionService.getById(id) } returns col
        coEvery { collectionService.setPublicList(col.id, true, "fr") } returns Unit
        coEvery { collectionService.setPublicSupplementary(col.id, false, "fr") } returns Unit

        run(
            SetCollectionStatusJob(
                id = id,
                public = null,
                publicList = true,
                publicSupplementary = false,
                languageTag = "fr",
            )
        )

        coVerify(exactly = 0) { collectionService.setPublic(any(), any(), any()) }
        coVerify(exactly = 1) { collectionService.setPublicList(col.id, true, "fr") }
        coVerify(exactly = 1) { collectionService.setPublicSupplementary(col.id, false, "fr") }
    }
}
