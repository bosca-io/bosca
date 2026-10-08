@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.db.*
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.git.dfs.*
import bosca.git.github.GitHubClient
import bosca.git.model.*
import bosca.git.repository.DfsPackRepositoryImpl
import bosca.git.repository.DfsRefRepositoryImpl
import bosca.git.repository.GitHubSyncRepository
import bosca.git.repository.GitHubSyncRepositoryImpl
import bosca.git.repository.GitRepositoryRepositoryImpl
import bosca.lock.DistributedLock
import bosca.lock.DistributedLockFactory
import bosca.pipelines.service.PipelineSecretService
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.internal.storage.dfs.DfsRepository
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.util.FS
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*

/** Real DFS ref/pack repositories and Git transports exercise transaction and write-lock boundaries. */
class GitHubRefSynchronizationIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("github_ref_transactions_test"); withReuse(true); start()
        }
        private val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 6,
        ), key = "github-ref-transactions-test"))
    }

    private val repositoryId = UUID.random()
    private val principalId = UUID.random()
    private val refs = DfsRefRepositoryImpl()
    private val packs = DfsPackRepositoryImpl()
    private val repository = GitHubSyncRepositoryImpl()
    private val hosted = Repository(id = repositoryId, name = "Source", slug = "source", ownerId = UUID.random())
    private val hostedService = mockk<RepositoryService>()
    private val secrets = mockk<PipelineSecretService>()
    private val security = mockk<SecurityService>()
    private val writers = bosca.security.model.Group(UUID.random(), "writers", "Writers", bosca.security.model.GroupType.SYSTEM)
    private val permissions = bosca.git.security.RepositoryPermissionEvaluator(hostedService, security, bosca.security.service.GroupEvaluator(security))
    private val protections = mockk<BranchProtectionService>()
    private val github = mockk<GitHubClient>()
    private val notifier = mockk<RefUpdateNotifier>()
    private val locks = mockk<DistributedLockFactory>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val storage = mockk<ObjectStorageService>()
    private val directory = Files.createTempDirectory("github-ref-transactions-").toFile()
    private val remote = Git.init().setBare(true).setDirectory(directory).call().repository
    private val manager = object : BoscaDfsRepositoryManager(storage, packs, refs) {
        override fun open(repositoryId: UUID): DfsRepository = open(repositoryId, null)
        override fun open(repositoryId: UUID, refConnectionManager: ConnectionManager?): DfsRepository =
            BoscaDfsRepositoryBuilder().apply {
                this.repositoryId = repositoryId
                storageAdapter = ObjectStorageDfsStorageAdapter(storage, packs)
                refAdapter = PostgresDfsRefAdapter(refs, refConnectionManager)
                repositoryDescription = DfsRepositoryDescription(repositoryId.toString())
                setFS(FS.DETECTED)
            }.build()
    }
    private val writes = RepositoryWriteServiceImpl(manager, notifier, locks)
    private fun syncService(ledger: GitHubSyncRepository = repository, writer: RepositoryWriteService = writes) =
        GitHubSyncServiceImpl(ledger, hostedService, secrets, security, writer, github, locks, mockk(), mockk(), permissions, protections)
    private val service = syncService()
    private val ref = "refs/heads/main"
    private val emitted = mutableListOf<UUID?>()
    private val pipelineTriggers = mutableListOf<Pair<RefChange, UUID>>()
    private var onNotify: suspend () -> Unit = {}
    private var onPipelineTrigger: suspend () -> Unit = {}
    private var allowRenewal = true

    @BeforeTest fun setup() = runBlocking {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { hostedService.isParentAllowed(any(), hosted, any()) } returns false
        coEvery { security.getPrincipalById(principalId) } returns bosca.security.model.Principal(id = principalId)
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        coEvery { hostedService.getPermissions(hosted) } returns listOf(RepositoryPermission(repositoryId, writers.id, bosca.security.model.PermissionAction.EDIT))
        coEvery { protections.findMatchingRule(repositoryId, any()) } returns null
        coEvery { secrets.resolve(any()) } returns "token"
        coEvery { github.repositoryUrl(any(), any()) } returns directory.toURI().toString()
        coEvery { locks.create(any()) } answers {
            val mutex = mutexes.computeIfAbsent(firstArg()) { Mutex() }
            val held = AtomicBoolean(false)
            mockk<DistributedLock>().apply {
                every { isHeld } answers { held.get() }
                coEvery { acquire(any(), any(), any()) } coAnswers {
                    withTimeoutOrNull(5_000) { mutex.lock(); held.set(true); true } ?: false
                }
                coEvery { renew(any()) } answers { held.get() && allowRenewal }
                coEvery { release() } answers { held.set(false); mutex.unlock(); true }
            }
        }
        coEvery { storage.setInputStream(any(), any(), any()) } answers {
            val bytes = secondArg<InputStream>().readBytes()
            files[firstArg<ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } answers { files.getValue(firstArg<ObjectPath>().toString()).inputStream() }
        coEvery { storage.getInputStreamRange(any(), any()) } answers {
            val range = secondArg<LongRange>()
            files.getValue(firstArg<ObjectPath>().toString()).copyOfRange(range.first.toInt(), range.last.toInt() + 1).inputStream()
        }
        coEvery { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) } coAnswers {
            val principal = arg<UUID?>(3)
            GitRepositoryRepositoryImpl().updateDiskSize(repositoryId, packs.sumPackSizeBytes(repositoryId))
            afterCommit {
                assertTrue(mutexes.getValue(RepositoryWriteLock.key(repositoryId)).isLocked)
                emitted += principal
            }
            onNotify()
        }
        coEvery { notifier.enqueuePipelineTriggers(any(), repositoryId, any(), any()) } coAnswers {
            val update = arg<List<RefChange>>(2).single()
            val principal = arg<UUID>(3)
            afterCommit {
                assertTrue(mutexes.getValue(RepositoryWriteLock.key(repositoryId)).isLocked)
                pipelineTriggers += update to principal
            }
            onPipelineTrigger()
        }
        withConnectionManager {
            connection().useStatement("""
                drop schema if exists git cascade;
                create schema git;
                create table git.repositories(id uuid primary key, disk_size_bytes bigint default 0, updated timestamptz default now());
                insert into git.repositories(id) values ('$repositoryId');
            """) { it.execute() }
            val hosting = migration("V1__git_server.sql")
            connection().useStatement(hosting.substringAfter("create table git.dfs_refs").let {
                "create table git.dfs_refs" + it.substringBefore("create table git.repository_permissions")
            }) { it.execute() }
            for (name in listOf("V23__dfs_pack_soft_delete.sql", "V44__github_intake.sql", "V45__github_ref_synchronization.sql",
                "V3__pull_requests.sql", "V46__github_pull_request_synchronization.sql", "V47__github_delivery_problems.sql")) {
                connection().useStatement(migration(name)) { it.execute() }
            }
            service.savePair(GitHubRepositoryPairInput(repositoryId, 123, "bosca-io", "source", "webhook", "token", true))
            repository.mapUser(7, principalId)
        }
        Unit
    }

    @AfterTest fun cleanup() {
        remote.close(); directory.deleteRecursively(); ProviderRegistry.clear()
    }

    private fun migration(name: String) = javaClass.getResource("/db/migrations/$name")?.readText() ?: error(name)
    private fun commit(parent: ObjectId? = null, repo: org.eclipse.jgit.lib.Repository = remote): ObjectId = repo.newObjectInserter().use { inserter ->
        val builder = CommitBuilder()
        builder.setTreeId(inserter.insert(TreeFormatter()))
        parent?.let(builder::setParentId)
        builder.author = PersonIdent("Original Author", "author@example.com")
        builder.committer = builder.author; builder.message = UUID.random().toString()
        inserter.insert(builder).also { inserter.flush() }
    }

    private fun setRemote(sha: ObjectId?, name: String = ref) {
        remote.updateRef(name).apply {
            isForceUpdate = true
            if (sha == null) delete() else { setNewObjectId(sha); update() }
        }
    }

    private suspend fun createLocalRef(name: String = ref): ObjectId = withConnectionManager {
        manager.open(repositoryId).use { repo ->
            commit(repo = repo).also { sha -> repo.updateRef(name).apply { setNewObjectId(sha) }.update() }
        }
    }

    private suspend fun deleteLocalRef(name: String = ref) = withConnectionManager {
        manager.open(repositoryId).use { repo -> repo.updateRef(name).apply { isForceUpdate = true }.delete() }
    }

    private suspend fun receive(
        before: ObjectId?, after: ObjectId?, name: String = ref, receiver: GitHubSyncService = service,
    ): GitHubDelivery {
        val body = """{"repository":{"id":123},"sender":{"id":7,"type":"User"},"ref":"$name","before":"${before?.name() ?: ObjectId.zeroId().name()}","after":"${after?.name() ?: ObjectId.zeroId().name()}"}"""
        return withConnectionManager {
            receiver.onDelivery(repositoryId, UUID.random().toString(), "push", WebhookService.computeSignature("token", body), body.toByteArray())
        }
    }

    /** A permitted PR merge may defer CI to the exact corresponding verified push. */
    private suspend fun importWithoutBuild(sha: ObjectId) = withConnectionManager {
        withRefSynchronizationTransaction(repositoryId, locks) {
            writes.synchronizeRef(RefSynchronizationInput(repositoryId, directory.toURI().toString(), "token", ref,
                null, sha.name(), null, false, RefSynchronizationDirection.INBOUND, principalId, triggerBuild = false))
            repository.saveRefState(GitHubRefState(repositoryId, ref, sha.name(), synchronized = true,
                boscaSha = sha.name(), githubSha = sha.name(), unattributedBeforeSha = ObjectId.zeroId().name()))
        }
    }

    @Test fun `manual pull imports existing history and annotated tags without deliveries or mappings and notifies as the caller`() = runBlocking {
        val base = commit()
        val next = commit(base); setRemote(next)
        val tag = remote.newObjectInserter().use { inserter ->
            val builder = org.eclipse.jgit.lib.TagBuilder().apply {
                setObjectId(next, org.eclipse.jgit.lib.Constants.OBJ_COMMIT)
                tag = "v1"; tagger = PersonIdent("Author", "author@example.com"); message = "Release"
            }
            inserter.insert(builder).also { inserter.flush() }
        }
        setRemote(tag, "refs/tags/v1")
        withConnectionManager { repository.unmapUser(7) }
        val pulled = service.pullRefs(repositoryId, principalId)
        assertEquals(setOf(ref, "refs/tags/v1"), pulled.map { it.ref }.toSet())
        assertTrue(pulled.all { it.synchronized && !it.conflict && it.boscaSha == it.githubSha })
        withConnectionManager {
            assertTrue(repository.findDeliveries(repositoryId, 0, 25).isEmpty())
            manager.open(repositoryId).use { local ->
                assertEquals(next, local.resolve(ref))
                assertEquals(tag, local.resolve("refs/tags/v1"))
                assertTrue(local.objectDatabase.has(base))
                assertEquals(next, local.resolve("refs/tags/v1^{}"))
            }
            assertNull(repository.findRefState(repositoryId, ref)?.unattributedBeforeSha)
        }
        assertEquals(listOf<UUID?>(principalId, principalId), emitted)
        service.pullRefs(repositoryId, principalId)
        assertEquals(2, emitted.size)
        assertTrue(pipelineTriggers.isEmpty())
    }

    @Test fun `manual reconciliation imports GitHub branch and tag advances without webhook attribution`() = runBlocking {
        withConnectionManager { repository.unmapUser(7) }
        for (name in listOf(ref, "refs/tags/release")) {
            val base = commit(); setRemote(base, name)
            assertFalse(service.reconcileRefs(repositoryId, principalId).single { it.ref == name }.conflict)
            val next = commit(base); setRemote(next, name)
            val result = service.reconcileRefs(repositoryId, principalId).single { it.ref == name }
            assertTrue(result.synchronized); assertFalse(result.conflict)
            assertEquals(next.name(), result.sha)
            withConnectionManager { assertEquals(next.name(), refs.findByName(repositoryId, name)?.objectId) }
            assertEquals(next, remote.resolve(name))
        }
        assertEquals(List<UUID?>(4) { principalId }, emitted)
        service.reconcileRefs(repositoryId, principalId)
        assertEquals(4, emitted.size)
    }

    @Test fun `background reconciliation observes a GitHub only advance without inventing a conflict`() = runBlocking {
        val base = commit(); setRemote(base)
        service.pullRefs(repositoryId, principalId)
        val next = commit(base); setRemote(next)
        repeat(2) {
            val observed = service.reconcileRefs(repositoryId).single()
            assertFalse(observed.conflict)
            assertEquals(base.name(), observed.sha); assertEquals(base.name(), observed.boscaSha)
            assertEquals(next.name(), observed.githubSha)
            withConnectionManager { assertEquals(base.name(), refs.findByName(repositoryId, ref)?.objectId) }
            assertEquals(next, remote.resolve(ref))
        }
        assertEquals(listOf<UUID?>(principalId), emitted)
        assertEquals(next.name(), service.reconcileRefs(repositoryId, principalId).single().sha)
        assertEquals(listOf<UUID?>(principalId, principalId), emitted)
    }

    @Test fun `manual reconciliation exports native refs propagates tracked deletions and preserves initial deletion conflicts`() = runBlocking {
        val local = createLocalRef()
        val remoteHead = commit(); setRemote(remoteHead, "refs/tags/remote")
        assertEquals(2, service.reconcileRefs(repositoryId, principalId).size)
        assertEquals(local, remote.resolve(ref))
        withConnectionManager { assertEquals(remoteHead.name(), refs.findByName(repositoryId, "refs/tags/remote")?.objectId) }
        setRemote(null, "refs/tags/remote")
        deleteLocalRef()
        val deleted = service.reconcileRefs(repositoryId, principalId)
        assertTrue(deleted.all { it.synchronized && !it.conflict && it.boscaSha == null && it.githubSha == null })
        assertNull(remote.resolve(ref))
        withConnectionManager { assertNull(refs.findByName(repositoryId, "refs/tags/remote")) }
        val recreated = createLocalRef()
        withConnectionManager { repository.saveRefState(GitHubRefState(repositoryId, ref, null,
            boscaSha = recreated.name(), conflict = true)) }
        assertTrue(service.reconcileRefs(repositoryId, principalId).single { it.ref == ref }.conflict)
        assertNull(remote.resolve(ref))
        withConnectionManager { assertEquals(recreated.name(), refs.findByName(repositoryId, ref)?.objectId) }
    }

    @Test fun `manual reconciliation retains independently edited branch and tag histories`() = runBlocking {
        for (name in listOf(ref, "refs/tags/release")) {
            val base = commit(); setRemote(base, name)
            service.pullRefs(repositoryId, principalId)
            deleteLocalRef(name); val localEdit = createLocalRef(name)
            val remoteEdit = commit(base); setRemote(remoteEdit, name)
            val observed = service.reconcileRefs(repositoryId, principalId).single { it.ref == name }
            assertTrue(observed.conflict)
            assertEquals(localEdit.name(), observed.boscaSha); assertEquals(remoteEdit.name(), observed.githubSha)
            assertEquals(remoteEdit, remote.resolve(name))
            withConnectionManager { assertEquals(localEdit.name(), refs.findByName(repositoryId, name)?.objectId) }
        }
        assertEquals(2, emitted.size)
    }

    @Test fun `manual reconciliation converges both advanced hosts when the remote is an ancestor of Bosca`() = runBlocking {
        val base = commit(); setRemote(base)
        service.pullRefs(repositoryId, principalId)
        val next = commit(base); setRemote(next)
        service.pullRefs(repositoryId, principalId)
        val tip = withConnectionManager {
            manager.open(repositoryId).use { repo ->
                commit(next, repo).also { repo.updateRef(ref).apply { setNewObjectId(it) }.update() }
            }.also {
                repository.saveRefState(GitHubRefState(repositoryId, ref, base.name(), synchronized = true,
                    boscaSha = base.name(), githubSha = base.name()))
            }
        }
        val observed = service.reconcileRefs(repositoryId, principalId).single()
        assertFalse(observed.conflict); assertEquals(tip.name(), observed.sha)
        remote.refDatabase.refresh(); assertEquals(tip, remote.resolve(ref))
        assertEquals(2, emitted.size)
    }

    @Test fun `manual reconciliation imports a pending delivery immediately and its later pipeline echo cannot duplicate CI`() = runBlocking {
        val next = commit(); setRemote(next)
        val delivery = receive(null, next)
        assertTrue(service.reconcileRefs(repositoryId).isEmpty())
        assertEquals(next.name(), service.reconcileRefs(repositoryId, principalId).single().sha)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(delivery))
        assertEquals(listOf<UUID?>(principalId), emitted)
        assertTrue(pipelineTriggers.isEmpty())
    }

    @Test fun `manual reconciliation respects protections and rolls back cancellation while retaining earlier ref commits`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        val second = commit(); setRemote(second, "refs/heads/b")
        coEvery { protections.findMatchingRule(repositoryId, "a") } returns BranchProtectionRule(
            repositoryId = repositoryId, pattern = "a", requirePullRequest = true)
        assertFailsWith<bosca.security.service.SecurityException> { service.reconcileRefs(repositoryId, principalId) }
        withConnectionManager { assertNull(refs.findByName(repositoryId, "refs/heads/a")) }
        coEvery { protections.findMatchingRule(repositoryId, "a") } returns null
        var notified = 0
        onNotify = { if (++notified == 2) throw CancellationException("cancel reconciliation") }
        assertFailsWith<CancellationException> { service.reconcileRefs(repositoryId, principalId) }
        withConnectionManager {
            assertEquals(first.name(), refs.findByName(repositoryId, "refs/heads/a")?.objectId)
            assertNull(refs.findByName(repositoryId, "refs/heads/b"))
            assertNull(repository.findRefState(repositoryId, "refs/heads/b"))
        }
        assertEquals(listOf<UUID?>(principalId), emitted)
        onNotify = {}
        assertEquals(2, service.reconcileRefs(repositoryId, principalId).size)
        assertEquals(listOf<UUID?>(principalId, principalId), emitted)
    }

    @Test fun `manual reconciliation rechecks permission and pair availability before each ref commit`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        setRemote(commit(), "refs/heads/b")
        onNotify = { coEvery { security.getPrincipalGroups(principalId) } returns emptyList() }
        assertFailsWith<bosca.security.service.SecurityException> { service.reconcileRefs(repositoryId, principalId) }
        withConnectionManager {
            assertEquals(first.name(), refs.findByName(repositoryId, "refs/heads/a")?.objectId)
            assertNull(refs.findByName(repositoryId, "refs/heads/b"))
        }
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        onNotify = {}
        for (enabled in listOf(true, false)) {
            val changing = syncService(object : GitHubSyncRepository by repository {
                override suspend fun lockPair(repositoryId: UUID): GitHubRepositoryPair? =
                    repository.lockPair(repositoryId)?.copy(version = 99, enabled = enabled)
            })
            assertFailsWith<IllegalStateException> { changing.reconcileRefs(repositoryId, principalId) }
        }
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `manual push exports Bosca refs and leaves untracked destination refs alone`() = runBlocking {
        val local = createLocalRef()
        val remoteOnly = commit(); setRemote(remoteOnly, "refs/heads/remote-only")
        val pushed = service.pushRefs(repositoryId, principalId)
        assertEquals(ref, pushed.single().ref)
        assertEquals(local, remote.resolve(ref))
        assertEquals(remoteOnly, remote.resolve("refs/heads/remote-only"))
        assertTrue(pushed.single().synchronized)
        assertTrue(emitted.isEmpty())
        val localOnly = createLocalRef("refs/heads/local-only")
        service.pullRefs(repositoryId, principalId)
        withConnectionManager { assertEquals(localOnly.name(), refs.findByName(repositoryId, "refs/heads/local-only")?.objectId) }
        assertNull(remote.resolve("refs/heads/local-only"))
    }

    @Test fun `manual push and pull preserve divergent histories and report conflicts`() = runBlocking {
        val local = createLocalRef()
        val other = commit(); setRemote(other)
        for (pull in listOf(true, false)) {
            val result = if (pull) service.pullRefs(repositoryId, principalId) else service.pushRefs(repositoryId, principalId)
            assertTrue(result.single().conflict)
            assertFalse(result.single().synchronized)
            assertEquals(local.name(), result.single().boscaSha)
            assertEquals(other.name(), result.single().githubSha)
            assertEquals(other, remote.resolve(ref))
            withConnectionManager { assertEquals(local.name(), refs.findByName(repositoryId, ref)?.objectId) }
        }
        assertTrue(emitted.isEmpty())
    }

    private suspend fun resolutionConflict(name: String = ref): GitHubRefState {
        createLocalRef(name)
        setRemote(commit(), name)
        return service.pullRefs(repositoryId, principalId).single { it.ref == name }.also { assertTrue(it.conflict) }
    }

    private fun resolutionInput(state: GitHubRefState, choice: GitHubRefResolution) =
        GitHubRefResolutionInput(repositoryId, state.ref, choice, state.boscaSha, state.githubSha)

    @Test fun `conflict choices converge only the reviewed branch or tag and keep original objects`() = runBlocking {
        for (tag in listOf(false, true)) for ((index, choice) in GitHubRefResolution.entries.withIndex()) {
            val name = if (tag) "refs/tags/release-$index" else "refs/heads/branch-$index"
            val conflict = resolutionConflict(name)
            val untouchedRef = "refs/heads/unrelated-$tag-$index"
            val untouched = commit(); setRemote(untouched, untouchedRef)
            val beforeNotifications = emitted.size
            val result = service.resolveRef(resolutionInput(conflict, choice), principalId)
            val selected = if (choice == GitHubRefResolution.BOSCA) conflict.boscaSha else conflict.githubSha
            assertEquals(selected, result.sha)
            assertEquals(selected, result.boscaSha); assertEquals(selected, result.githubSha)
            assertTrue(result.synchronized); assertFalse(result.conflict)
            remote.refDatabase.refresh()
            assertEquals(selected, remote.resolve(name)?.name())
            assertEquals(untouched, remote.resolve(untouchedRef))
            withConnectionManager {
                assertEquals(selected, refs.findByName(repositoryId, name)?.objectId)
                assertNull(refs.findByName(repositoryId, untouchedRef))
            }
            assertEquals(if (choice == GitHubRefResolution.GITHUB) listOf<UUID?>(principalId) else emptyList(), emitted.drop(beforeNotifications))
        }
    }

    @Test fun `conflict choices can retain either deletion or the surviving value on either host`() = runBlocking {
        for (choice in GitHubRefResolution.entries) for (keepDeletion in listOf(true, false)) {
            val name = "refs/heads/${choice.name.lowercase()}-$keepDeletion"
            val base = commit(); setRemote(base, name)
            service.pullRefs(repositoryId, principalId)
            val absentBosca = (choice == GitHubRefResolution.BOSCA) == keepDeletion
            val survivor: String
            if (absentBosca) {
                deleteLocalRef(name)
                val changed = commit(base); setRemote(changed, name); survivor = changed.name()
            } else {
                deleteLocalRef(name); survivor = createLocalRef(name).name(); setRemote(null, name)
            }
            val conflict = service.reconcileRefs(repositoryId).single { it.ref == name }
            assertTrue(conflict.conflict)
            val resolved = service.resolveRef(resolutionInput(conflict, choice), principalId)
            val selected = if (keepDeletion) null else survivor
            assertEquals(selected, resolved.sha); assertFalse(resolved.conflict); assertTrue(resolved.synchronized)
            remote.refDatabase.refresh()
            assertEquals(selected, remote.resolve(name)?.name())
            withConnectionManager { assertEquals(selected, refs.findByName(repositoryId, name)?.objectId) }
        }
    }

    @Test fun `resolution rejects stale reviewed observations without touching transport`() = runBlocking {
        val conflict = resolutionConflict()
        val input = resolutionInput(conflict, GitHubRefResolution.GITHUB)
        clearMocks(github, answers = false)
        for (stale in listOf(input.copy(expectedBoscaSha = null), input.copy(expectedGitHubSha = "a".repeat(40)))) {
            assertFailsWith<IllegalArgumentException> { service.resolveRef(stale, principalId) }
        }
        coVerify(exactly = 0) { github.repositoryUrl(any(), any()) }
        withConnectionManager { assertEquals(conflict.boscaSha, refs.findByName(repositoryId, ref)?.objectId) }
        assertEquals(conflict.githubSha, remote.resolve(ref)?.name())
    }

    @Test fun `resolution commits fresh observations and refuses source or destination movement since review`() = runBlocking {
        for (choice in GitHubRefResolution.entries) for (moveBosca in listOf(true, false)) {
            val name = "refs/heads/${choice.name.lowercase()}-$moveBosca"
            val conflict = resolutionConflict(name)
            val changed = if (moveBosca) {
                deleteLocalRef(name); createLocalRef(name).name()
            } else commit().also { setRemote(it, name) }.name()
            assertFailsWith<IllegalStateException> { service.resolveRef(resolutionInput(conflict, choice), principalId) }
            withConnectionManager {
                val observed = assertNotNull(repository.findRefState(repositoryId, name))
                assertTrue(observed.conflict)
                assertEquals(if (moveBosca) changed else conflict.boscaSha, observed.boscaSha)
                assertEquals(if (moveBosca) conflict.githubSha else changed, observed.githubSha)
                assertEquals(observed.boscaSha, refs.findByName(repositoryId, name)?.objectId)
                assertEquals(observed.githubSha, remote.resolve(name)?.name())
            }
        }
        assertTrue(emitted.isEmpty())
    }

    @Test fun `resolution preserves required PR restricted push and force protections`() = runBlocking {
        val conflict = resolutionConflict()
        for (rule in listOf(
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true, allowForcePush = true),
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", restrictPushAccess = listOf(UUID.random()), allowForcePush = true),
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", allowForcePush = false),
        )) {
            coEvery { protections.findMatchingRule(repositoryId, "main") } returns rule
            assertFailsWith<bosca.security.service.SecurityException> {
                service.resolveRef(resolutionInput(conflict, GitHubRefResolution.GITHUB), principalId)
            }
            withConnectionManager { assertEquals(conflict.boscaSha, refs.findByName(repositoryId, ref)?.objectId) }
        }
        assertTrue(emitted.isEmpty())
    }

    @Test fun `resolution requires valid refs an active editor enabled pairing and a recorded conflict`() = runBlocking {
        val input = GitHubRefResolutionInput(repositoryId, ref, GitHubRefResolution.BOSCA)
        for (invalid in listOf(input.copy(ref = "HEAD"), input.copy(ref = "refs/heads/../bad"),
            input.copy(expectedBoscaSha = "invalid"), input.copy(expectedGitHubSha = "invalid"))) {
            assertFailsWith<IllegalArgumentException> { service.resolveRef(invalid, principalId) }
        }
        assertFailsWith<IllegalArgumentException> { service.resolveRef(input, principalId) }
        withConnectionManager { repository.saveRefState(GitHubRefState(repositoryId, ref, null, synchronized = true)) }
        assertFailsWith<IllegalArgumentException> { service.resolveRef(input, principalId) }
        coEvery { security.getPrincipalById(principalId) } returns null
        assertFailsWith<bosca.security.service.SecurityException> { service.resolveRef(input, principalId) }
        coEvery { security.getPrincipalById(principalId) } returns bosca.security.model.Principal(id = principalId)
        withConnectionManager { val pair = assertNotNull(repository.findPair(repositoryId)); repository.updatePair(pair.copy(enabled = false)) }
        assertFailsWith<IllegalStateException> { service.resolveRef(input, principalId) }
        assertFailsWith<IllegalStateException> { service.resolveRef(input.copy(repositoryId = UUID.random()), principalId) }
        coVerify(exactly = 0) { github.repositoryUrl(any(), any()) }
        Unit
    }

    @Test fun `resolution cancellation rolls back the ref and conflict and retry notifies exactly once`() = runBlocking {
        val conflict = resolutionConflict()
        val input = resolutionInput(conflict, GitHubRefResolution.GITHUB)
        onNotify = { throw CancellationException("cancel resolution") }
        assertFailsWith<CancellationException> { service.resolveRef(input, principalId) }
        withConnectionManager {
            assertEquals(conflict.boscaSha, refs.findByName(repositoryId, ref)?.objectId)
            assertEquals(conflict, repository.findRefState(repositoryId, ref))
        }
        assertTrue(emitted.isEmpty())
        onNotify = {}
        assertFalse(service.resolveRef(input, principalId).conflict)
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `manual transfers propagate tracked deletions but preserve independently changed targets`() = runBlocking {
        val base = commit(); setRemote(base)
        service.pullRefs(repositoryId, principalId)
        setRemote(null)
        assertNull(service.pullRefs(repositoryId, principalId).single().boscaSha)
        val recreated = commit(); setRemote(recreated)
        service.pullRefs(repositoryId, principalId)
        deleteLocalRef()
        assertNull(service.pushRefs(repositoryId, principalId).single().githubSha)
        assertNull(remote.resolve(ref))
        val later = commit(); setRemote(later)
        service.pullRefs(repositoryId, principalId)
        deleteLocalRef()
        val remoteEdit = commit(later); setRemote(remoteEdit)
        assertTrue(service.pushRefs(repositoryId, principalId).single().conflict)
        assertEquals(remoteEdit, remote.resolve(ref))
    }

    @Test fun `manual pull respects required PR push access force deletion and linear history protections`() = runBlocking {
        val base = commit(); setRemote(base)
        for (rule in listOf(
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true),
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", restrictPushAccess = listOf(UUID.random())),
        )) {
            coEvery { protections.findMatchingRule(repositoryId, "main") } returns rule
            assertFailsWith<bosca.security.service.SecurityException> { service.pullRefs(repositoryId, principalId) }
        }
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns null
        service.pullRefs(repositoryId, principalId)
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns BranchProtectionRule(repositoryId = repositoryId, pattern = "main")
        setRemote(commit())
        assertFailsWith<bosca.security.service.SecurityException> { service.pullRefs(repositoryId, principalId) }
        setRemote(null)
        assertFailsWith<bosca.security.service.SecurityException> { service.pullRefs(repositoryId, principalId) }
        val merge = remote.newObjectInserter().use { inserter ->
            val builder = CommitBuilder().apply {
                setTreeId(inserter.insert(TreeFormatter()))
                setParentIds(base, commit(base))
                author = PersonIdent("Author", "author@example.com"); committer = author; message = "Merge"
            }
            inserter.insert(builder).also { inserter.flush() }
        }
        setRemote(merge)
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requireLinearHistory = true)
        assertFailsWith<bosca.security.service.SecurityException> { service.pullRefs(repositoryId, principalId) }
        withConnectionManager { assertEquals(base.name(), refs.findByName(repositoryId, ref)?.objectId) }
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `manual transfers require an enabled available pair and an active authorized caller before touching transport`() = runBlocking {
        for (operation in listOf("pull", "push", "reconcile")) {
            suspend fun transfer() = when (operation) {
                "pull" -> service.pullRefs(repositoryId, principalId)
                "push" -> service.pushRefs(repositoryId, principalId)
                else -> service.reconcileRefs(repositoryId, principalId)
            }
            coEvery { security.getPrincipalById(principalId) } returns null
            assertFailsWith<bosca.security.service.SecurityException> { transfer() }
            coEvery { security.getPrincipalById(principalId) } returns bosca.security.model.Principal(id = principalId, deletedAt = java.time.OffsetDateTime.now())
            assertFailsWith<bosca.security.service.SecurityException> { transfer() }
            coEvery { security.getPrincipalById(principalId) } returns bosca.security.model.Principal(id = principalId)
            coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
            assertFailsWith<bosca.security.service.SecurityException> { transfer() }
            coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        }
        coVerify(exactly = 0) { github.repositoryUrl(any(), any()) }
        withConnectionManager { val pair = assertNotNull(repository.findPair(repositoryId)); repository.updatePair(pair.copy(enabled = false)) }
        assertFailsWith<IllegalStateException> { service.pullRefs(repositoryId, principalId) }
        assertFailsWith<IllegalStateException> { service.pushRefs(repositoryId, principalId) }
        assertFailsWith<IllegalStateException> { service.reconcileRefs(repositoryId, principalId) }
        withConnectionManager { val pair = assertNotNull(repository.findPair(repositoryId)); repository.updatePair(pair.copy(enabled = true)) }
        coEvery { hostedService.findById(repositoryId) } returns hosted.copy(archived = true)
        assertFailsWith<IllegalStateException> { service.pullRefs(repositoryId, principalId) }
        assertFailsWith<IllegalStateException> { service.pushRefs(UUID.random(), principalId) }
        assertFailsWith<IllegalStateException> { service.reconcileRefs(repositoryId, principalId) }
        assertFailsWith<IllegalStateException> { service.reconcileRefs(UUID.random(), principalId) }
        Unit
    }

    @Test fun `manual transfers of empty repositories have no ref side effects`() = runBlocking {
        assertTrue(service.pullRefs(repositoryId, principalId).isEmpty())
        assertTrue(service.pushRefs(repositoryId, principalId).isEmpty())
        assertTrue(service.reconcileRefs(repositoryId, principalId).isEmpty())
        assertTrue(emitted.isEmpty())
    }

    @Test fun `manual pull cancellation rolls back the current ref retains earlier commits and retries without duplicate notifications`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        val second = commit(); setRemote(second, "refs/heads/b")
        var notified = 0
        onNotify = { if (++notified == 2) throw CancellationException("cancelled pull") }
        assertFailsWith<CancellationException> { service.pullRefs(repositoryId, principalId) }
        withConnectionManager {
            assertEquals(first.name(), refs.findByName(repositoryId, "refs/heads/a")?.objectId)
            assertNull(refs.findByName(repositoryId, "refs/heads/b"))
            assertNotNull(repository.findRefState(repositoryId, "refs/heads/a"))
            assertNull(repository.findRefState(repositoryId, "refs/heads/b"))
        }
        assertEquals(listOf<UUID?>(principalId), emitted)
        onNotify = {}
        assertEquals(2, service.pullRefs(repositoryId, principalId).size)
        assertEquals(listOf<UUID?>(principalId, principalId), emitted)
    }

    @Test fun `manual transfer rechecks pair version and caller permissions between ref commits`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        val second = commit(); setRemote(second, "refs/heads/b")
        onNotify = { coEvery { security.getPrincipalGroups(principalId) } returns emptyList() }
        assertFailsWith<bosca.security.service.SecurityException> { service.pullRefs(repositoryId, principalId) }
        withConnectionManager { assertNull(refs.findByName(repositoryId, "refs/heads/b")) }
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        onNotify = {}
        val changing = syncService(object : GitHubSyncRepository by repository {
            override suspend fun lockPair(repositoryId: UUID): GitHubRepositoryPair? = repository.lockPair(repositoryId)?.copy(version = 99)
        })
        assertFailsWith<IllegalStateException> { changing.pullRefs(repositoryId, principalId) }
        val disabling = syncService(object : GitHubSyncRepository by repository {
            override suspend fun lockPair(repositoryId: UUID): GitHubRepositoryPair? = repository.lockPair(repositoryId)?.copy(enabled = false)
        })
        assertFailsWith<IllegalStateException> { disabling.pullRefs(repositoryId, principalId) }
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `an unmapped GitHub writer cannot change the Bosca default branch or notify CI`() = runBlocking {
        val next = commit(); setRemote(next)
        withConnectionManager { repository.unmapUser(7) }
        val receipt = receive(null, next)
        assertNull(receipt.principalId)
        assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(receipt) }
        withConnectionManager {
            assertNull(refs.findByName(repositoryId, ref))
            assertNull(repository.findPushResult(receipt.deliveryId))
        }
        assertTrue(emitted.isEmpty()); assertTrue(pipelineTriggers.isEmpty())
    }

    @Test fun `an unmapped integration echo is a no-op and cannot acquire CI attribution`() = runBlocking {
        val next = commit(); setRemote(next)
        importWithoutBuild(next)
        withConnectionManager { repository.unmapUser(7) }
        val echo = receive(null, next)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(echo))
        withConnectionManager {
            assertEquals(next.name(), refs.findByName(repositoryId, ref)?.objectId)
            assertNotNull(repository.findRefState(repositoryId, ref)?.unattributedBeforeSha)
            assertEquals("UNCHANGED", repository.findPushResult(echo.deliveryId))
        }
        assertEquals(listOf<UUID?>(null), emitted); assertTrue(pipelineTriggers.isEmpty())
        withConnectionManager { repository.mapUser(7, principalId) }
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(receive(null, next)))
        assertEquals(listOf(RefChange(ref, ObjectId.zeroId(), next) to principalId), pipelineTriggers)
    }

    @Test fun `a verified push rechecks deleted missing and revoked Bosca writers before changing refs`() = runBlocking {
        val next = commit(); setRemote(next)
        val receipt = receive(null, next)
        for (principal in listOf(null, bosca.security.model.Principal(id = principalId, deletedAt = java.time.OffsetDateTime.now()),
            bosca.security.model.Principal(id = principalId))) {
            coEvery { security.getPrincipalById(principalId) } returns principal
            coEvery { security.getPrincipalGroups(principalId) } returns emptyList()
            assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(receipt) }
            withConnectionManager { assertNull(refs.findByName(repositoryId, ref)); assertNull(repository.findPushResult(receipt.deliveryId)) }
        }
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(receipt))
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `verified pushes cannot bypass required PRs or push access restrictions`() = runBlocking {
        val next = commit(); setRemote(next)
        val receipt = receive(null, next)
        for (rule in listOf(BranchProtectionRule(repositoryId = repositoryId, pattern = "main", requirePullRequest = true),
            BranchProtectionRule(repositoryId = repositoryId, pattern = "main", restrictPushAccess = listOf(UUID.random())))) {
            coEvery { protections.findMatchingRule(repositoryId, "main") } returns rule
            assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(receipt) }
            withConnectionManager { assertNull(refs.findByName(repositoryId, ref)) }
        }
        assertTrue(emitted.isEmpty()); assertTrue(pipelineTriggers.isEmpty())
    }

    @Test fun `verified pushes cannot force or delete a protected Bosca branch`() = runBlocking {
        val base = commit(); setRemote(base)
        service.synchronizePush(receive(null, base))
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns BranchProtectionRule(repositoryId = repositoryId, pattern = "main")
        val forced = commit(); setRemote(forced)
        assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(receive(base, forced)) }
        setRemote(null)
        assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(receive(base, null)) }
        withConnectionManager { assertEquals(base.name(), refs.findByName(repositoryId, ref)?.objectId) }
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `native merge cancellation rolls back PR metadata and DFS refs and retry commits under the write lock`() = runBlocking {
        val base = createLocalRef()
        val tip = withConnectionManager {
            manager.open(repositoryId).use { repo ->
                commit(base, repo).also { repo.updateRef("refs/heads/feature").apply { setNewObjectId(it) }.update() }
            }
        }
        val prs = bosca.git.repository.PullRequestRepositoryImpl()
        val pr = withConnectionManager { prs.create(PullRequest(repositoryId = repositoryId, number = 1,
            title = "Change", authorId = principalId, sourceBranch = "feature", targetBranch = "main")) }
        val hostedRepository = mockk<bosca.git.repository.GitRepositoryRepository>()
        coEvery { hostedRepository.findById(repositoryId) } returns hosted
        val protections = mockk<BranchProtectionService>()
        coEvery { protections.findMatchingRule(repositoryId, "main") } returns null
        val profiles = mockk<bosca.profile.profile.service.ProfileService>(relaxed = true)
        coEvery { security.getPrincipalById(any()) } returns null
        val pubsub = mockk<bosca.pubsub.PubSubService>()
        provides<bosca.pubsub.PubSubService> { pubsub }
        fun owningService(prRepository: bosca.git.repository.PullRequestRepository) = PullRequestServiceImpl(prRepository, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            hostedRepository, mockk(relaxed = true), protections, manager, mockk(relaxed = true), mockk(), locks, profiles, security)
        val pulls = owningService(prs)
        coEvery { pubsub.publish("bosca.git.pull_request", PullRequestEvent.serializer(), any()) } throws CancellationException("cancel after ref write")
        assertFailsWith<CancellationException> { withConnectionManager { pulls.merge(pr.id, MergeStrategy.FAST_FORWARD, principalId, "Author", "author@example.com") } }
        withConnectionManager {
            assertEquals(PullRequestStatus.OPEN, prs.findById(pr.id)?.status)
            assertEquals(0L, prs.findById(pr.id)?.version)
            manager.open(repositoryId).use { assertEquals(base, it.resolve(ref)) }
        }
        val failedLease = owningService(object : bosca.git.repository.PullRequestRepository by prs {
            override suspend fun updateMergeState(pr: PullRequest): PullRequest? {
                val updated = prs.updateMergeState(pr)
                refs.compareAndSwap(repositoryId, ref, base.name(), tip.name())
                return updated
            }
        })
        coEvery { pubsub.publish("bosca.git.pull_request", PullRequestEvent.serializer(), any()) } returns Unit
        assertFailsWith<IllegalStateException> { withConnectionManager {
            failedLease.merge(pr.id, MergeStrategy.FAST_FORWARD, principalId, "Author", "author@example.com")
        } }
        withConnectionManager {
            assertEquals(PullRequestStatus.OPEN, prs.findById(pr.id)?.status)
            manager.open(repositoryId).use { assertEquals(base, it.resolve(ref)) }
        }
        var committed = 0
        coEvery { pubsub.publish("bosca.git.pull_request", PullRequestEvent.serializer(), any()) } coAnswers {
            assertTrue(connection().inTransaction)
            afterCommit { assertTrue(mutexes.getValue(RepositoryWriteLock.key(repositoryId)).isLocked); committed++ }
        }
        val merged = withConnectionManager { pulls.merge(pr.id, MergeStrategy.FAST_FORWARD, principalId, "Author", "author@example.com") }
        assertEquals(tip.name(), merged.mergeSha); assertEquals(1, committed)
        withConnectionManager {
            assertEquals(PullRequestStatus.MERGED, prs.findById(pr.id)?.status)
            assertEquals(1L, prs.findById(pr.id)?.version)
            manager.open(repositoryId).use { assertEquals(tip, it.resolve(ref)) }
        }
    }

    @Test fun `intake cannot commit a delivery while synchronization holds the pair lock`() = runBlocking {
        val next = commit(); setRemote(next)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val intakePid = CompletableDeferred<Int>()
        val receiver = syncService(object : GitHubSyncRepository by repository {
            override suspend fun createDelivery(delivery: GitHubDelivery): GitHubDelivery? = transaction {
                intakePid.complete(connection().useStatement("select pg_backend_pid()") {
                    it.executeQuery().use { result -> check(result.next()); result.getInt(1) }
                })
                repository.createDelivery(delivery)
            }
        })
        withTimeout(30_000) {
            coroutineScope {
                val synchronization = async(Dispatchers.Default) {
                    withConnectionManager {
                        transaction { repository.lockPair(repositoryId); locked.complete(Unit); release.await() }
                    }
                }
                locked.await()
                val intake = async(Dispatchers.Default) { receive(null, next, receiver = receiver) }
                try {
                    val pid = intakePid.await()
                    // Observe PostgreSQL's actual wait, rather than relying on scheduler timing.
                    withTimeout(5_000) {
                        while (!withConnectionManager {
                            connection().useStatement("select wait_event_type from pg_stat_activity where pid = $pid") {
                                it.executeQuery().use { result -> result.next() && result.getString(1) == "Lock" }
                            }
                        }) delay(10)
                    }
                    assertFalse(intake.isCompleted)
                } finally {
                    release.complete(Unit)
                }
                synchronization.await()
                val delivery = intake.await()
                withConnectionManager { assertEquals(delivery, repository.findDelivery(delivery.deliveryId)) }
                assertEquals(principalId, delivery.principalId)
            }
        }
    }

    @Test fun `reconciliation defers accepted pushes and the import retains original attribution once`() = runBlocking {
        val next = commit(); setRemote(next)
        val delivery = receive(null, next)
        // Changing the mapping after intake must not change the pending occurrence's identity.
        withConnectionManager { repository.unmapUser(7) }
        repeat(2) { assertTrue(service.reconcileRefs(repositoryId).isEmpty()) }
        assertTrue(emitted.isEmpty())
        withConnectionManager {
            assertNull(refs.findByName(repositoryId, ref))
            assertNull(repository.findPushResult(delivery.deliveryId))
        }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(next.name(), service.reconcileRefs(repositoryId).single().sha)
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `a delivery accepted while comparing refs is deferred before anonymous import`() = runBlocking {
        val next = commit(); setRemote(next)
        lateinit var delivery: GitHubDelivery
        val writer = object : RepositoryWriteService by writes {
            override suspend fun compareRefs(repositoryId: UUID, remoteUrl: String, token: String): List<RefComparison> {
                val compared = writes.compareRefs(repositoryId, remoteUrl, token)
                delivery = receive(null, next)
                return compared
            }
        }
        assertTrue(syncService(writer = writer).reconcileRefs(repositoryId).isEmpty())
        assertTrue(emitted.isEmpty())
        withConnectionManager { assertNull(refs.findByName(repositoryId, ref)) }
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `reconciliation waits for a verified writer before importing and notifying CI`() = runBlocking {
        val next = commit(); setRemote(next)
        val observed = service.reconcileRefs(repositoryId).single()
        assertNull(observed.sha); assertEquals(next.name(), observed.githubSha)
        assertTrue(emitted.isEmpty()); assertTrue(pipelineTriggers.isEmpty())
        withConnectionManager { assertNull(refs.findByName(repositoryId, ref)) }
        val receipt = receive(null, next)
        repeat(2) { assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(receipt)) }
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `late verified pushes import observed branch and tag changes only once`() = runBlocking {
        for (name in listOf(ref, "refs/tags/v1")) {
            val base = commit(); setRemote(base, name)
            service.synchronizePush(receive(null, base, name))
            val next = commit(base); setRemote(next, name)
            val observed = service.reconcileRefs(repositoryId).single { it.ref == name }
            assertEquals(base.name(), observed.boscaSha); assertEquals(next.name(), observed.githubSha)
            withConnectionManager { assertEquals(base.name(), refs.findByName(repositoryId, name)?.objectId) }
            val receipt = receive(base, next, name)
            repeat(2) { assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(receipt)) }
        }
        assertEquals(listOf<UUID?>(principalId, principalId, principalId, principalId), emitted)
        assertTrue(pipelineTriggers.isEmpty())
    }

    @Test fun `a native push echo cannot claim an anonymous import even after restoring the same SHA`() = runBlocking {
        val imported = commit(); setRemote(imported)
        importWithoutBuild(imported)
        val native = withConnectionManager {
            locks.withRepositoryWriteLock(repositoryId, 5_000) {
                manager.open(repositoryId).use { repo ->
                    val next = commit(imported, repo)
                    for ((before, after) in listOf(imported to next, next to imported)) {
                        repo.updateRef(ref).apply { isForceUpdate = true; setNewObjectId(after) }.update()
                        notifier.notifyRefsUpdated(repo, repositoryId, listOf(RefChange(ref, before, after)), principalId)
                    }
                    next
                }
            } ?: error("native write timed out")
        }
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(receive(native, imported)))
        assertTrue(pipelineTriggers.isEmpty())
        assertEquals(listOf<UUID?>(null, principalId, principalId), emitted)
        withConnectionManager { assertNull(repository.findRefState(repositoryId, ref)?.unattributedBeforeSha) }
    }

    @Test fun `mapping a user later cannot authorize their original unmapped occurrence`() = runBlocking {
        val next = commit(); setRemote(next)
        service.reconcileRefs(repositoryId)
        withConnectionManager { repository.unmapUser(7) }
        val unmapped = receive(null, next)
        withConnectionManager { repository.mapUser(7, principalId) }
        assertFailsWith<bosca.security.service.SecurityException> { service.synchronizePush(unmapped) }
        withConnectionManager { assertNull(refs.findByName(repositoryId, ref)) }
        assertTrue(emitted.isEmpty())
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(receive(null, next)))
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `failed or cancelled attribution and a late ledger failure can retry without duplicate CI`() = runBlocking {
        val next = commit(); setRemote(next)
        importWithoutBuild(next)
        val delivery = receive(null, next)
        onPipelineTrigger = { error("CI queue unavailable") }
        assertEquals("CI queue unavailable", assertFailsWith<IllegalStateException> { service.synchronizePush(delivery) }.message)
        onPipelineTrigger = { throw CancellationException("cancelled attribution") }
        assertFailsWith<CancellationException> { service.synchronizePush(delivery) }
        onPipelineTrigger = {}
        val failing = syncService(object : GitHubSyncRepository by repository {
            override suspend fun savePushResult(deliveryId: String, result: String) { error("ledger unavailable") }
        })
        assertFailsWith<IllegalStateException> { failing.synchronizePush(delivery) }
        withConnectionManager {
            assertNotNull(repository.findRefState(repositoryId, ref)?.unattributedBeforeSha)
            assertNull(repository.findPushResult(delivery.deliveryId))
        }
        assertTrue(pipelineTriggers.isEmpty())
        repeat(2) { assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(delivery)) }
        assertEquals(listOf(RefChange(ref, ObjectId.zeroId(), next) to principalId), pipelineTriggers)
        assertEquals(listOf<UUID?>(null), emitted)
    }

    @Test fun `initial GitHub deletion conflicts retain Bosca branches and tags without recreating remote refs`() = runBlocking {
        for (name in listOf(ref, "refs/tags/v1")) {
            val deleted = commit(); setRemote(deleted, name)
            val local = createLocalRef(name)
            setRemote(null, name)
            val delivery = receive(deleted, null, name)
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePush(delivery))
            repeat(2) {
                val state = service.reconcileRefs(repositoryId).single { it.ref == name }
                assertTrue(state.conflict); assertFalse(state.synchronized); assertNull(state.sha)
                assertEquals(local.name(), state.boscaSha); assertNull(state.githubSha)
                remote.refDatabase.refresh(); assertNull(remote.exactRef(name))
                withConnectionManager { assertEquals(local.name(), refs.findByName(repositoryId, name)?.objectId) }
            }
            // Agreeing on deletion resolves the conflict without inventing a new branch or build.
            deleteLocalRef(name)
            val resolved = service.reconcileRefs(repositoryId).single { it.ref == name }
            assertTrue(resolved.synchronized); assertFalse(resolved.conflict); assertNull(resolved.sha)
        }
        assertTrue(emitted.isEmpty())
    }

    @Test fun `initial Bosca deletion conflicts retain GitHub branches and tags without recreating local refs`() = runBlocking {
        for (name in listOf(ref, "refs/tags/v1")) {
            val before = commit()
            val next = commit(before); setRemote(next, name)
            val delivery = receive(before, next, name)
            assertEquals(GitHubSyncResult.CONFLICT, service.synchronizePush(delivery))
            repeat(2) {
                val state = service.reconcileRefs(repositoryId).single { it.ref == name }
                assertTrue(state.conflict); assertFalse(state.synchronized); assertNull(state.sha)
                assertNull(state.boscaSha); assertEquals(next.name(), state.githubSha)
                withConnectionManager { assertNull(refs.findByName(repositoryId, name)) }
                assertEquals(next, remote.resolve(name))
            }
            setRemote(null, name)
            val resolved = service.reconcileRefs(repositoryId).single { it.ref == name }
            assertTrue(resolved.synchronized); assertFalse(resolved.conflict); assertNull(resolved.sha)
        }
        assertTrue(emitted.isEmpty())
    }

    @Test fun `cancelled creates updates and deletes roll back DFS refs and retry original notifications once`() = runBlocking {
        val base = commit(); setRemote(base)
        val created = receive(null, base)
        onNotify = { throw CancellationException("interrupted after ref mutation") }
        assertFailsWith<CancellationException> { service.synchronizePush(created) }
        withConnectionManager {
            assertNull(refs.findByName(repositoryId, ref))
            assertNull(repository.findPushResult(created.deliveryId))
        }
        assertTrue(emitted.isEmpty())
        onNotify = {}
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(created))
        val next = commit(base); setRemote(next)
        val updated = receive(base, next)
        onNotify = { throw CancellationException("interrupted after ref mutation") }
        assertFailsWith<CancellationException> { service.synchronizePush(updated) }
        withConnectionManager { assertEquals(base.name(), refs.findByName(repositoryId, ref)?.objectId) }
        onNotify = {}
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(updated))
        setRemote(null)
        val deleted = receive(next, null)
        onNotify = { throw CancellationException("interrupted after ref mutation") }
        assertFailsWith<CancellationException> { service.synchronizePush(deleted) }
        withConnectionManager { assertEquals(next.name(), refs.findByName(repositoryId, ref)?.objectId) }
        onNotify = {}
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(deleted))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(deleted))
        assertEquals(listOf<UUID?>(principalId, principalId, principalId), emitted)
        withConnectionManager { assertNull(refs.findByName(repositoryId, ref)) }
    }

    @Test fun `a failure recording the delivery result rolls back both ref and queued effects`() = runBlocking {
        val next = commit(); setRemote(next)
        val delivery = receive(null, next)
        val failing = syncService(object : GitHubSyncRepository by repository {
            override suspend fun savePushResult(deliveryId: String, result: String) { error("ledger unavailable") }
        })
        assertEquals("ledger unavailable", assertFailsWith<IllegalStateException> { failing.synchronizePush(delivery) }.message)
        withConnectionManager {
            assertNull(refs.findByName(repositoryId, ref)); assertNull(repository.findRefState(repositoryId, ref))
        }
        assertTrue(emitted.isEmpty())
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `reconciliation observes unauthenticated refs without blocking native writers`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        val second = commit(); setRemote(second, "refs/heads/b")
        assertEquals(2, service.reconcileRefs(repositoryId).size)
        assertTrue(emitted.isEmpty())
        withConnectionManager {
            locks.withRepositoryWriteLock(repositoryId, 5_000) {
                GitRepositoryRepositoryImpl().updateDiskSize(repositoryId, 10)
                assertEquals(2, repository.findAllRefStates(repositoryId).size)
                assertNull(refs.findByName(repositoryId, "refs/heads/a"))
                assertNull(refs.findByName(repositoryId, "refs/heads/b"))
            } ?: error("native writer timed out")
        }
    }

    @Test fun `losing the write lock before transaction commit rolls back the imported ref`() = runBlocking {
        val next = commit(); setRemote(next)
        val delivery = receive(null, next)
        onNotify = { allowRenewal = false }
        assertFailsWith<RepositoryWriteLockLostException> { service.synchronizePush(delivery) }
        withConnectionManager {
            assertNull(refs.findByName(repositoryId, ref))
            assertNull(repository.findPushResult(delivery.deliveryId))
        }
        assertTrue(emitted.isEmpty())
        allowRenewal = true; onNotify = {}
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        assertEquals(listOf<UUID?>(principalId), emitted)
    }

    @Test fun `reconciliation observes missed ref changes until their verified occurrences arrive`() = runBlocking {
        val first = commit(); setRemote(first, "refs/heads/a")
        val second = commit(); setRemote(second, "refs/heads/b")
        repeat(2) { assertEquals(2, service.reconcileRefs(repositoryId).size) }
        assertTrue(emitted.isEmpty())
        service.synchronizePush(receive(null, first, "refs/heads/a"))
        service.synchronizePush(receive(null, second, "refs/heads/b"))
        repeat(2) { assertEquals(2, service.reconcileRefs(repositoryId).size) }
        assertEquals(listOf<UUID?>(principalId, principalId), emitted)
        coVerify(exactly = 2) { notifier.notifyRefsUpdated(any(), repositoryId, any(), any()) }
    }

    @Test fun `synchronization commits before releasing its lock even inside a caller transaction`() = runBlocking {
        val next = commit(); setRemote(next)
        val delivery = receive(null, next)
        withConnectionManager {
            transaction {
                assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
                assertEquals(listOf<UUID?>(principalId), emitted)
                assertFalse(mutexes.getValue(RepositoryWriteLock.key(repositoryId)).isLocked)
                withConnectionManager { assertEquals(next.name(), refs.findByName(repositoryId, ref)?.objectId) }
            }
        }
    }
}
