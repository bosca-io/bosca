@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.cache.CacheManager
import bosca.cache.RequestCache
import bosca.cache.RequestCacheSerializer
import bosca.cache.asCoroutineContext as requestCacheContext
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.DfsRef
import bosca.git.model.Repository
import bosca.git.model.Visibility
import bosca.git.repository.DfsRefRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.search.IndexStorageSystem
import bosca.search.service.SearchService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Branch coverage for [ReindexAllExecutor]: the storage-system resolution path,
 * clearing existing file docs, enqueuing per-branch file-index jobs, and skipping
 * missing/soft-deleted repositories.
 */
class ReindexAllExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val jobQueue = mockk<JobQueue>(relaxed = true)
    private val searchService = mockk<SearchService>(relaxed = true)
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val refRepository = mockk<DfsRefRepository>(relaxed = true)
    private val storageService = mockk<StorageSystemService>(relaxed = true)
    private val cacheManager = mockk<CacheManager>(relaxed = true)
    private val repoIndexEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)
    private val fileIndexEnqueuer = mockk<JobConfigurationEnqueuer>(relaxed = true)

    private val storage = IndexStorageSystem(UUID.random(), "git-code")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<SearchService>(singleton = true) { searchService }
        provides<GitRepositoryRepository>(singleton = true) { repoRepository }
        provides<DfsRefRepository>(singleton = true) { refRepository }
        provides<StorageSystemService>(singleton = true) { storageService }
        provides<CacheManager>(singleton = true) { cacheManager }
        provides<JobConfigurationEnqueuer>(name = "repository-index", singleton = true) { repoIndexEnqueuer }
        provides<JobConfigurationEnqueuer>(name = "file-content-index", singleton = true) { fileIndexEnqueuer }
    }

    @AfterTest
    fun teardown() {
        unmockkAll()
        ProviderRegistry.clear()
    }

    private suspend fun run(job: ReindexAllJob) {
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement(ReindexAllJob.serializer(), job),
            executor = ReindexAllExecutor::class,
        )
        val cache = RequestCache(cacheManager, mockk<RequestCacheSerializer>(relaxed = true))
        withContext(jobQueue.asCoroutineContext(jobObj) + cache.requestCacheContext()) {
            ReindexAllExecutor().execute()
        }
    }

    private fun repo(id: UUID, deleted: Boolean = false) = Repository(
        id = id, slug = "r-$id", name = "R", ownerId = UUID.random(),
        visibility = Visibility.PRIVATE, deleted = deleted,
    )

    private fun branchRef(repoId: UUID, name: String) = DfsRef(repositoryId = repoId, name = name, objectId = "a".repeat(40))

    @Test
    fun `enqueues a repository reindex, clears file docs, and enqueues per-branch file jobs`() = runTest {
        val id = UUID.random()
        coEvery { repoRepository.findActiveIds() } returns listOf(id)
        coEvery { repoRepository.findById(id) } returns repo(id)
        coEvery { refRepository.findAll(id) } returns listOf(
            branchRef(id, "refs/heads/main"),
            branchRef(id, "refs/heads/dev"),
            branchRef(id, "refs/tags/v1"), // not a branch -> filtered out
        )

        run(ReindexAllJob(storage = storage))

        coVerify(exactly = 1) { repoIndexEnqueuer.enqueue(any(), any()) }
        coVerify { searchService.deleteByFilter(eq(storage), any()) }
        coVerify(exactly = 2) { fileIndexEnqueuer.enqueue(any(), any()) } // two branch refs only
    }

    @Test
    fun `skips missing and soft-deleted repositories`() = runTest {
        val present = UUID.random()
        val missing = UUID.random()
        val deleted = UUID.random()
        coEvery { repoRepository.findActiveIds() } returns listOf(present, missing, deleted)
        coEvery { repoRepository.findById(present) } returns repo(present)
        coEvery { repoRepository.findById(missing) } returns null
        coEvery { repoRepository.findById(deleted) } returns repo(deleted, deleted = true)
        coEvery { refRepository.findAll(present) } returns listOf(branchRef(present, "refs/heads/main"))

        run(ReindexAllJob(storage = storage))

        // Only the present repository's branch is scanned for file jobs.
        coVerify(exactly = 1) { fileIndexEnqueuer.enqueue(any(), any()) }
        coVerify(exactly = 0) { refRepository.findAll(missing) }
        coVerify(exactly = 0) { refRepository.findAll(deleted) }
    }

    @Test
    fun `resolves search storage systems when the job carries none`() = runTest {
        coEvery { repoRepository.findActiveIds() } returns emptyList()
        coEvery { storageService.getAll() } returns listOf(
            StorageSystem(id = UUID.random(), name = "git-code", description = "", type = StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")),
            StorageSystem(id = UUID.random(), name = "other", description = "", type = StorageSystemType.SEARCH, configuration = json.parseToJsonElement("{}")), // wrong name
            StorageSystem(id = UUID.random(), name = "git-code", description = "", type = StorageSystemType.SUPPLEMENTARY, configuration = json.parseToJsonElement("{}")), // wrong type
        )

        run(ReindexAllJob(storage = null))

        // Only the git-code SEARCH system runs its reindex (one repository-index enqueue).
        coVerify(exactly = 1) { repoIndexEnqueuer.enqueue(any(), any()) }
    }
}
