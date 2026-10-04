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
                "V3__pull_requests.sql", "V46__github_pull_request_synchronization.sql")) {
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
