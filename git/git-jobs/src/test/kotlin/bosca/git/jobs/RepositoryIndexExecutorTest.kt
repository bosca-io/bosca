@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext as requestCacheContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.GitRepositoryRepository
import bosca.search.IndexStorageSystem
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import bosca.storage.model.StorageSystem
import bosca.storage.model.StorageSystemType
import bosca.storage.service.StorageSystemService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Branch coverage for [RepositoryIndexExecutor]: single-repository index/delete,
 * missing/soft-deleted handling, storage-system resolution when the job carries
 * no storage, and the bulk re-index batch path.
 */
class RepositoryIndexExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val searchService = mockk<SearchService>(relaxed = true)
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)

    private val storage = IndexStorageSystem(UUID.random(), "git-code")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SearchService>(singleton = true) { searchService }
        provides<GitRepositoryRepository>(singleton = true) { repoRepository }
        provides<StorageSystemService>(singleton = true) { storageService }
        provides<CacheManager>(singleton = true) { cacheManager }
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private suspend fun run(job: RepositoryIndexJob) {
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement(RepositoryIndexJob.serializer(), job),
            executor = RepositoryIndexExecutor::class,
        )
        val cache = RequestCache(cacheManager, mockk<RequestCacheSerializer>(relaxed = true))
        withContext(jobQueue.asCoroutineContext(jobObj) + cache.requestCacheContext()) {
            RepositoryIndexExecutor().execute()
        }
    }

    private fun repo(id: UUID, deleted: Boolean = false) = Repository(
        id = id, slug = "r-$id", name = "R", ownerId = UUID.random(),
        visibility = Visibility.PRIVATE, deleted = deleted,
    )

    @Test
    fun `indexes a single existing repository`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findById(id) } returns repo(id)

        run(RepositoryIndexJob(storage = storage, repositoryId = id))

        coVerify { searchService.index(storage, any<JsonElement>()) }
    }

    @Test
    fun `deletes the document when deleteOnly is set`() = runTest {
        val id = UUID.random()

        run(RepositoryIndexJob(storage = storage, repositoryId = id, deleteOnly = true))

        coVerify { searchService.delete(storage, id.toString()) }
        coVerify(exactly = 0) { searchService.index(storage, any<JsonElement>()) }
    }

    @Test
    fun `deletes the document when the repository is missing`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findById(id) } returns null

        run(RepositoryIndexJob(storage = storage, repositoryId = id))

        coVerify { searchService.delete(storage, id.toString()) }
    }

    @Test
    fun `deletes the document when the repository is soft-deleted`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findById(id) } returns repo(id, deleted = true)

        run(RepositoryIndexJob(storage = storage, repositoryId = id))

        coVerify { searchService.delete(storage, id.toString()) }
    }

    @Test
    fun `resolves matching search storage systems when the job carries none`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findById(id) } returns repo(id)
        coEvery { storageService.getAll() } returns listOf(
            StorageSystem(id = UUID.random(), name = "git-code", description = "", type = StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")),
            StorageSystem(id = UUID.random(), name = "other", description = "", type = StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")),
            StorageSystem(id = UUID.random(), name = "git-code", description = "", type = StorageSystemType.SUPPLEMENTARY, configuration = json.parseToJsonElement("{}")),
        )

        run(RepositoryIndexJob(storage = null, repositoryId = id))

        // Only the SEARCH system named "git-code" is used.
        coVerify(exactly = 1) { searchService.index(any(), any<JsonElement>()) }
    }

    @Test
    fun `bulk index skips the search call when every repository is filtered out`() = runTest {
        coEvery { repoRepository.findActiveIds() } returns listOf(UUID.random(), UUID.random())
        coEvery { repoRepository.findById(any()) } returns null // all missing -> empty batch

        run(RepositoryIndexJob(storage = storage, repositoryId = null))

        coVerify(exactly = 0) { searchService.index(storage, any<List<JsonElement>>()) }
    }

    @Test
    fun `bulk delete-only is a no-op without a repository id`() = runTest {
        // repositoryId == null && deleteOnly == true -> neither the single-doc nor bulk path runs.
        run(RepositoryIndexJob(storage = storage, repositoryId = null, deleteOnly = true))

        coVerify(exactly = 0) { searchService.index(any(), any<List<JsonElement>>()) }
        coVerify(exactly = 0) { repoRepository.findActiveIds() }
    }

    @Test
    fun `indexes a repository that has a description and content type`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findById(id) } returns Repository(
            id = id, slug = "r", name = "R", ownerId = UUID.random(), visibility = Visibility.PRIVATE,
            description = "a repo", contentType = bosca.git.model.RepositoryContentType.SCRIPT_PROJECT,
        )

        run(RepositoryIndexJob(storage = storage, repositoryId = id))

        coVerify { searchService.index(storage, any<JsonElement>()) }
    }

    @Test
    fun `bulk indexes all active repositories in batches`() = runTest {
        val a = UUID.random()
        val b = UUID.random()
        coEvery { repoRepository.findActiveIds() } returns listOf(a, b)
        coEvery { repoRepository.findById(a) } returns repo(a)
        coEvery { repoRepository.findById(b) } returns repo(b, deleted = true) // filtered out

        run(RepositoryIndexJob(storage = storage, repositoryId = null))

        coVerify { searchService.index(storage, any<List<JsonElement>>()) }
    }
}
