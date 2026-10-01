package bosca.content.metadata.jobs

import bosca.cache.RequestCache
import bosca.cache.asCoroutineContext
import bosca.content.metadata.model.Metadata
import bosca.content.metadata.model.MetadataType
import bosca.content.metadata.service.MetadataService
import bosca.core.annotations.Internal
import bosca.di.ProviderRegistry
import bosca.di.annotation.InternalDI
import bosca.di.provides
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.search.IndexStorageSystem
import bosca.search.model.SearchFilter
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

@OptIn(InternalDI::class)
class MetadataIndexExecutorCoverageTest {

    private val transform =
        mockk<Transformation<IndexStorageSystem, Metadata, JsonElement?>>()
    private val distributedLock = mockk<DistributedLockFactory>()
    private val application = mockk<BoscaApplication>(relaxed = true)

    private val searchService = mockk<SearchService>(relaxed = true)
    private val metadataService = mockk<MetadataService>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private fun executor(batchSizeConfig: String? = null): MetadataIndexExecutor {
        every { application.environment.config.propertyOrNull("content.index.batchSize") } returns
            batchSizeConfig?.let { ConfigValue(JsonPrimitive(it), json) }
        return MetadataIndexExecutor(transform, distributedLock, application)
    }

    private fun metadata(
        id: UUID = UUID.random(),
        public: Boolean = true,
        workflowStateId: String = "published",
        deleted: Boolean = false,
        searchable: Boolean = true,
    ): Metadata = Metadata(
        id = id,
        name = "item",
        type = MetadataType.STANDARD,
        contentType = "text/plain",
        contentLength = 10L,
        languageTag = "en",
        workflowStateId = workflowStateId,
        public = public,
        deleted = deleted,
        searchable = searchable,
    )

    private fun searchSystem(
        name: String = "Search Index",
        type: StorageSystemType = StorageSystemType.SEARCH,
        configuration: JsonElement = JsonNull,
    ): StorageSystem = StorageSystem(
        id = UUID.random(),
        name = name,
        description = "",
        type = type,
        configuration = configuration,
    )

    private fun stubWithLock() {
        val lock = mockk<DistributedLock>()
        coEvery { distributedLock.create("metadata:index:all") } returns lock
        coEvery { lock.withLock(any<Long>(), any(), any(), any<suspend () -> Unit>()) } coAnswers {
            val block = arg<suspend () -> Unit>(3)
            block()
        }
    }

    private suspend fun run(job: MetadataIndexJob, exec: MetadataIndexExecutor) {
        val jobQueue = mockk<JobQueue>()
        val requestCache = RequestCache(mockk(relaxed = true), mockk(relaxed = true))
        val jobObject = InternalJobConstructor(
            definition = json.encodeToJsonElement(job),
            executor = MetadataIndexExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObject) + requestCache.asCoroutineContext()) {
            exec.execute()
        }
    }

    @BeforeTest
    fun setup() {
        provides<Json> { json }
        provides<SearchService> { searchService }
        provides<MetadataService> { metadataService }
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
        val metadataId = UUID.random()
        coEvery { storageService.get(storageId) } returns searchSystem(name = "Some Index")

        val item = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("doc")

        val job = MetadataIndexJob(id = metadataId, storage = IndexStorageSystem(id = storageId, name = null))
        run(job, executor())

        coVerify(exactly = 1) { storageService.get(storageId) }
        coVerify(exactly = 1) { searchService.index(any(), JsonPrimitive("doc")) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when storage id is missing and name is null`() = runTest {
        val job = MetadataIndexJob(storage = IndexStorageSystem(id = null, name = null), id = UUID.random())
        val exec = executor()
        assertFailsWith<IllegalStateException> { run(job, exec) }
    }

    @OptIn(Internal::class)
    @Test
    fun `errors when storage system is not found`() = runTest {
        val storageId = UUID.random()
        coEvery { storageService.get(storageId) } returns null
        val job = MetadataIndexJob(id = UUID.random(), storage = IndexStorageSystem(id = storageId, name = null))
        val exec = executor()
        assertFailsWith<IllegalStateException> { run(job, exec) }
    }

    // --- execute() top-level: storage != null, name != null ---

    @OptIn(Internal::class)
    @Test
    fun `uses provided storage system directly when name is present`() = runTest {
        val metadataId = UUID.random()
        val item = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("doc")

        val job = MetadataIndexJob(id = metadataId, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        coVerify(exactly = 0) { storageService.get(any()) }
        coVerify(exactly = 1) { searchService.index(any(), JsonPrimitive("doc")) }
    }

    // --- execute() top-level: storage == null (getAll filter) ---

    @OptIn(Internal::class)
    @Test
    fun `iterates only content search indexes when storage is null`() = runTest {
        val metadataId = UUID.random()
        val searchIndex = searchSystem(name = "Content Index", type = StorageSystemType.SEARCH)
        val nonSearch = searchSystem(name = "Supp", type = StorageSystemType.SUPPLEMENTARY)
        val blankName = searchSystem(name = "  ", type = StorageSystemType.SEARCH)
        val nonContentIndex = searchSystem(
            name = "Docs Index",
            type = StorageSystemType.SEARCH,
            configuration = JsonObject(mapOf("contentIndex" to JsonPrimitive(false))),
        )
        coEvery { storageService.getAll() } returns listOf(searchIndex, nonSearch, blankName, nonContentIndex)

        val item = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("doc")

        val job = MetadataIndexJob(id = metadataId, storage = null)
        run(job, executor())

        // Only the single SEARCH + non-blank + content index should be used to index.
        coVerify(exactly = 1) { searchService.index(any(), JsonPrimitive("doc")) }
    }

    // --- IndexStorageSystem.execute(): deleteFirst branch ---

    @OptIn(Internal::class)
    @Test
    fun `deleteFirst clears own metadata document type first`() = runTest {
        val metadataId = UUID.random()
        val item = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("doc")

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteFirst = true,
        )
        run(job, executor())

        coVerify(exactly = 1) { searchService.deleteByFilter(any(), SearchFilter.eq("_type", "metadata")) }
        coVerify(exactly = 1) { searchService.index(any(), JsonPrimitive("doc")) }
    }

    // --- IndexStorageSystem.execute(): id != null, deleteOnly branch ---

    @OptIn(Internal::class)
    @Test
    fun `deleteOnly with id deletes by contentId filter`() = runTest {
        val metadataId = UUID.random()
        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteOnly = true,
        )
        run(job, executor())

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { metadataService.getById(any<UUID>()) }
        coVerify(exactly = 0) { searchService.index(any(), any<JsonElement>()) }
    }

    // --- IndexStorageSystem.execute(): id != null, getById returns null ---

    @OptIn(Internal::class)
    @Test
    fun `returns early when metadata not found by id`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns null

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { metadataService.getById(metadataId) }
        coVerify(exactly = 0) { searchService.index(any(), any<JsonElement>()) }
        coVerify(exactly = 0) { searchService.deleteByFilter(any(), any()) }
    }

    // --- IndexStorageSystem.execute(): document == null path (not indexable) ---

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when metadata is not public`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns metadata(id = metadataId, public = false)

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { searchService.index(any(), any<JsonElement>()) }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when metadata not published`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns
            metadata(id = metadataId, workflowStateId = "draft")

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when metadata deleted`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns metadata(id = metadataId, deleted = true)

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when metadata not searchable`() = runTest {
        val metadataId = UUID.random()
        coEvery { metadataService.getById(metadataId) } returns metadata(id = metadataId, searchable = false)

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { transform.transform(any(), any()) }
    }

    // --- IndexStorageSystem.execute(): transform returns JsonNull -> takeIf null -> deleteByFilter ---

    @OptIn(Internal::class)
    @Test
    fun `deletes by filter when transform returns JsonNull`() = runTest {
        val metadataId = UUID.random()
        val item = metadata(id = metadataId)
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonNull

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { transform.transform(any(), item) }
        coVerify(exactly = 1) {
            searchService.deleteByFilter(any(), SearchFilter.eq("contentId", metadataId.toString()))
        }
        coVerify(exactly = 0) { searchService.index(any(), any<JsonElement>()) }
    }

    // --- IndexStorageSystem.execute(): admin index indexes even non-public/unpublished/unsearchable ---

    @OptIn(Internal::class)
    @Test
    fun `admin index indexes non-public unpublished unsearchable metadata`() = runTest {
        val metadataId = UUID.random()
        val item = metadata(
            id = metadataId,
            public = false,
            workflowStateId = "draft",
            searchable = false,
        )
        coEvery { metadataService.getById(metadataId) } returns item
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("admin-doc")

        val job = MetadataIndexJob(
            id = metadataId,
            storage = IndexStorageSystem(id = UUID.random(), name = "Admin Search Index"),
        )
        run(job, executor())

        coVerify(exactly = 1) { transform.transform(any(), item) }
        coVerify(exactly = 1) { searchService.index(any(), JsonPrimitive("admin-doc")) }
    }

    // --- IndexStorageSystem.execute(): id == null, deleteOnly == true -> no-op ---

    @OptIn(Internal::class)
    @Test
    fun `id null and deleteOnly does nothing`() = runTest {
        val job = MetadataIndexJob(
            id = null,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            deleteOnly = true,
        )
        run(job, executor())

        coVerify(exactly = 0) { searchService.index(any(), any<JsonElement>()) }
        coVerify(exactly = 0) { distributedLock.create(any()) }
        coVerify(exactly = 0) { metadataService.getAll(any(), any()) }
    }

    // --- IndexStorageSystem.execute(): id == null, index-all loop ---

    @OptIn(Internal::class)
    @Test
    fun `index-all loop batches and indexes only eligible metadata`() = runTest {
        stubWithLock()

        val eligible = metadata(public = true, workflowStateId = "published", searchable = true)
        val notPublic = metadata(public = false)
        val deleted = metadata(deleted = true)
        coEvery { metadataService.getAll(0L, 100) } returns listOf(eligible, notPublic, deleted)
        coEvery { metadataService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), eligible) } returns JsonPrimitive("doc")

        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        coVerify(exactly = 1) { metadataService.getAll(0L, 100) }
        coVerify(exactly = 1) { metadataService.getAll(100L, 100) }
        coVerify(exactly = 1) { transform.transform(any(), eligible) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("doc"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop uses admin path indexing all metadata`() = runTest {
        stubWithLock()

        val item = metadata(public = false, workflowStateId = "draft", searchable = false)
        coEvery { metadataService.getAll(0L, 100) } returns listOf(item)
        coEvery { metadataService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("admin-doc")

        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Admin Search Index"))
        run(job, executor())

        coVerify(exactly = 1) { transform.transform(any(), item) }
        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("admin-doc"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop drops JsonNull transform results`() = runTest {
        stubWithLock()

        val a = metadata()
        val b = metadata()
        coEvery { metadataService.getAll(0L, 100) } returns listOf(a, b)
        coEvery { metadataService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), a) } returns JsonNull
        coEvery { transform.transform(any(), b) } returns JsonPrimitive("keep")

        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        coVerify(exactly = 1) { searchService.index(any(), listOf<JsonElement>(JsonPrimitive("keep"))) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop swallows per-item transform exceptions`() = runTest {
        stubWithLock()

        val item = metadata()
        coEvery { metadataService.getAll(0L, 100) } returns listOf(item)
        coEvery { metadataService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), item) } throws RuntimeException("transform boom")

        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor())

        // The failing transform is mapped to null; an empty batch is still indexed.
        coVerify(exactly = 1) { transform.transform(any(), item) }
        coVerify(exactly = 1) { searchService.index(any(), emptyList<JsonElement>()) }
    }

    @OptIn(Internal::class)
    @Test
    fun `index-all loop swallows indexing exceptions and continues`() = runTest {
        stubWithLock()

        val item = metadata()
        coEvery { metadataService.getAll(0L, 100) } returns listOf(item)
        coEvery { metadataService.getAll(100L, 100) } returns emptyList()
        coEvery { transform.transform(any(), item) } returns JsonPrimitive("doc")
        coEvery { searchService.index(any(), any<List<JsonElement>>()) } throws RuntimeException("boom")

        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        // Should not throw despite index() failure.
        run(job, executor())

        coVerify(exactly = 1) { metadataService.getAll(100L, 100) }
    }

    // --- batchSize resolution branches ---

    @OptIn(Internal::class)
    @Test
    fun `job batchSize overrides default when positive`() = runTest {
        stubWithLock()
        coEvery { metadataService.getAll(0L, 25) } returns emptyList()

        val job = MetadataIndexJob(
            id = null,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            batchSize = 25,
        )
        run(job, executor())

        coVerify(exactly = 1) { metadataService.getAll(0L, 25) }
    }

    @OptIn(Internal::class)
    @Test
    fun `non-positive job batchSize falls back to configured default`() = runTest {
        stubWithLock()
        coEvery { metadataService.getAll(0L, 42) } returns emptyList()

        // Config supplies 42; job batchSize of 0 is rejected by takeIf { it > 0 }.
        val job = MetadataIndexJob(
            id = null,
            storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"),
            batchSize = 0,
        )
        run(job, executor(batchSizeConfig = "42"))

        coVerify(exactly = 1) { metadataService.getAll(0L, 42) }
    }

    @OptIn(Internal::class)
    @Test
    fun `invalid config batchSize falls back to hardcoded default`() = runTest {
        stubWithLock()
        coEvery { metadataService.getAll(0L, 100) } returns emptyList()

        // Non-numeric config -> toIntOrNull null -> default 100.
        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor(batchSizeConfig = "not-a-number"))

        coVerify(exactly = 1) { metadataService.getAll(0L, 100) }
    }

    @OptIn(Internal::class)
    @Test
    fun `zero config batchSize falls back to hardcoded default`() = runTest {
        stubWithLock()
        coEvery { metadataService.getAll(0L, 100) } returns emptyList()

        // Config 0 fails takeIf { it > 0 } -> default 100.
        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor(batchSizeConfig = "0"))

        coVerify(exactly = 1) { metadataService.getAll(0L, 100) }
    }

    @OptIn(Internal::class)
    @Test
    fun `null config batchSize falls back to hardcoded default`() = runTest {
        stubWithLock()
        coEvery { metadataService.getAll(0L, 100) } returns emptyList()

        // Absent config -> propertyOrNull null -> default 100.
        val job = MetadataIndexJob(id = null, storage = IndexStorageSystem(id = UUID.random(), name = "Named Index"))
        run(job, executor(batchSizeConfig = null))

        coVerify(exactly = 1) { metadataService.getAll(0L, 100) }
    }
}
