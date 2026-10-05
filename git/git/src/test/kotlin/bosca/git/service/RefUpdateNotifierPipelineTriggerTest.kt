@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.model.PipelineTriggerJob
import bosca.git.repository.DfsPackRepository
import bosca.git.repository.GitRepositoryRepository
import bosca.git.repository.TaskCommitReferenceRepository
import bosca.sharedqueue.jobs.JobConfigurationEnqueuer
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.lib.TreeFormatter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException

/**
 * [RefUpdateNotifierImpl] → CI trigger: an ANNOTATED tag's ref points at the tag OBJECT, but the
 * enqueued [PipelineTriggerJob.afterSha] must be the peeled COMMIT — the run's commitSha, commit
 * statuses, and the relay's Get Build Run correlation all mean the commit. (This was the first real
 * release run's failure: the run recorded the tag object sha, so nothing could find it.)
 */
class RefUpdateNotifierPipelineTriggerTest {

    private val enqueuer = mockk<JobConfigurationEnqueuer>()
    private val repositoryRepository = mockk<GitRepositoryRepository>(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = kotlinx.serialization.modules.SerializersModule {
            contextual(UUID::class, bosca.serialization.UUIDSerializer())
        }
    }
    private val notifier = RefUpdateNotifierImpl(
        repositoryRepository = repositoryRepository,
        packRepository = mockk<DfsPackRepository>(relaxed = true),
        webhookService = mockk(relaxed = true),
        taskCommitRefRepository = mockk<TaskCommitReferenceRepository>(relaxed = true),
    )

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<Json> { json }
        provides<JobConfigurationEnqueuer>(name = "pipeline-trigger") { enqueuer }
    }

    @AfterTest
    fun teardown() = ProviderRegistry.clear()

    @Test
    fun `an annotated tag ref update enqueues the PEELED commit sha, not the tag object sha`() = runTest {
        val repo = InMemoryRepository(DfsRepositoryDescription("test"))
        val (commitId, tagId) = repo.newObjectInserter().use { inserter ->
            val tree = inserter.insert(TreeFormatter())
            val commit = CommitBuilder()
            commit.setTreeId(tree)
            commit.author = PersonIdent("t", "t@t")
            commit.committer = PersonIdent("t", "t@t")
            commit.message = "c1"
            val commitId = inserter.insert(commit)
            val tagBuilder = TagBuilder()
            tagBuilder.tag = "v1.0.0"
            tagBuilder.setObjectId(commitId, Constants.OBJ_COMMIT)
            tagBuilder.tagger = PersonIdent("t", "t@t")
            tagBuilder.message = "release"
            val tagId = inserter.insert(tagBuilder)
            inserter.flush()
            commitId to tagId
        }

        val enqueued = mutableListOf<JsonElement>()
        coEvery { enqueuer.enqueue(capture(enqueued), any()) } returns mockk(relaxed = true)

        notifier.notifyRefsUpdated(
            repository = repo,
            repositoryId = UUID.random(),
            updates = listOf(RefChange("refs/tags/v1.0.0", ObjectId.zeroId(), tagId)),
        )

        val job = enqueued.map { json.decodeFromJsonElement(PipelineTriggerJob.serializer(), it) }
            .single { it.ref == "refs/tags/v1.0.0" }
        assertEquals(commitId.name(), job.afterSha, "the trigger must carry the commit, not the tag object")
    }

    @Test
    fun `a branch push whose commit message says skip ci enqueues nothing`() = runTest {
        val repo = InMemoryRepository(DfsRepositoryDescription("test"))
        val commitId = repo.newObjectInserter().use { inserter ->
            val tree = inserter.insert(TreeFormatter())
            val commit = CommitBuilder()
            commit.setTreeId(tree)
            commit.author = PersonIdent("t", "t@t")
            commit.committer = PersonIdent("t", "t@t")
            commit.message = "Pin release versions [skip ci]"
            val id = inserter.insert(commit)
            inserter.flush()
            id
        }
        val enqueued = mutableListOf<JsonElement>()
        coEvery { enqueuer.enqueue(capture(enqueued), any()) } returns mockk(relaxed = true)

        notifier.notifyRefsUpdated(repo, UUID.random(), listOf(RefChange("refs/heads/main", ObjectId.zeroId(), commitId)))

        kotlin.test.assertTrue(enqueued.isEmpty(), "a [skip ci] commit must not trigger a pipeline")
    }

    @Test
    fun `a release tag pointing at a skip-ci pin commit still builds — only the tag's own message suppresses`() = runTest {
        val repo = InMemoryRepository(DfsRepositoryDescription("test"))
        val (commitId, tagId, skipTagId) = repo.newObjectInserter().use { inserter ->
            val tree = inserter.insert(TreeFormatter())
            val commit = CommitBuilder()
            commit.setTreeId(tree)
            commit.author = PersonIdent("t", "t@t")
            commit.committer = PersonIdent("t", "t@t")
            commit.message = "Pin release versions [skip ci]"
            val commitId = inserter.insert(commit)
            val tag = TagBuilder()
            tag.tag = "v2.0.0"
            tag.setObjectId(commitId, Constants.OBJ_COMMIT)
            tag.tagger = PersonIdent("t", "t@t")
            tag.message = "Release v2.0.0"
            val tagId = inserter.insert(tag)
            val skipTag = TagBuilder()
            skipTag.tag = "v2.0.1"
            skipTag.setObjectId(commitId, Constants.OBJ_COMMIT)
            skipTag.tagger = PersonIdent("t", "t@t")
            skipTag.message = "internal re-point [skip ci]"
            val skipTagId = inserter.insert(skipTag)
            inserter.flush()
            Triple(commitId, tagId, skipTagId)
        }
        val enqueued = mutableListOf<JsonElement>()
        coEvery { enqueuer.enqueue(capture(enqueued), any()) } returns mockk(relaxed = true)

        notifier.notifyRefsUpdated(
            repo, UUID.random(),
            listOf(
                RefChange("refs/tags/v2.0.0", ObjectId.zeroId(), tagId),
                RefChange("refs/tags/v2.0.1", ObjectId.zeroId(), skipTagId),
            ),
        )

        val jobs = enqueued.map { json.decodeFromJsonElement(PipelineTriggerJob.serializer(), it) }
        // The release tag builds (peeled to the pin commit), the [skip ci]-messaged tag doesn't.
        assertEquals(listOf("refs/tags/v2.0.0"), jobs.map { it.ref })
        assertEquals(commitId.name(), jobs.single().afterSha)
    }

    @Test
    fun `a branch ref update retains its initiating principal and commit sha`() = runTest {
        val repo = InMemoryRepository(DfsRepositoryDescription("test"))
        val initiatingPrincipalId = UUID.random()
        val commitId = repo.newObjectInserter().use { inserter ->
            val tree = inserter.insert(TreeFormatter())
            val commit = CommitBuilder()
            commit.setTreeId(tree)
            commit.author = PersonIdent("t", "t@t")
            commit.committer = PersonIdent("t", "t@t")
            commit.message = "c1"
            val id = inserter.insert(commit)
            inserter.flush()
            id
        }

        val enqueued = mutableListOf<JsonElement>()
        coEvery { enqueuer.enqueue(capture(enqueued), any()) } returns mockk(relaxed = true)

        notifier.notifyRefsUpdated(
            repository = repo,
            repositoryId = UUID.random(),
            updates = listOf(RefChange("refs/heads/main", ObjectId.zeroId(), commitId)),
            pusherPrincipalId = initiatingPrincipalId,
        )

        val job = enqueued.map { json.decodeFromJsonElement(PipelineTriggerJob.serializer(), it) }
            .single { it.ref == "refs/heads/main" }
        assertEquals(commitId.name(), job.afterSha)
        assertEquals(initiatingPrincipalId, job.pusherPrincipalId)
    }

    @Test
    fun `attributing an imported ref uses ordinary CI rules without repeating ref side effects`() = runTest {
        InMemoryRepository(DfsRepositoryDescription("attribution")).use { repo ->
            val (commitId, tagId) = repo.newObjectInserter().use { inserter ->
                val commit = CommitBuilder().apply {
                    setTreeId(inserter.insert(TreeFormatter()))
                    author = PersonIdent("t", "t@t"); committer = author
                    message = "Pin versions [skip ci]"
                }
                val commitId = inserter.insert(commit)
                val tagId = inserter.insert(TagBuilder().apply {
                    tag = "v3"; setObjectId(commitId, Constants.OBJ_COMMIT)
                    tagger = PersonIdent("t", "t@t"); message = "Release v3"
                })
                inserter.flush()
                commitId to tagId
            }
            val enqueued = mutableListOf<JsonElement>()
            coEvery { enqueuer.enqueue(capture(enqueued), any()) } returns mockk(relaxed = true)
            val principalId = UUID.random()
            notifier.enqueuePipelineTriggers(repo, UUID.random(), listOf(
                RefChange("refs/tags/v3", ObjectId.zeroId(), tagId),
                RefChange("refs/heads/main", ObjectId.zeroId(), commitId),
                RefChange("refs/heads/deleted", commitId, ObjectId.zeroId()),
                RefChange("refs/other/internal", ObjectId.zeroId(), commitId),
            ), principalId)
            val job = enqueued.map { json.decodeFromJsonElement(PipelineTriggerJob.serializer(), it) }.single()
            assertEquals("refs/tags/v3", job.ref)
            assertEquals(commitId.name(), job.afterSha)
            assertEquals(ObjectId.zeroId().name(), job.beforeSha)
            assertEquals(principalId, job.pusherPrincipalId)
            coVerify(exactly = 0) { repositoryRepository.updateDiskSize(any(), any()) }
            coVerify(exactly = 0) { repositoryRepository.findById(any()) }
        }
    }

    @Test
    fun `attributed CI queue failures and cancellation propagate for transactional retry`() = runTest {
        InMemoryRepository(DfsRepositoryDescription("attribution-failure")).use { repo ->
            val commitId = repo.newObjectInserter().use { inserter ->
                val commit = CommitBuilder().apply {
                    setTreeId(inserter.insert(TreeFormatter()))
                    author = PersonIdent("t", "t@t"); committer = author; message = "Build"
                }
                inserter.insert(commit).also { inserter.flush() }
            }
            val updates = listOf(RefChange("refs/heads/main", ObjectId.zeroId(), commitId))
            coEvery { enqueuer.enqueue(any(), any()) } throws IllegalStateException("queue unavailable")
            assertEquals("queue unavailable", assertFailsWith<IllegalStateException> {
                notifier.enqueuePipelineTriggers(repo, UUID.random(), updates, UUID.random())
            }.message)
            coEvery { enqueuer.enqueue(any(), any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                notifier.enqueuePipelineTriggers(repo, UUID.random(), updates, UUID.random())
            }
        }
    }
}
