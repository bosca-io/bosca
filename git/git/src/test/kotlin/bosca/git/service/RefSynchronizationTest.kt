package bosca.git.service

import bosca.git.dfs.BoscaDfsRepositoryManager
import bosca.git.model.GitHubSyncResult
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.serialization.UUID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TagBuilder
import org.eclipse.jgit.lib.TreeFormatter
import java.nio.file.Files
import kotlin.test.*

/** Real fetch/push transports transfer original objects between DFS and a local bare repository. */
class RefSynchronizationTest {
    private val repositoryId = UUID.random()
    private val principalId = UUID.random()
    private val local = InMemoryRepository.Builder().setRepositoryDescription(DfsRepositoryDescription("local"))
        .setFS(org.eclipse.jgit.util.FS.DETECTED).build()
    private val directory = Files.createTempDirectory("bosca-github-refs-").toFile()
    private val remote = Git.init().setBare(true).setDirectory(directory).call().repository
    private val manager = mockk<BoscaDfsRepositoryManager>()
    private val notifier = mockk<RefUpdateNotifier>(relaxed = true)
    private val locks = mockk<DistributedLockFactory>()
    private val lock = mockk<DistributedLock>()
    private val service = RepositoryWriteServiceImpl(manager, notifier, locks)
    private val ref = "refs/heads/main"

    @BeforeTest fun setup() {
        every { manager.open(repositoryId) } answers { local.incrementOpen(); local }
        coEvery { locks.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
    }

    @AfterTest fun cleanup() {
        local.close(); remote.close(); directory.deleteRecursively()
    }

    private fun commit(repo: Repository, parent: ObjectId? = null): ObjectId = repo.newObjectInserter().use { inserter ->
        val builder = CommitBuilder()
        builder.setTreeId(inserter.insert(TreeFormatter()))
        parent?.let(builder::setParentId)
        builder.author = PersonIdent("Original Author", "author@example.com")
        builder.committer = builder.author
        builder.message = UUID.random().toString()
        inserter.insert(builder).also { inserter.flush() }
    }

    private fun set(repo: Repository, sha: ObjectId?, name: String = ref) {
        val update = repo.updateRef(name)
        update.isForceUpdate = true
        if (sha == null) update.delete() else { update.setNewObjectId(sha); update.update() }
    }

    private suspend fun sync(
        after: ObjectId?, before: ObjectId? = null, baseline: ObjectId? = before,
        known: Boolean = baseline != null, direction: RefSynchronizationDirection = RefSynchronizationDirection.INBOUND,
        name: String = ref, principal: UUID? = principalId, conflict: Boolean = false,
        protection: bosca.git.model.BranchProtectionRule? = null, pullRequestMerge: Boolean = false, triggerBuild: Boolean = true,
    ) = service.synchronizeRef(RefSynchronizationInput(
        repositoryId, directory.toURI().toString(), "test-token", name,
        before?.name(), after?.name(), baseline?.name(), known, direction, principal, conflict,
        protection = protection, pullRequestMerge = pullRequestMerge, triggerBuild = triggerBuild,
    ))

    private suspend fun common(): ObjectId {
        val base = commit(remote); set(remote, base)
        assertEquals(GitHubSyncResult.APPLIED, sync(base).result)
        return base
    }

    @Test fun `protection permits authorized force deletion and paired merges but never direct required PR pushes`() = runBlocking {
        val base = common()
        val rule = bosca.git.model.BranchProtectionRule(repositoryId = repositoryId, pattern = "main", allowForcePush = true,
            allowDeletion = true, restrictPushAccess = listOf(principalId))
        val forced = commit(remote); set(remote, forced)
        assertEquals(GitHubSyncResult.APPLIED, sync(forced, base, protection = rule).result)
        set(remote, null)
        assertEquals(GitHubSyncResult.APPLIED, sync(null, forced, protection = rule).result)
        val required = rule.copy(requirePullRequest = true)
        set(remote, base)
        assertFailsWith<bosca.security.service.SecurityException> { sync(base, protection = required) }
        assertEquals(GitHubSyncResult.APPLIED, sync(base, protection = required, pullRequestMerge = true, triggerBuild = false).result)
        coVerify { notifier.notifyRefsUpdated(any(), repositoryId, any(), null) }
    }

    @Test fun `linear history rejects every newly introduced merge commit but permits a linear update`() = runBlocking {
        val base = common()
        val left = commit(remote, base)
        val right = commit(remote, base)
        val merge = remote.newObjectInserter().use { inserter ->
            val builder = CommitBuilder().apply {
                setTreeId(inserter.insert(TreeFormatter())); setParentIds(left, right)
                author = PersonIdent("Author", "author@example.com"); committer = author; message = "Merge"
            }
            inserter.insert(builder).also { inserter.flush() }
        }
        val tip = commit(remote, merge); set(remote, tip)
        val rule = bosca.git.model.BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requireLinearHistory = true)
        assertFailsWith<bosca.security.service.SecurityException> { sync(tip, base, protection = rule, pullRequestMerge = true) }
        assertEquals(base, local.resolve(ref))
        set(remote, left)
        assertEquals(GitHubSyncResult.APPLIED, sync(left, base, protection = rule).result)
    }

    @Test fun `inbound create and fast forward retain objects and principal and echoes do not notify`() = runBlocking {
        val base = common()
        val next = commit(remote, base); set(remote, next)
        assertEquals(GitHubSyncResult.APPLIED, sync(next, base).result)
        assertEquals(next, local.resolve(ref))
        assertEquals("Original Author", org.eclipse.jgit.revwalk.RevWalk(local).use { it.parseCommit(next).authorIdent.name })
        assertEquals(GitHubSyncResult.UNCHANGED, sync(next, base).result)
        coVerify(exactly = 2) { notifier.notifyRefsUpdated(any(), repositoryId, any(), principalId) }
    }

    @Test fun `outbound create update and delete use real push and do not notify Bosca`() = runBlocking {
        val base = commit(local); set(local, base)
        assertEquals(GitHubSyncResult.APPLIED, sync(base, direction = RefSynchronizationDirection.OUTBOUND).result)
        remote.refDatabase.refresh()
        assertEquals(base, remote.resolve(ref))
        val next = commit(local, base); set(local, next)
        assertEquals(GitHubSyncResult.APPLIED, sync(next, base, direction = RefSynchronizationDirection.OUTBOUND).result)
        set(local, null)
        assertEquals(GitHubSyncResult.APPLIED, sync(null, next, direction = RefSynchronizationDirection.OUTBOUND).result)
        remote.refDatabase.refresh()
        assertNull(remote.resolve(ref))
        coVerify(exactly = 0) { notifier.notifyRefsUpdated(any(), any(), any(), any()) }
    }

    @Test fun `independent branch edits conflict and leave both histories intact`() = runBlocking {
        val base = common()
        val bosca = commit(local, base); set(local, bosca)
        val github = commit(remote, base); set(remote, github)
        val result = sync(github, base)
        assertEquals(GitHubSyncResult.CONFLICT, result.result)
        assertEquals(bosca.name(), result.boscaSha)
        assertEquals(github.name(), result.remoteSha)
        assertEquals(bosca, local.resolve(ref))
        assertEquals(github, remote.resolve(ref))
    }

    @Test fun `fast forward converges even without a recorded common baseline`() = runBlocking {
        val base = common()
        val next = commit(remote, base); set(remote, next)
        assertEquals(GitHubSyncResult.APPLIED, sync(next, known = false, baseline = null).result)
    }

    @Test fun `an initial conflict still permits safe fast forwards and agreed ref values`() = runBlocking {
        val base = common()
        val next = commit(remote, base); set(remote, next)
        assertEquals(GitHubSyncResult.APPLIED, sync(next, known = false, baseline = null, conflict = true).result)
        assertEquals(GitHubSyncResult.UNCHANGED, sync(next, known = false, baseline = null, conflict = true).result)
        assertEquals(next, local.resolve(ref))
    }

    @Test fun `force update requires an unchanged baseline and deletion cannot erase an independent edit`() = runBlocking {
        val base = common()
        val replacement = commit(remote); set(remote, replacement)
        assertEquals(GitHubSyncResult.APPLIED, sync(replacement, base).result)
        val localEdit = commit(local, replacement); set(local, localEdit)
        set(remote, null)
        assertEquals(GitHubSyncResult.CONFLICT, sync(null, replacement).result)
        assertEquals(localEdit, local.resolve(ref))
        set(local, replacement)
        assertEquals(GitHubSyncResult.APPLIED, sync(null, replacement).result)
        assertNull(local.resolve(ref))
    }

    @Test fun `inbound deletion removes the branch HEAD points to because DFS repositories are bare`() = runBlocking {
        val base = common()
        local.updateRef("HEAD").link(ref)
        assertTrue(local.isBare)
        set(remote, null)
        assertEquals(GitHubSyncResult.APPLIED, sync(null, base).result)
        assertNull(local.exactRef(ref))
        coVerify { notifier.notifyRefsUpdated(any(), repositoryId, match { it.single().newId == ObjectId.zeroId() }, principalId) }
    }

    @Test fun `an independent target deletion conflicts with a source update`() = runBlocking {
        val base = common()
        val next = commit(remote, base); set(remote, next); set(local, null)
        assertEquals(GitHubSyncResult.CONFLICT, sync(next, base).result)
        assertNull(local.resolve(ref))
    }

    @Test fun `a synchronized deletion permits later recreation and stale deliveries never rewind refs`() = runBlocking {
        val base = common()
        val next = commit(remote, base); set(remote, next)
        assertEquals(GitHubSyncResult.STALE, sync(base).result)
        assertEquals(base, local.resolve(ref))
        set(local, null)
        assertEquals(GitHubSyncResult.APPLIED, sync(next, baseline = null, known = true).result)
    }

    @Test fun `annotated tag object and metadata transfer unchanged without auto-following other tags`() = runBlocking {
        val head = commit(remote)
        val tag = remote.newObjectInserter().use { inserter ->
            val builder = TagBuilder(); builder.setObjectId(head, org.eclipse.jgit.lib.Constants.OBJ_COMMIT)
            builder.tag = "v1"; builder.tagger = PersonIdent("Original Tagger", "tagger@example.com"); builder.message = "Original tag"
            inserter.insert(builder).also { inserter.flush() }
        }
        val name = "refs/tags/v1"; set(remote, tag, name); set(remote, head, "refs/tags/unrelated")
        assertEquals(GitHubSyncResult.APPLIED, sync(tag, name = name, principal = null).result)
        assertEquals(tag, local.resolve(name))
        assertNull(local.resolve("refs/tags/unrelated"))
        val other = commit(local); set(local, other, name)
        val replacement = commit(remote); set(remote, replacement, name)
        assertEquals(GitHubSyncResult.CONFLICT, sync(replacement, tag, name = name).result)
        coVerify { notifier.notifyRefsUpdated(any(), repositoryId, any(), null) }
    }

    @Test fun `unknown initial deletion cannot remove a target and identical absent refs are unchanged`() = runBlocking {
        val localHead = commit(local); set(local, localHead)
        assertEquals(GitHubSyncResult.CONFLICT, sync(null, known = false).result)
        set(local, null)
        assertEquals(GitHubSyncResult.UNCHANGED, sync(null, known = false).result)
    }

    @Test fun `remote lease refuses a concurrent update between fetch and push`() = runBlocking {
        val base = common()
        val localHead = commit(local, base); set(local, localHead)
        val concurrent = commit(remote, base)
        coEvery { lock.renew(any()) } answers { set(remote, concurrent); true }
        val result = sync(localHead, base, direction = RefSynchronizationDirection.OUTBOUND)
        assertEquals(GitHubSyncResult.CONFLICT, result.result)
        assertEquals(concurrent.name(), result.remoteSha)
        remote.refDatabase.refresh()
        assertEquals(concurrent, remote.resolve(ref))
    }

    @Test fun `invalid refs and object IDs fail before transport and lock contention is retryable`() = runBlocking {
        for (name in listOf("refs/heads/../x", "refs/remotes/main", "main")) {
            assertFailsWith<IllegalArgumentException> { sync(null, name = name) }
        }
        assertFailsWith<IllegalArgumentException> { service.synchronizeRef(RefSynchronizationInput(
            repositoryId, directory.toURI().toString(), "", ref, "invalid", null, null, false, RefSynchronizationDirection.INBOUND,
        )) }
        coEvery { lock.acquire(any(), any(), any()) } returns false
        assertFailsWith<RepositoryWriteBusyException> { sync(null) }
        Unit
    }

    @Test fun `lost write lock fails before an irreversible ref change`() = runBlocking {
        val next = commit(remote); set(remote, next)
        coEvery { lock.renew(any()) } returns false
        assertFailsWith<RepositoryWriteLockLostException> { sync(next) }
        assertNull(local.resolve(ref))
        coVerify(exactly = 0) { notifier.notifyRefsUpdated(any(), any(), any(), any()) }
    }

    @Test fun `comparison reads both sides without changing refs importing objects or following peeled tags`() = runBlocking {
        val localHead = commit(local); set(local, localHead)
        val remoteHead = commit(remote); set(remote, remoteHead, "refs/heads/other")
        set(remote, remoteHead, "refs/tags/v1")
        set(remote, remoteHead, "refs/bosca/internal")
        val compared = service.compareRefs(repositoryId, directory.toURI().toString(), "token")
        assertEquals(listOf("refs/heads/main", "refs/heads/other", "refs/tags/v1"), compared.map { it.ref })
        assertEquals(localHead.name(), compared[0].localSha); assertNull(compared[0].remoteSha)
        assertNull(compared[1].localSha); assertEquals(remoteHead.name(), compared[1].remoteSha)
        assertFalse(local.objectDatabase.has(remoteHead))
        coVerify(exactly = 0) { notifier.notifyRefsUpdated(any(), any(), any(), any()) }
        coVerify(exactly = 0) { locks.create(any()) }
    }
}
