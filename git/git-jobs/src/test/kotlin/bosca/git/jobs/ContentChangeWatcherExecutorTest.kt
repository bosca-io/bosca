@file:OptIn(bosca.di.annotation.InternalDI::class, bosca.core.annotations.Internal::class)

package bosca.git.jobs

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.events.EventManager
import bosca.events.asCoroutineContext
import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.Repository
import bosca.git.model.RepositoryContentType
import bosca.git.model.Visibility
import bosca.git.repository.GitRepositoryRepository
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.sharedqueue.jobs.InternalJobConstructor
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Branch coverage for [ContentChangeWatcherExecutor]: content-type gating, the
 * zero-parent (no-diff) short circuit, and script-path event emission. Real commits
 * in an in-memory DFS repo drive the diff.
 */
class ContentChangeWatcherExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val repoRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val dfsManager = mockk<BoscaDfsRepositoryManager>()
    private val pubSub = mockk<PubSubService>(relaxed = true)
    private val jobQueue = mockk<JobQueue>(relaxed = true)

    private val repositoryId = UUID.random()
    private lateinit var gitRepo: InMemoryRepository
    private val author = PersonIdent("Test", "test@bosca.io")

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json>(singleton = true) { json }
        provides<PubSubService>(singleton = true) { pubSub }
        provides<GitRepositoryRepository>(singleton = true) { repoRepository }
        provides<BoscaDfsRepositoryManager>(singleton = true) { dfsManager }
        gitRepo = InMemoryRepository(DfsRepositoryDescription("ccw-$repositoryId"))
        coEvery { dfsManager.open(repositoryId) } returns gitRepo
    }

    @AfterTest
    fun teardown() {
        gitRepo.close()
        unmockkAll()
        ProviderRegistry.clear()
    }

    // Builds a (possibly nested) tree from path->content, returns the commit id.
    private fun commit(files: Map<String, String>, parent: ObjectId? = null): ObjectId {
        val ins = gitRepo.objectDatabase.newInserter()
        try {
            val blobs = files.mapValues { ins.insert(Constants.OBJ_BLOB, it.value.toByteArray()) }
            val rootTree = buildTree(ins, blobs)
            val person = author
            val commit = CommitBuilder().apply {
                setTreeId(rootTree)
                setAuthor(person); setCommitter(person); setMessage("c")
                if (parent != null) setParentId(parent)
            }
            val id = ins.insert(commit)
            ins.flush()
            return id
        } finally {
            ins.close()
        }
    }

    private fun buildTree(ins: ObjectInserter, entries: Map<String, ObjectId>): ObjectId {
        val blobs = mutableMapOf<String, ObjectId>()
        val subtrees = mutableMapOf<String, MutableMap<String, ObjectId>>()
        for ((path, id) in entries) {
            val slash = path.indexOf('/')
            if (slash < 0) blobs[path] = id
            else subtrees.getOrPut(path.substring(0, slash)) { mutableMapOf() }[path.substring(slash + 1)] = id
        }
        val names = (blobs.keys.map { it to false } + subtrees.keys.map { it to true })
            // git tree order: directories compare as if they had a trailing '/'
            .sortedBy { (n, isTree) -> if (isTree) "$n/" else n }
        val tf = TreeFormatter()
        for ((name, isTree) in names) {
            if (isTree) tf.append(name, FileMode.TREE, buildTree(ins, subtrees.getValue(name)))
            else tf.append(name, FileMode.REGULAR_FILE, blobs.getValue(name))
        }
        return ins.insert(tf)
    }

    private fun repoOf(contentType: RepositoryContentType?) = Repository(
        id = repositoryId, slug = "r", name = "R", ownerId = UUID.random(),
        visibility = Visibility.PRIVATE, contentType = contentType,
    )

    private suspend fun watch(before: ObjectId, after: ObjectId, ref: String = "refs/heads/main") =
        withContext(EventManager().asCoroutineContext()) {
            ContentChangeWatcherExecutor.watchChanges(
                ContentChangeWatcherJob(repositoryId, ref, before.name(), after.name()),
                repoRepository, dfsManager,
            )
        }

    @Test
    fun `does nothing when the repository is missing`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns null
        val c1 = commit(mapOf("a.txt" to "1"))
        watch(c1, commit(mapOf("a.txt" to "2"), c1))
        coVerify(exactly = 0) { pubSub.publish(any(), any<kotlinx.serialization.KSerializer<Any>>(), any()) }
    }

    @Test
    fun `does nothing when the repository has no content type`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(null)
        val c1 = commit(mapOf("a.txt" to "1"))
        watch(c1, commit(mapOf("a.txt" to "2"), c1))
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `ignores repositories that are not script projects`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(RepositoryContentType.GENERAL)
        val c1 = commit(mapOf("a.txt" to "1"))
        watch(c1, commit(mapOf("a.txt" to "2"), c1))
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `returns early when there is no previous commit`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        val c1 = commit(mapOf("scripts/foo/source.kts" to "1"))
        // beforeSha == zero id => no diff computed
        watch(ObjectId.zeroId(), c1)
        coVerify(exactly = 0) { dfsManager.open(any()) }
    }

    @Test
    fun `emits a script event for changed script sources`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        val c1 = commit(mapOf("scripts/foo/source.kts" to "1", "readme.md" to "x"))
        val c2 = commit(mapOf("scripts/foo/source.kts" to "2", "readme.md" to "x"), c1)

        watch(c1, c2)

        coVerify { pubSub.publish(eq("bosca.git.script_source_updated"), any<kotlinx.serialization.KSerializer<Any>>(), any()) }
    }

    @Test
    fun `does not emit a script event when no script path matches`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        val c1 = commit(mapOf("docs/readme.md" to "1"))
        val c2 = commit(mapOf("docs/readme.md" to "2"), c1)

        watch(c1, c2)

        coVerify(exactly = 0) { pubSub.publish(eq("bosca.git.script_source_updated"), any<kotlinx.serialization.KSerializer<Any>>(), any()) }
    }

    @Test
    fun `execute drives the watcher from the job definition`() = runTest {
        coEvery { repoRepository.findById(repositoryId) } returns repoOf(RepositoryContentType.SCRIPT_PROJECT)
        val c1 = commit(mapOf("scripts/foo/source.kts" to "1"))
        val c2 = commit(mapOf("scripts/foo/source.kts" to "2"), c1)
        val jobObj: Job = InternalJobConstructor(
            definition = json.encodeToJsonElement(
                ContentChangeWatcherJob.serializer(),
                ContentChangeWatcherJob(repositoryId, "refs/heads/main", c1.name(), c2.name()),
            ),
            executor = ContentChangeWatcherExecutor::class,
        )
        withContext(jobQueue.asCoroutineContext(jobObj) + EventManager().asCoroutineContext()) {
            ContentChangeWatcherExecutor().execute()
        }
        coVerify { pubSub.publish(eq("bosca.git.script_source_updated"), any<kotlinx.serialization.KSerializer<Any>>(), any()) }
    }
}
