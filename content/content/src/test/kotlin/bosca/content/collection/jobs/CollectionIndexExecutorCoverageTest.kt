package bosca.content.collection.jobs

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.content.collection.model.Collection
import bosca.content.collection.model.CollectionLanguageVariant
import bosca.content.collection.service.CollectionService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.search.IndexStorageSystem
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ConfigValue
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import bosca.transformations.Transformation
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class CollectionIndexExecutorCoverageTest {

    private val transform =
        mockk<Transformation<IndexStorageSystem, Collection, List<JsonElement>>>()
    private val distributedLock = mockk<DistributedLockFactory>()
    private val application = mockk<BoscaApplication>(relaxed = true)

    private val searchService = mockk<SearchService>(relaxed = true)
    private val collectionService = mockk<CollectionService>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun executor(batchSizeConfig: String? = null): CollectionIndexExecutor {
        every { application.environment.config.propertyOrNull("content.index.batchSize") } returns
            batchSizeConfig?.let { ConfigValue(JsonPrimitive(it), json) }
        return CollectionIndexExecutor(transform, distributedLock, application)
    }

    private fun collection(
        id: UUID = UUID.random(),
        public: Boolean = true,
        workflowStateId: String = "published",
        deleted: Boolean = false,
        searchable: Boolean = true,
        languageTag: String = "en",
    ): Collection = Collection(
        id = id,
        name = "collection",
        languageTag = languageTag,
        workflowStateId = workflowStateId,
        public = public,
        deleted = deleted,
        searchable = searchable,
    )

    private fun variant(
        id: UUID = UUID.random(),
        public: Boolean = true,
        workflowStateId: String = "published",
        languageTag: String = "en",
    ): CollectionLanguageVariant = CollectionLanguageVariant(
        id = id,
        languageTag = languageTag,
        name = "variant",
        workflowStateId = workflowStateId,
        public = public,
    )

    private fun searchSystem(
        name: String = "Search Index",
        type: StorageSystemType = StorageSystemType.SEARCH,
    ): StorageSystem = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "",
        type = type,
        configuration = JsonNull,
    )

    @OptIn(Internal::class)
    private suspend fun run(job: CollectionIndexJob, exec: CollectionIndexExecutor) {
        val jobQueue = mockk<JobQueue>()
        val requestCache = RequestCache(mockk(relaxed = true), mockk(relaxed = true))
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = CollectionIndexExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject) + requestCache.asCoroutineContext()) {
            exec.execute()
        }
    }

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        provides<SearchService> { searchService }
        provides<CollectionService> { collectionService }
        provides<StorageSystemService> { storageService }
    }

    @AfterTest
    fun tearDown() {
        ProviderRegistry.clear()
        clearAllMocks()
        unmockkAll()
    }

    // --- execute() top-level: storage != null, name == null, id present ---

    @OptIn(Internal::class)
    @Test
    fun `resolves storage system by id when storage name is null`() = runTest {
        val storageId = UUID.random()
        val collectionId = UUID.random()
        val system = searchSystem(name = "Some Index")
        coEvery { storageService.get(storageId) } returns system

        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        val job = CollectionIndexJob(id = collectionId, storage = IndexStorageSystem(id = storageId, name = null))
        run(job, executor())

        coVerify(exactly = 1) { storageService.get(storageId) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when storage id is missing and name is null`() = runTest {
        val job = CollectionIndexJob(storage = IndexStorageSystem(id = null, name = null), id = UUID.random())
        val exec = executor()
        assertFailsWith<IllegalStateException> { run(job, exec) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when storage system is not found`() = runTest {
        val storageId = UUID.random()
        coEvery { storageService.get(storageId) } returns null
        val job = CollectionIndexJob(id = UUID.random(), storage = IndexStorageSystem(id = storageId, name = null))
        val exec = executor()
        assertFailsWith<IllegalStateException> { run(job, exec) }
    }

    // --- execute() top-level: storage != null, name != null ---

    @OptIn(Internal::class)
    @Test
    fun `uses provided storage system directly when name is present`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        val job = CollectionIndexJob(id = collectionId, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        coVerify(exactly = 0) { storageService.get(any()) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    // --- execute() top-level: storage == null (getAll filter) ---

    @OptIn(Internal::class)
    @Test
    fun `iterates content search indexes when storage is null`() = runTest {
        val collectionId = UUID.random()
        val searchIndex = searchSystem(name = "Content Index", type = StorageSystemType.SEARCH)
        val nonSearch = searchSystem(name = "Supp", type = StorageSystemType.SUPPLEMENTARY)
        coEvery { storageService.getAll() } returns listOf(searchIndex, nonSearch)

        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        val job = CollectionIndexJob(id = collectionId, storage = null)
        run(job, executor())

        // Only the SEARCH + content index should be used to index.
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    // --- IndexStorageSystem.execute(): deleteFirst branch ---

    @OptIn(Internal::class)
    @Test
    fun `deleteFirst clears own document type first`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteFirst = true,
        )
        run(job, executor())

        coVerify(atLeast = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    // --- IndexStorageSystem.execute(): id != null, deleteOnly branch ---

    @OptIn(Internal::class)
    @Test
    fun `deleteOnly with id deletes by contentId filter`() = runTest {
        val collectionId = UUID.random()
        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteOnly = true,
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { collectionService.getById(any()) }
        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
    }

    // --- IndexStorageSystem.execute(): id != null, getById returns null ---

    @OptIn(Internal::class)
    @Test
    fun `returns early when collection not found by id`() = runTest {
        val collectionId = UUID.random()
        coEvery { collectionService.getById(collectionId) } returns null

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
    }

    // --- IndexStorageSystem.execute(): documents == null path (not indexable) ---

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when collection is not indexable`() = runTest {
        val collectionId = UUID.random()
        // Not public -> not indexable on a non-admin index -> documents == null.
        val col = collection(id = collectionId, public = false)
        coEvery { collectionService.getById(collectionId) } returns col

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when deleted collection`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId, deleted = true)
        coEvery { collectionService.getById(collectionId) } returns col

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when not searchable`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId, searchable = false)
        coEvery { collectionService.getById(collectionId) } returns col

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when not published`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId, workflowStateId = "draft")
        coEvery { collectionService.getById(collectionId) } returns col

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), any()) }
        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
    }

    // --- IndexStorageSystem.execute(): documents filtered to null (all JsonNull) ---

    @OptIn(Internal::class)
    @Test
    fun `indexes filtered documents dropping JsonNull entries`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonNull, JsonPrimitive("keep"))
        coEvery { collectionService.getLanguageVariants(collectionId) } returns emptyList()

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("keep"))) }
    }

    // --- IndexStorageSystem.execute(): admin index indexes even non-public ---

    @OptIn(Internal::class)
    @Test
    fun `admin index indexes non-public collections and skips variant cleanup`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId, public = false, workflowStateId = "draft", searchable = false)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Admin Search Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
        // Admin path skips language variant cleanup.
        coVerify(exactly = 0) { collectionService.getLanguageVariants(any()) }
    }

    // --- IndexStorageSystem.execute(): non-admin removes unpublished variants ---

    @OptIn(Internal::class)
    @Test
    fun `non-admin index removes unpublished and non-public variants`() = runTest {
        val collectionId = UUID.random()
        val col = collection(id = collectionId)
        coEvery { collectionService.getById(collectionId) } returns col
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        val keep = variant(public = true, workflowStateId = "published", languageTag = "en")
        val dropUnpublished = variant(public = true, workflowStateId = "draft", languageTag = "fr")
        val dropNonPublic = variant(public = false, workflowStateId = "published", languageTag = "de")
        coEvery { collectionService.getLanguageVariants(collectionId) } returns
            listOf(keep, dropUnpublished, dropNonPublic)

        val job = CollectionIndexJob(
            id = collectionId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
        // Two variants (fr, de) removed; the published/public one is kept.
        coVerify(exactly = 2) { searchService.deleteByFilter(any(), any()) }
    }

    // --- IndexStorageSystem.execute(): id == null, deleteOnly == true -> no-op ---

    @OptIn(Internal::class)
    @Test
    fun `id null and deleteOnly does nothing`() = runTest {
        val job = CollectionIndexJob(
            id = null,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteOnly = true,
        )
        run(job, executor())

        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
        coVerify(exactly = 0) { distributedLock.create(any()) }
    }

    // --- IndexStorageSystem.execute(): id == null, index-all loop ---

    @OptIn(Internal::class)
    @Test
    fun `index-all loop batches and indexes eligible collections`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }

        val eligible = collection(public = true, workflowStateId = "published", searchable = true)
        val ineligible = collection(public = false)
        coEvery { collectionService.getAll(0L, 100) } returns listOf(eligible, ineligible)
        coEvery { collectionService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), eligible) } returns listOf(JsonPrimitive("doc"))

        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        coVerify(exactly = 1) { collectionService.getAll(0L, 100) }
        coVerify(exactly = 1) { collectionService.getAll(100L, 100) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop uses admin path indexing all collections`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }

        val col = collection(public = false, workflowStateId = "draft", searchable = false)
        coEvery { collectionService.getAll(0L, 100) } returns listOf(col)
        coEvery { collectionService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("admin-doc"))

        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Admin Search Index"))
        run(job, executor())

        coVerify(exactly = 1) { transform.transform(any(), col) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("admin-doc"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop swallows indexing exceptions`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }

        val col = collection()
        coEvery { collectionService.getAll(0L, 100) } returns listOf(col)
        coEvery { collectionService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), col) } returns listOf(JsonPrimitive("doc"))
        coEvery { searchService.index(any(), any<List<JsonElement>>()) } throws RuntimeException("boom")

        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        // Should not throw despite the index() failure.
        run(job, executor())

        coVerify(exactly = 1) { collectionService.getAll(100L, 100) }
    }

    // --- batchSize resolution branches ---

    @OptIn(Internal::class)
    @Test
    fun `job batchSize overrides default when positive`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }
        coEvery { collectionService.getAll(0L, 25) } returns emptyList()

        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"), batchSize = 25)
        run(job, executor())

        coVerify(exactly = 1) { collectionService.getAll(0L, 25) }
    }

    @OptIn(Internal::class)
    @Test
    fun `non-positive job batchSize falls back to configured default`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }
        coEvery { collectionService.getAll(0L, 42) } returns emptyList()

        // Config supplies 42; job batchSize of 0 is rejected by takeIf { it > 0 }.
        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"), batchSize = 0)
        run(job, executor(batchSizeConfig = "42"))

        coVerify(exactly = 1) { collectionService.getAll(0L, 42) }
    }

    @OptIn(Internal::class)
    @Test
    fun `invalid config batchSize falls back to hardcoded default`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }
        coEvery { collectionService.getAll(0L, 100) } returns emptyList()

        // Non-numeric config -> toIntOrNull null -> default 100.
        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor(batchSizeConfig = "not-a-number"))

        coVerify(exactly = 1) { collectionService.getAll(0L, 100) }
    }

    @OptIn(Internal::class)
    @Test
    fun `zero config batchSize falls back to hardcoded default`() = runTest {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("collection:index:all") } returns lock
        coEvery { lock.withLock<Unit>(any(), any(), any(), any()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }
        coEvery { collectionService.getAll(0L, 100) } returns emptyList()

        // Config 0 fails takeIf { it > 0 } -> default 100.
        val job = CollectionIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor(batchSizeConfig = "0"))

        coVerify(exactly = 1) { collectionService.getAll(0L, 100) }
    }
}
