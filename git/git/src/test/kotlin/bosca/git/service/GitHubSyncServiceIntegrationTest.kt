@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.git.service

import bosca.db.ConnectionConfig
import bosca.db.afterCommit
import bosca.events.catalog.CoreGitEventCatalogRegistrarProvider
import bosca.events.catalog.EventCatalogRegistrar
import bosca.git.model.GitHubDelivery
import bosca.git.model.GitHubSyncResult
import bosca.git.model.GitHubRefState
import bosca.git.model.RefUpdateEvent
import bosca.git.model.GitRefKind
import bosca.git.model.GitRefUpdateAction
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.configuration.PipelinesRuntimeConfiguration
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.model.PipelineGraph
import bosca.pipelines.model.PipelineRunStatus
import bosca.pipelines.node.ActionNode
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodePosition
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNode
import bosca.pipelines.node.PipelineValue
import bosca.pipelines.repository.*
import bosca.pipelines.service.PipelineRunResultStore
import bosca.pipelines.service.PipelineRunServiceImpl
import bosca.sharedqueue.jobs.Job
import bosca.sharedqueue.jobs.JobQueue
import bosca.sharedqueue.jobs.asCoroutineContext
import io.mockk.every
import io.mockk.spyk
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.polymorphic
import bosca.db.ConnectionFactoryImpl
import bosca.db.ConnectionPool
import bosca.db.asCoroutineContext
import bosca.db.connection
import bosca.db.transaction
import bosca.di.ProviderRegistry
import bosca.di.provides
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.git.graphql.*
import bosca.graphql.*
import bosca.graphql.dispatcher.Dispatcher
import bosca.graphql.dispatcher.DispatchersRegistrar
import bosca.graphql.server.RuntimeWiringBuilder
import bosca.observability.ErrorCapture
import bosca.security.model.Group
import bosca.security.model.GroupType
import bosca.security.service.GroupEvaluator
import bosca.security.service.AuthenticationProviders
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUIDSerializer
import bosca.serialization.OffsetDateTimeSerializer
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Tracer
import bosca.git.model.GitHubDeliveryConflictException
import bosca.git.model.GitHubRepositoryPairInput
import bosca.git.model.GitHubWebhookRejectedException
import bosca.git.model.GitHubWebhookInputException
import bosca.git.model.GitHubWebhookUnavailableException
import bosca.git.model.Repository
import bosca.git.repository.GitHubSyncRepositoryImpl
import bosca.pipelines.service.PipelineSecretService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.serialization.UUID
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.*

/** Production intake, migrations and generated JDBC mapping run against PostgreSQL. */
class GitHubSyncServiceIntegrationTest {
    companion object {
        private val postgres = SharedPostgreSQLContainer("pgvector/pgvector:pg17").apply {
            withDatabaseName("github_intake_test")
            withReuse(true)
            start()
        }
        private val pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 4,
        ), key = "github-intake-test"))
    }

    private val repositoryId = UUID.random()
    private val principalId = UUID.random()
    private val servicePrincipalId = UUID.random()
    private val repository = GitHubSyncRepositoryImpl()
    private val hosted = Repository(id = repositoryId, slug = "source", name = "Source", ownerId = UUID.random())
    private val hostedService = mockk<RepositoryService>()
    private val secrets = mockk<PipelineSecretService>()
    private val security = mockk<SecurityService>()
    private val writers = Group(UUID.random(), "writers", "Writers", GroupType.SYSTEM)
    private val permissions = bosca.git.security.RepositoryPermissionEvaluator(hostedService, security, GroupEvaluator(security))
    private val protections = mockk<BranchProtectionService>()
    private val pipelines = mockk<bosca.pipelines.service.PipelineService>()
    private val writes = mockk<RepositoryWriteService>()
    private val github = mockk<bosca.git.github.GitHubClient>()
    private val locks = mockk<bosca.lock.DistributedLockFactory>()
    private val lock = mockk<bosca.lock.DistributedLock>()
    private val service = GitHubSyncServiceImpl(repository, hostedService, secrets, security, writes, github, locks, mockk(), mockk(), permissions, protections)
    private val input = GitHubRepositoryPairInput(repositoryId, 123, "bosca-io", "source", "github-webhook", "github-token", true)
    private val secret = "test-secret-✓"
    private val json = Json { serializersModule = SerializersModule {
        contextual(UUIDSerializer())
        contextual(OffsetDateTimeSerializer())
    } }

    @BeforeTest
    fun setup() {
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<bosca.lock.DistributedLockFactory>(singleton = true) { locks }
        coEvery { locks.create(any()) } returns lock
        coEvery { lock.acquire(any(), any(), any()) } returns true
        coEvery { lock.renew(any()) } returns true
        coEvery { lock.release() } returns true
        BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { hostedService.isParentAllowed(any(), hosted, any()) } returns false
        coEvery { secrets.resolve("github-webhook") } returns secret
        coEvery { secrets.resolve("github-token") } returns "token"
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId)
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        coEvery { hostedService.getPermissions(hosted) } returns listOf(bosca.git.model.RepositoryPermission(repositoryId, writers.id, bosca.security.model.PermissionAction.EDIT))
        coEvery { protections.findMatchingRule(repositoryId, any()) } returns null
        coEvery { github.repositoryUrl(any(), "token") } returns "https://github.com/bosca-io/source.git"
        withDb {
            connection().useStatement("drop schema if exists git cascade; create schema git; create table git.repositories(id uuid primary key)") { it.execute() }
            val hosting = javaClass.getResource("/db/migrations/V1__git_server.sql")?.readText() ?: error("Missing hosting migration")
            connection().useStatement("create table git.dfs_refs" + hosting.substringAfter("create table git.dfs_refs")
                .substringBefore("create table git.dfs_packs")) { it.execute() }
            val migration = javaClass.getResource("/db/migrations/V44__github_intake.sql")?.readText() ?: error("Missing migration")
            connection().useStatement(migration) { it.execute() }
            val refMigration = javaClass.getResource("/db/migrations/V45__github_ref_synchronization.sql")?.readText()
                ?: error("Missing ref migration")
            connection().useStatement(refMigration) { it.execute() }
            for (name in listOf("V3__pull_requests.sql", "V46__github_pull_request_synchronization.sql")) {
                connection().useStatement(javaClass.getResource("/db/migrations/$name")?.readText() ?: error(name)) { it.execute() }
            }
            connection().useStatement("insert into git.repositories(id) values ('$repositoryId')") { it.execute() }
        }
    }

    @AfterTest fun cleanup() = ProviderRegistry.clear()

    private val ref = "refs/heads/main"
    private val sha = "1".repeat(40)
    private val nextSha = "2".repeat(40)
    private fun pushPayload(before: String = "0".repeat(40), after: String = sha) =
        payload(extra = ",\"ref\":\"$ref\",\"before\":\"$before\",\"after\":\"$after\"")

    private fun refEvent(before: String? = null, after: String? = sha) = RefUpdateEvent(
        repositoryId, "Source", ref, "main", GitRefKind.BRANCH, GitRefUpdateAction.UPDATED, before, after,
    )

    @Test fun `push synchronization persists a common baseline and redelivery cannot repeat the write`() = withDb {
        service.savePair(input); service.mapUser(7, principalId)
        val delivery = receive(pushPayload())
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.APPLIED, sha, sha)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery.copy(principalId = UUID.random())))
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
        coVerify(exactly = 1) { writes.synchronizeRef(match {
            it.direction == RefSynchronizationDirection.INBOUND && it.principalId == principalId &&
                it.beforeSha == null && it.afterSha == sha && !it.hasSynchronized
        }) }
        val state = service.findRefStates(repositoryId, 0, 25).single()
        assertTrue(state.synchronized); assertFalse(state.conflict)
        assertEquals(sha, state.sha); assertEquals(sha, state.boscaSha); assertEquals(sha, state.githubSha)
        assertTrue(service.findRefStates(repositoryId, 1, 25).isEmpty())
        assertFailsWith<IllegalArgumentException> { service.findRefStates(repositoryId, -1, 25) }
        assertFailsWith<IllegalArgumentException> { service.findRefStates(repositoryId, 0, 0) }
    }

    @Test fun `conflicts preserve the common baseline until refs actually converge`() = withDb {
        service.savePair(input)
        repository.saveRefState(GitHubRefState(repositoryId, ref, sha, synchronized = true, boscaSha = sha, githubSha = sha))
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.CONFLICT, nextSha, "3".repeat(40))
        assertEquals(GitHubSyncResult.CONFLICT, service.synchronizeRef(refEvent(sha, nextSha)))
        coVerify { writes.synchronizeRef(match { it.hasSynchronized && it.synchronizedSha == sha && it.direction == RefSynchronizationDirection.OUTBOUND }) }
        assertEquals(sha, repository.findRefState(repositoryId, ref)?.sha)
        assertTrue(assertNotNull(repository.findRefState(repositoryId, ref)).conflict)
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.STALE, nextSha, "4".repeat(40))
        assertEquals(GitHubSyncResult.STALE, service.synchronizeRef(refEvent(sha, nextSha)))
        assertTrue(assertNotNull(repository.findRefState(repositoryId, ref)).conflict)
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.UNCHANGED, nextSha, nextSha)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizeRef(refEvent(sha, nextSha)))
        assertFalse(assertNotNull(repository.findRefState(repositoryId, ref)).conflict)
        assertEquals(nextSha, repository.findRefState(repositoryId, ref)?.sha)
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.APPLIED, null, null)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizeRef(refEvent(nextSha, null)))
        val deleted = assertNotNull(repository.findRefState(repositoryId, ref))
        assertTrue(deleted.synchronized); assertNull(deleted.sha)
    }

    @Test fun `an initial conflict or stale event does not invent a common baseline`() = withDb {
        service.savePair(input)
        for (status in listOf(GitHubSyncResult.CONFLICT, GitHubSyncResult.STALE)) {
            coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(status, sha, nextSha)
            assertEquals(status, service.synchronizeRef(refEvent()))
            assertFalse(assertNotNull(repository.findRefState(repositoryId, ref)).synchronized)
        }
        coVerify(exactly = 2) { writes.synchronizeRef(match { !it.hasSynchronized }) }
    }

    @Test fun `failed writes and cancellation retain retryable deliveries without advancing state`() = withDb {
        service.savePair(input)
        service.mapUser(7, principalId)
        val delivery = receive(pushPayload())
        for (failure in listOf(IllegalStateException("unavailable"), kotlinx.coroutines.CancellationException("cancelled"))) {
            coEvery { writes.synchronizeRef(any()) } throws failure
            val caught = assertFails { service.synchronizePush(delivery) }
            assertEquals<Class<*>>(failure.javaClass, caught.javaClass)
            assertEquals(failure.message, caught.message)
            assertTrue(generateSequence(caught) { it.cause }.any { it === failure })
            assertNull(repository.findPushResult(delivery.deliveryId))
            assertNull(repository.findRefState(repositoryId, ref))
        }
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.APPLIED, sha, sha)
        assertEquals(GitHubSyncResult.APPLIED, service.synchronizePush(delivery))
    }

    @Test fun `only persisted eligible pushes from an active hosted pair may synchronize`() = withDb {
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizeRef(refEvent()))
        service.savePair(input)
        val delivery = receive(pushPayload())
        assertFailsWith<NoSuchElementException> { service.synchronizePush(delivery.copy(deliveryId = UUID.random().toString())) }
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePush(receive(event = "ping")))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePush(receive(
            payload(extra = ",\"pull_request\":{\"head\":{\"repo\":{\"id\":123}}}"), "pull_request")))
        val other = repository.createDelivery(delivery.copy(deliveryId = UUID.random().toString()))
            ?: error("Missing delivery")
        // A different persisted occurrence cannot be smuggled through a caller-provided repository ID.
        val otherId = UUID.random()
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        repository.createPair(bosca.git.model.GitHubRepositoryPair(otherId, 999, "owner", "other", "w", "t", enabled = true))
        coEvery { hostedService.findById(otherId) } returns hosted.copy(id = otherId)
        assertFailsWith<IllegalArgumentException> { service.synchronizePush(other.copy(repositoryId = otherId)) }
        for (unavailable in listOf<Repository?>(null, hosted.copy(archived = true), hosted.copy(deleted = true))) {
            coEvery { hostedService.findById(repositoryId) } returns unavailable
            assertEquals(GitHubSyncResult.IGNORED, service.synchronizePush(delivery))
        }
        coEvery { hostedService.findById(repositoryId) } returns hosted
        service.savePair(input.copy(enabled = false))
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizePush(delivery))
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
    }

    @Test fun `missing transport credentials or a mismatched remote cannot complete a push`() = withDb {
        service.savePair(input)
        service.mapUser(7, principalId)
        val delivery = receive(pushPayload(after = "0".repeat(40)))
        coEvery { secrets.resolve("github-token") } returns ""
        assertFailsWith<GitHubWebhookUnavailableException> { service.synchronizePush(delivery) }
        coEvery { secrets.resolve("github-token") } returns "token"
        coEvery { github.repositoryUrl(any(), any()) } throws IllegalStateException("Repository mismatch")
        assertFailsWith<IllegalStateException> { service.synchronizePush(delivery) }
        assertNull(repository.findPushResult(delivery.deliveryId))
        coEvery { github.repositoryUrl(any(), any()) } returns "https://github.com/bosca-io/source.git"
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.UNCHANGED, null, null)
        assertEquals(GitHubSyncResult.UNCHANGED, service.synchronizePush(delivery))
        coVerify { writes.synchronizeRef(match { it.afterSha == null && it.principalId == principalId }) }
    }

    @Test fun `reconciliation observes unsigned inbound changes and transfers only native outbound history`() = withDb {
        service.savePair(input)
        val inbound = "refs/heads/inbound"
        val ahead = "refs/heads/ahead"
        val deleted = "refs/tags/deleted"
        val stale = "refs/heads/stale"
        repository.saveRefState(GitHubRefState(repositoryId, ahead, sha, synchronized = true))
        repository.saveRefState(GitHubRefState(repositoryId, deleted, sha, synchronized = true))
        coEvery { writes.compareRefs(repositoryId, any(), "token") } returns listOf(
            RefComparison(ref, sha, null), RefComparison(inbound, null, sha),
            RefComparison(ahead, nextSha, "3".repeat(40)), RefComparison(stale, sha, nextSha),
        )
        coEvery { writes.synchronizeRef(any()) } answers {
            val request = firstArg<RefSynchronizationInput>()
            when {
                request.ref == stale -> RefSynchronizationResult(GitHubSyncResult.STALE, sha, nextSha)
                request.ref == ahead && request.direction == RefSynchronizationDirection.INBOUND ->
                    RefSynchronizationResult(GitHubSyncResult.CONFLICT, nextSha, "3".repeat(40))
                else -> RefSynchronizationResult(GitHubSyncResult.APPLIED, request.afterSha, request.afterSha)
            }
        }
        val recovered = service.reconcileRefs()
        assertEquals(setOf(ref, inbound, ahead, deleted, stale), recovered.map { it.ref }.toSet())
        coVerify { writes.synchronizeRef(match { it.ref == ref && it.direction == RefSynchronizationDirection.OUTBOUND && !it.hasSynchronized }) }
        coVerify(exactly = 0) { writes.synchronizeRef(match { it.direction == RefSynchronizationDirection.INBOUND }) }
        coVerify(exactly = 2) { writes.synchronizeRef(match { it.principalId == null }) }
        assertNull(recovered.single { it.ref == inbound }.sha)
        assertEquals(sha, recovered.single { it.ref == inbound }.githubSha)
        assertFalse(recovered.any { it.conflict })
        assertEquals(nextSha, recovered.single { it.ref == ahead }.sha)
        assertTrue(recovered.single { it.ref == deleted }.synchronized)
    }

    @Test fun `reconciliation preserves independently changed tags and exports only unchanged remote baselines`() = withDb {
        service.savePair(input)
        val tag = "refs/tags/v1"
        repository.saveRefState(GitHubRefState(repositoryId, tag, sha, synchronized = true))
        repository.saveRefState(GitHubRefState(repositoryId, ref, sha, synchronized = true))
        coEvery { writes.compareRefs(repositoryId, any(), any()) } returns listOf(
            RefComparison(tag, nextSha, "3".repeat(40)), RefComparison(ref, nextSha, sha),
        )
        coEvery { writes.synchronizeRef(any()) } answers {
            val request = firstArg<RefSynchronizationInput>()
            if (request.ref == tag) RefSynchronizationResult(GitHubSyncResult.CONFLICT, nextSha, "3".repeat(40))
            else RefSynchronizationResult(GitHubSyncResult.APPLIED, nextSha, nextSha)
        }
        assertTrue(service.reconcileRefs(repositoryId).single { it.ref == tag }.conflict)
        coVerify(exactly = 0) { writes.synchronizeRef(match { it.ref == tag }) }
        coVerify { writes.synchronizeRef(match { it.ref == ref && it.direction == RefSynchronizationDirection.OUTBOUND }) }
        coEvery { secrets.resolve("github-token") } returns null
        assertFailsWith<GitHubWebhookUnavailableException> { service.reconcileRefs(repositoryId) }
        coEvery { secrets.resolve("github-token") } returns "token"
        service.savePair(input.copy(enabled = false))
        assertTrue(service.reconcileRefs().isEmpty())
        assertTrue(service.reconcileRefs(repositoryId).isEmpty())
        assertTrue(service.reconcileRefs(UUID.random()).isEmpty())
    }

    @Test fun `reconciliation skips refs both repositories still hold at the common value`() = withDb {
        service.savePair(input)
        val tag = "refs/tags/v1"
        val conflicted = "refs/heads/conflicted"
        repository.saveRefState(GitHubRefState(repositoryId, ref, sha, synchronized = true, boscaSha = sha, githubSha = sha))
        repository.saveRefState(GitHubRefState(repositoryId, tag, sha, synchronized = true, boscaSha = sha, githubSha = sha))
        repository.saveRefState(GitHubRefState(repositoryId, conflicted, sha, synchronized = true, conflict = true))
        coEvery { writes.compareRefs(repositoryId, any(), any()) } returns listOf(
            RefComparison(ref, sha, sha), RefComparison(tag, sha, sha), RefComparison(conflicted, sha, sha),
        )
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.UNCHANGED, sha, sha)
        val reconciled = service.reconcileRefs(repositoryId)
        assertEquals(listOf(conflicted, ref, tag), reconciled.map { it.ref })
        assertTrue(reconciled.all { it.sha == sha && it.synchronized })
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
        assertFalse(assertNotNull(repository.findRefState(repositoryId, conflicted)).conflict)
    }

    @Test fun `pending push queries exclude completed ignored non-push and other repository deliveries`() = withDb {
        service.savePair(input)
        val pending = receive(pushPayload())
        val completed = receive(pushPayload())
        repository.savePushResult(completed.deliveryId, "APPLIED")
        repository.createDelivery(pending.copy(deliveryId = UUID.random().toString(), ignored = true))
        receive(pushPayload(), event = "ping")
        val otherId = UUID.random()
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        repository.createPair(bosca.git.model.GitHubRepositoryPair(otherId, 456, "owner", "other", "w", "t"))
        repository.createDelivery(pending.copy(deliveryId = UUID.random().toString(), repositoryId = otherId))
        assertEquals(listOf(ref), repository.findPendingPushRefs(repositoryId))
        assertEquals(listOf(pending), repository.findPendingPushDeliveries(repositoryId, ref))
        assertTrue(repository.findPendingPushDeliveries(repositoryId, "refs/heads/other").isEmpty())
    }

    @Test fun `reconciliation redispatches pending deleted refs absent from both repositories and ref state`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input); service.mapUser(7, principalId)
        val delivery = receive(pushPayload(before = sha, after = "0".repeat(40)))
        coEvery { writes.compareRefs(repositoryId, any(), any()) } returns emptyList()
        assertTrue(service.reconcileRefs(repositoryId).isEmpty())
        assertNull(repository.findPushResult(delivery.deliveryId))
        assertEquals(2, fixture.jobs.size)
        fixture.dispatchAll(); fixture.driveAll()
        assertEquals(2, DeliveryProbe.observed.size)
        assertTrue(DeliveryProbe.observed.all { it.delivery == delivery && it.delivery.principalId == principalId })
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
    }

    @Test fun `reconciling all pairs continues past a failing pair and then fails loudly`() = withDb {
        val otherId = UUID.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        coEvery { hostedService.findById(otherId) } returns hosted.copy(id = otherId, slug = "other")
        service.savePair(input)
        service.savePair(input.copy(repositoryId = otherId, githubRepositoryId = 456, name = "other"))
        coEvery { writes.compareRefs(repositoryId, any(), any()) } throws IllegalStateException("GitHub unavailable")
        coEvery { writes.compareRefs(otherId, any(), any()) } returns listOf(RefComparison(ref, null, sha))
        coEvery { writes.synchronizeRef(any()) } returns RefSynchronizationResult(GitHubSyncResult.APPLIED, sha, sha)
        val failure = assertFailsWith<IllegalStateException> { service.reconcileRefs() }
        assertTrue(failure.message.orEmpty().contains(repositoryId.toString()))
        assertFalse(failure.message.orEmpty().contains(otherId.toString()))
        assertEquals("GitHub unavailable", failure.cause?.message)
        assertNull(assertNotNull(repository.findRefState(otherId, ref)).sha)
        assertEquals(sha, repository.findRefState(otherId, ref)?.githubSha)
        assertNull(repository.findRefState(repositoryId, ref))
        // A single selected pair still fails directly with its own error.
        assertEquals("GitHub unavailable", assertFailsWith<IllegalStateException> { service.reconcileRefs(repositoryId) }.message)
    }

    @Test fun `unpaired refs skip the write lock and paired lock contention remains retryable`() = withDb {
        coEvery { lock.acquire(any(), any(), any()) } returns false
        assertEquals(GitHubSyncResult.IGNORED, service.synchronizeRef(refEvent()))
        coVerify(exactly = 0) { locks.create(any()) }
        service.savePair(input)
        assertFailsWith<RepositoryWriteBusyException> { service.synchronizeRef(refEvent()) }
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
    }

    @Test fun `reconciliation rejects a pair configuration changed after comparing refs`() = withDb {
        service.savePair(input)
        coEvery { writes.compareRefs(repositoryId, any(), any()) } coAnswers {
            service.savePair(input.copy(owner = "renamed-owner"))
            listOf(RefComparison(ref, null, sha))
        }
        val failure = assertFailsWith<IllegalStateException> { service.reconcileRefs(repositoryId) }
        assertEquals("GitHub repository pair changed during reconciliation", failure.message)
        coVerify(exactly = 0) { writes.synchronizeRef(any()) }
    }

    @Test fun `all-pair reconciliation preserves cancellation and reports each ordinary failure`() = withDb {
        val otherId = UUID.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        coEvery { hostedService.findById(otherId) } returns hosted.copy(id = otherId, slug = "other")
        service.savePair(input)
        service.savePair(input.copy(repositoryId = otherId, githubRepositoryId = 456, name = "other"))
        coEvery { writes.compareRefs(repositoryId, any(), any()) } throws kotlinx.coroutines.CancellationException("cancel reconciliation")
        assertFailsWith<kotlinx.coroutines.CancellationException> { service.reconcileRefs() }
        coVerify(exactly = 0) { writes.compareRefs(otherId, any(), any()) }
        coEvery { writes.compareRefs(repositoryId, any(), any()) } throws IllegalStateException("first failure")
        coEvery { writes.compareRefs(otherId, any(), any()) } throws IllegalArgumentException("second failure")
        val failure = assertFailsWith<IllegalStateException> { service.reconcileRefs() }
        assertEquals("first failure", failure.cause?.message)
        assertEquals("second failure", failure.suppressed.single().message)
        assertTrue(failure.message.orEmpty().contains(repositoryId.toString()))
        assertTrue(failure.message.orEmpty().contains(otherId.toString()))
    }

    private fun payload(userId: Long = 7, type: String = "User", remoteId: Long = 123, extra: String = "") =
        """{"repository":{"id":$remoteId,"full_name":"bosca-io/source"},"sender":{"id":$userId,"type":"$type"},"message":"✓ café"$extra}"""

    private suspend fun receive(body: String = payload(), event: String = "push", id: String = UUID.random().toString()) =
        service.onDelivery(repositoryId, id, event, WebhookService.computeSignature(secret, body), body.toByteArray())

    @Test fun `pair stores only secret references and updates with optimistic locking`() = withDb {
        val pair = service.savePair(input)
        assertEquals(0, pair.version)
        assertEquals(pair, service.findPair(repositoryId))
        val renamed = service.savePair(input.copy(owner = "new-owner", name = "renamed", enabled = false))
        assertEquals(1, renamed.version)
        assertEquals(pair.created, renamed.created)
        assertEquals("new-owner", renamed.owner)
        assertEquals("renamed", renamed.name)
        assertEquals("github-token", renamed.tokenSecretName)
        assertEquals("github-webhook", renamed.webhookSecretName)
        assertFalse(renamed.enabled)
        assertFailsWith<IllegalStateException> { service.savePair(input) }
        assertFailsWith<IllegalArgumentException> { service.savePair(input.copy(githubRepositoryId = 999, version = 1)) }
        assertFailsWith<GitHubWebhookRejectedException> { receive() }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
    }

    @Test fun `signed repeated delivery retains one identity original attribution and complete JSON`() = withDb {
        service.savePair(input)
        val user = service.mapUser(7, principalId)
        assertEquals(principalId, user.principalId)
        val id = UUID.random().toString()
        val accepted = receive(id = id)
        assertEquals(principalId, accepted.principalId)
        assertEquals(7, accepted.githubUserId)
        assertFalse(accepted.ignored)
        assertEquals("✓ café", accepted.payload.jsonObject["message"]?.jsonPrimitive?.content)
        service.unmapUser(7)
        val duplicate = receive(id = id.uppercase())
        assertEquals(accepted, duplicate)
        assertEquals(listOf(accepted), service.findDeliveries(repositoryId, 0, 25))
        assertTrue(service.findUsers(0, 25).isEmpty())
    }

    @Test fun `delivery ID rejects changed bytes event or repository`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        receive(id = id)
        assertFailsWith<GitHubDeliveryConflictException> { receive(body = payload(userId = 8), id = id) }
        assertFailsWith<GitHubDeliveryConflictException> { receive(event = "pull_request", id = id) }
        assertEquals(1, service.findDeliveries(repositoryId, 0, 25).size)
    }

    @Test fun `rollback releases a delivery occurrence for retry`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        assertFailsWith<IllegalStateException> { transaction { receive(id = id); error("rollback") } }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
        assertEquals(id, receive(id = id).deliveryId)
    }

    @Test fun `unknown missing or bot users never inherit an integration principal`() = withDb {
        service.savePair(input)
        service.mapUser(7, principalId)
        assertNull(receive(payload(userId = 8)).principalId)
        assertNull(receive(payload(type = "Bot")).principalId)
        assertNull(receive("""{"repository":{"id":123}}""").principalId)
        assertNull(receive("""{"repository":{"id":123},"sender":{"id":7}}""").principalId)
        assertNull(receive(payload(userId = 0)).principalId)
    }

    @Test fun `fork pull requests are ignored while paired branches are eligible`() = withDb {
        service.savePair(input)
        val prefix = ",\"pull_request\":{\"head\":{\"repo\":{\"id\":"
        assertTrue(receive(payload(extra = prefix + "999}}}"), "pull_request").ignored)
        assertFalse(receive(payload(extra = prefix + "123}}}"), "pull_request").ignored)
        assertTrue(receive(payload(extra = ",\"pull_request\":{\"head\":{\"repo\":null}}"), "pull_request").ignored)
        assertTrue(receive(event = "ping").ignored)
    }

    @Test fun `invalid authenticity or repository identity never persists intake`() = withDb {
        service.savePair(input)
        for (signature in listOf(null, "sha1=bad", "sha256=" + "0".repeat(64))) {
            assertFailsWith<GitHubWebhookRejectedException> {
                service.onDelivery(repositoryId, UUID.random().toString(), "push", signature, "not JSON".toByteArray())
            }
        }
        assertFailsWith<GitHubWebhookRejectedException> { receive(payload(remoteId = 999)) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, "bad", "push", null, byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, UUID.random().toString(), "bad event", null, byteArrayOf()) }
        assertFailsWith<GitHubWebhookInputException> { receive("invalid JSON") }
        val bytes = byteArrayOf(0xc3.toByte(), 0x28)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        }
        val signature = "sha256=" + mac.doFinal(bytes).joinToString("") { "%02x".format(it) }
        assertFailsWith<IllegalArgumentException> { service.onDelivery(repositoryId, UUID.random().toString(), "push", signature, bytes) }
        assertTrue(service.findDeliveries(repositoryId, 0, 25).isEmpty())
    }

    @Test fun `administrators can disable intake after a repository is archived`() = withDb {
        service.savePair(input)
        coEvery { hostedService.findById(repositoryId) } returns hosted.copy(archived = true)
        assertFalse(service.savePair(input.copy(enabled = false)).enabled)
    }

    @Test fun `a delivery occurrence cannot move between paired repositories`() = withDb {
        service.savePair(input)
        val id = UUID.random().toString()
        receive(id = id)
        val otherId = UUID.random()
        connection().useStatement("insert into git.repositories(id) values ('$otherId')") { it.execute() }
        coEvery { hostedService.findById(otherId) } returns hosted.copy(id = otherId)
        service.savePair(input.copy(repositoryId = otherId, githubRepositoryId = 456))
        val body = payload(remoteId = 456)
        assertFailsWith<GitHubDeliveryConflictException> {
            service.onDelivery(otherId, id, "push", WebhookService.computeSignature(secret, body), body.toByteArray())
        }
        assertTrue(service.findDeliveries(otherId, 0, 25).isEmpty())
        assertEquals(1, service.findDeliveries(repositoryId, 0, 25).size)
    }

    @Test fun `unavailable repository or configuration fails before intake`() = withDb {
        assertFailsWith<GitHubWebhookRejectedException> { receive() }
        service.savePair(input)
        for (record in listOf(null, hosted.copy(deleted = true), hosted.copy(archived = true))) {
            coEvery { hostedService.findById(repositoryId) } returns record
            assertFailsWith<GitHubWebhookRejectedException> { receive() }
        }
        coEvery { hostedService.findById(repositoryId) } returns hosted
        coEvery { secrets.resolve("github-webhook") } returns null
        assertFailsWith<GitHubWebhookUnavailableException> { receive() }
    }

    @Test fun `invalid pairing user mapping and pagination fail loudly`() = withDb {
        for (invalid in listOf(input.copy(githubRepositoryId = 0), input.copy(version = -1), input.copy(owner = "a/b"),
            input.copy(name = ""), input.copy(webhookSecretName = ""), input.copy(tokenSecretName = ""), input.copy(version = 1))) {
            assertFailsWith<IllegalArgumentException> { service.savePair(invalid) }
        }
        coEvery { secrets.resolve("github-webhook") } returns ""
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        coEvery { secrets.resolve("github-webhook") } returns secret
        coEvery { secrets.resolve("github-token") } returns null
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        coEvery { hostedService.findById(repositoryId) } returns null
        assertFailsWith<NoSuchElementException> { service.savePair(input) }
        coEvery { hostedService.findById(repositoryId) } returns hosted.copy(archived = true)
        assertFailsWith<IllegalArgumentException> { service.savePair(input) }
        assertFailsWith<IllegalArgumentException> { service.mapUser(0, principalId) }
        assertFailsWith<IllegalArgumentException> { service.unmapUser(0) }
        coEvery { security.getPrincipalById(principalId) } returns null
        assertFailsWith<NoSuchElementException> { service.mapUser(7, principalId) }
        coEvery { security.getPrincipalById(principalId) } returns Principal(id = principalId, deletedAt = java.time.OffsetDateTime.now())
        assertFailsWith<IllegalArgumentException> { service.mapUser(7, principalId) }
        assertFailsWith<IllegalArgumentException> { service.findUsers(-1, 25) }
        assertFailsWith<IllegalArgumentException> { service.findDeliveries(repositoryId, 0, 0) }
        assertFailsWith<IllegalArgumentException> { service.findDeliveries(repositoryId, 0, 101) }
    }

    @Test fun `GitHub official test vector validates exact raw bytes`() {
        val signature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        assertTrue(GitHubSyncServiceImpl.verifySignature("It's a Secret to Everybody", signature, "Hello, World!".toByteArray()))
        assertFalse(GitHubSyncServiceImpl.verifySignature("It's a Secret to Everybody", signature, "Hello, World!\n".toByteArray()))
    }

    @Test fun `generated service provider resolves the production intake dependencies`() = withDb {
        provides<bosca.git.repository.GitHubSyncRepository>(singleton = true) { repository }
        provides<RepositoryService>(singleton = true) { hostedService }
        provides<PipelineSecretService>(singleton = true) { secrets }
        provides<SecurityService>(singleton = true) { security }
        provides<RepositoryWriteService>(singleton = true) { writes }
        provides<bosca.git.github.GitHubClient>(singleton = true) { github }
        provides<PullRequestService>(singleton = true) { mockk() }
        provides<bosca.profile.profile.service.ProfileService>(singleton = true) { mockk() }
        provides<bosca.git.security.RepositoryPermissionEvaluator>(singleton = true) { permissions }
        provides<BranchProtectionService>(singleton = true) { protections }
        val wired = GitHubSyncServiceImplProvider().get()
        val pair = wired.savePair(input)
        assertEquals(pair, wired.findPair(repositoryId))
        val body = payload()
        val accepted = wired.onDelivery(repositoryId, UUID.random().toString(), "push", WebhookService.computeSignature(secret, body), body.toByteArray())
        assertEquals(listOf(accepted), wired.findDeliveries(repositoryId, 0, 25))
    }

    @Test fun `generated GraphQL resolvers expose persisted configuration with administrator authorization`() = withDb {
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<AuthenticationProviders> { AuthenticationProviders(emptyArray()) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<Tracer> { GlobalOpenTelemetry.getTracer("GitHubSyncGraphQLTest") }
        val registered = bosca.git.configuration.GitSchemaRegistrar().load()
        assertTrue("type GitHubRepositoryPair" in registered)
        val types = javaClass.getResource("/graphql/github.graphqls")?.readText() ?: error("Missing GitHub SDL")
        // Load the complete GitHub SDL, including its root namespace extensions.
        SchemaRegistry.initialize(object : SchemaRegistrar {
            override suspend fun load() = """
                scalar UUID
                scalar DateTime
                scalar JSON
                scalar Long
                scalar Upload
                enum GitPullRequestStatus { OPEN DRAFT CLOSED MERGED }
                type Query { _empty: Boolean }
                type Mutation { _empty: Boolean }
                $types
            """.trimIndent()
        })
        val groups = GroupEvaluator(security)
        val root = object : SchemaRoot {
            override val query = object : QueryRoot {}
            override val mutation = object : MutationRoot {}
            override val subscription = object : SubscriptionRoot {}
        }
        val graphQL = object : GraphQLService(root, false) {
            override val dispatchersRegistrar = object : DispatchersRegistrar {
                override suspend fun dispatchers(): Map<String, Dispatcher> = listOf(
                    GitHubSyncQueryDispatcher(GitHubSyncQuery(service, groups)),
                    GitHubSyncMutationDispatcher(GitHubSyncMutation(service, groups)),
                    GitHubRepositoryPairControllerDispatcher(GitHubRepositoryPairController()),
                    GitHubUserControllerDispatcher(GitHubUserController()),
                    GitHubDeliveryControllerDispatcher(GitHubDeliveryController()),
                    GitHubRefStateControllerDispatcher(GitHubRefStateController()),
                    GitHubPullRequestStateControllerDispatcher(GitHubPullRequestStateController()),
                    GitHubPullRequestSnapshotControllerDispatcher(GitHubPullRequestSnapshotController()),
                ).associateBy { it.type.typeName }
            }
            override suspend fun initialize(builder: RuntimeWiringBuilder) {
                builder.type("Query") { field("github") { GitHub } }
                builder.type("Mutation") { field("github") { GitHubMutation } }
            }
        }
        val admin = ImpersonatedAuthenticationContext(Principal(id = principalId), listOf(
            Group(name = "administrators", description = "", type = GroupType.SYSTEM),
        ))
        val mutation = """mutation { github { savePair(input: {
            repositoryId: "$repositoryId", githubRepositoryId: 123, owner: "bosca-io", name: "source",
            webhookSecretName: "github-webhook", tokenSecretName: "github-token", enabled: true
        }) { repositoryId githubRepositoryId owner name webhookSecretName tokenSecretName enabled version created modified } } }"""
        val saved = graphQL.execute(admin, GraphQLRequest(query = mutation)).jsonObject
        assertFalse("errors" in saved, saved.toString())
        assertEquals("0", saved.getValue("data").jsonObject.getValue("github").jsonObject.getValue("savePair").jsonObject.getValue("version").jsonPrimitive.content)
        val mapped = graphQL.execute(admin, GraphQLRequest(query = """mutation { github {
            mapUser(githubUserId: 7, principalId: "$principalId") { githubUserId principalId created modified }
        } }""")).jsonObject
        assertFalse("errors" in mapped, mapped.toString())
        val user = service.findUsers(0, 25).single()
        val accepted = receive()
        repository.saveRefState(GitHubRefState(repositoryId, ref, sha, synchronized = true, boscaSha = sha, githubSha = nextSha, conflict = true))
        val prSnapshot = bosca.git.model.GitHubPullRequestSnapshot("Title", "Body", "feature", "main", bosca.git.model.PullRequestStatus.MERGED, sha)
        repository.savePullRequestState(bosca.git.model.GitHubPullRequestState(repositoryId = repositoryId,
            githubId = 456, githubNumber = 7, snapshot = prSnapshot, boscaSnapshot = prSnapshot,
            githubSnapshot = prSnapshot.copy(title = "Other"), problem = "Concurrent edit"))
        val query = """{ github {
            pair(repositoryId: "$repositoryId") { repositoryId githubRepositoryId owner name webhookSecretName tokenSecretName enabled version created modified }
            users { githubUserId principalId created modified }
            deliveries(repositoryId: "$repositoryId") { deliveryId repositoryId event payload githubUserId principalId ignored created }
            refStates(repositoryId: "$repositoryId") { repositoryId ref sha synchronized boscaSha githubSha conflict modified }
            pullRequestStates(repositoryId: "$repositoryId") { repositoryId pullRequestId githubId githubNumber problem modified
                snapshot { title description sourceBranch targetBranch status mergeSha }
                bosca { title description sourceBranch targetBranch status mergeSha }
                github { title description sourceBranch targetBranch status mergeSha }
            }
        } }"""
        val result = graphQL.execute(admin, GraphQLRequest(query = query)).jsonObject
        assertFalse("errors" in result, result.toString())
        val data = result.getValue("data").jsonObject.getValue("github").jsonObject
        val refData = data.getValue("refStates").jsonArray.single().jsonObject
        assertEquals(sha, refData.getValue("sha").jsonPrimitive.content)
        val prData = data.getValue("pullRequestStates").jsonArray.single().jsonObject
        assertEquals("Concurrent edit", prData.getValue("problem").jsonPrimitive.content)
        assertEquals("Other", prData.getValue("github").jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals("true", refData.getValue("conflict").jsonPrimitive.content)
        assertEquals(principalId.toString(), data.getValue("users").jsonArray.single().jsonObject.getValue("principalId").jsonPrimitive.content)
        val delivery = data.getValue("deliveries").jsonArray.single().jsonObject
        assertEquals(accepted.payload, delivery.getValue("payload"))
        assertEquals("7", delivery.getValue("githubUserId").jsonPrimitive.content)
        assertEquals(principalId.toString(), delivery.getValue("principalId").jsonPrimitive.content)
        val ordinary = ImpersonatedAuthenticationContext(Principal(id = principalId), emptyList())
        assertTrue("errors" in graphQL.execute(ordinary, GraphQLRequest(query = query)).jsonObject)
        val unmapped = graphQL.execute(admin, GraphQLRequest(query = """mutation { github {
            unmapUser(githubUserId: 7)
        } }""")).jsonObject
        assertFalse("errors" in unmapped, unmapped.toString())
        assertEquals("true", unmapped.getValue("data").jsonObject.getValue("github").jsonObject.getValue("unmapUser").jsonPrimitive.content)
        assertTrue(service.findUsers(0, 25).isEmpty())
        // Native-safe explicit serializers retain IDs, body and contextual timestamps across job boundaries.
        val pair = assertNotNull(service.findPair(repositoryId))
        assertEquals(pair, json.decodeFromString(bosca.git.model.GitHubRepositoryPair.serializer(), json.encodeToString(bosca.git.model.GitHubRepositoryPair.serializer(), pair)))
        assertEquals(user, json.decodeFromString(bosca.git.model.GitHubUser.serializer(), json.encodeToString(bosca.git.model.GitHubUser.serializer(), user)))
        assertEquals(accepted, json.decodeFromString(bosca.git.model.GitHubDelivery.serializer(), json.encodeToString(bosca.git.model.GitHubDelivery.serializer(), accepted)))
    }

    @Serializable
    @SerialName("githubDeliveryProbe")
    class DeliveryProbe(
        override val id: String,
        override val name: String = "",
        override val description: String = "",
        override val position: NodePosition = NodePosition(),
    ) : ActionNode() {
        override suspend fun execute(context: PipelineContext, inputs: NodeInputs): PipelineValue? {
            val delivery = context.json.decodeFromJsonElement(
                GitHubDelivery.serializer(), requireNotNull(inputs.first).encode(context.json),
            )
            observed += Observation(
                delivery, context.authentication.principal()?.id, context.runId, context.runJobId,
            )
            return inputs.first
        }

        companion object {
            val observed = mutableListOf<Observation>()
        }
    }

    data class Observation(
        val delivery: GitHubDelivery,
        val principalId: UUID?,
        val runId: UUID?,
        val jobId: UUID?,
    )

    private class EventFixture(
        val pipelines: MutableList<Pipeline>,
        val pipelineService: bosca.pipelines.service.PipelineService,
        val runService: bosca.pipelines.service.PipelineRunService,
        val runRepository: PipelineRunRepositoryImpl,
        val json: Json,
        val resultStore: PipelineRunResultStore,
    ) {
        val jobs = linkedMapOf<UUID, Job>()
        private val completedJobs = mutableSetOf<UUID>()
        val events = mutableListOf<bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent>()
        lateinit var queue: JobQueue
        var failPublication = false

        suspend fun dispatchAll() {
            for (queued in jobs.values.filter { job -> job.getId() !in completedJobs && events.any {
                it.jobId == job.getId() && it.executor == bosca.pipelines.trigger.PipelineDispatchJobExecutor::class.qualifiedName
            } }) {
                val job = spyk(queued)
                every { job.isLocked } returns true
                withContext(queue.asCoroutineContext(job)) {
                    bosca.pipelines.trigger.PipelineDispatchJobExecutor(pipelineService).execute()
                }
                completedJobs += queued.getId()
            }
        }

        suspend fun driveAll() {
            for (queued in jobs.values.filter { job -> job.getId() !in completedJobs && events.any {
                it.jobId == job.getId() && it.executor == bosca.pipelines.trigger.PipelineRunJobExecutor::class.qualifiedName
            } }) {
                val job = spyk(queued)
                every { job.isLocked } returns true
                withContext(queue.asCoroutineContext(job)) {
                    bosca.pipelines.trigger.PipelineRunJobExecutor(pipelineService, runService, json).execute()
                }
                completedJobs += queued.getId()
            }
        }

        suspend fun executeBackingJobs(security: SecurityService) {
            for (queued in jobs.values.filter { job -> job.getId() !in completedJobs && events.any {
                it.jobId == job.getId() && it.executor == bosca.pipelines.trigger.ExecuteNodeInJobExecutor::class.qualifiedName
            } }) {
                val definition = json.decodeFromJsonElement(bosca.pipelines.trigger.ExecuteNodeInJob.serializer(), queued.getDefinition())
                val job = spyk(queued)
                every { job.isLocked } returns true
                withContext(queue.asCoroutineContext(job)) {
                    bosca.pipelines.trigger.ExecuteNodeInJobExecutor(runService, pipelineService, resultStore, security,
                        PipelinesRuntimeConfiguration()).execute()
                }
                val run = assertNotNull(runRepository.getById(definition.runId))
                val parent = spyk(jobs.getValue(requireNotNull(run.runJobId)))
                every { parent.isLocked } returns true
                withContext(queue.asCoroutineContext(parent)) {
                    bosca.pipelines.trigger.PipelineRunDriveListenerImpl(json).onChildStatusChanged(
                        parent, queued, bosca.sharedqueue.jobs.JobStatus.COMPLETE, null)
                }
                completedJobs += queued.getId()
            }
        }

        suspend fun runs(delivery: GitHubDelivery) = DeliveryProbe.observed
            .filter { it.delivery.deliveryId == delivery.deliveryId }
            .map { requireNotNull(runRepository.getById(requireNotNull(it.runId))) }
    }

    /** Exercises generated event dispatch, both production pipeline jobs and JDBC run mapping. */
    private suspend fun installTriggeredPipeline(sync: Boolean = false, pullRequests: Boolean = false): EventFixture {
        connection().useStatement("""
            drop schema if exists pipelines cascade;
            create table if not exists groups (id uuid primary key);
            do $$ begin
                if not exists (select 1 from pg_type where typname = 'permission_action') then
                    create type permission_action as enum ('view', 'edit', 'manage', 'delete', 'execute');
                end if;
            end $$;
        """.trimIndent()) { it.execute() }
        for (resource in PipelinesMigration().resources) {
            val sql = PipelinesMigration::class.java.getResource("/db/migrations/$resource")?.readText()
                ?: error("Missing pipeline migration: $resource")
            connection().useStatement(sql) { it.execute() }
        }
        val graphJson = Json(json) {
            serializersModule = SerializersModule {
                include(json.serializersModule)
                include(bosca.pipelines.node.GitPipelineNodeSerializersProvider().module)
                polymorphic(PipelineNode::class) {
                    subclass(InputNode::class, InputNode.serializer())
                    subclass(OutputNode::class, OutputNode.serializer())
                    subclass(DeliveryProbe::class, DeliveryProbe.serializer())
                }
            }
        }
        ProviderRegistry.clear()
        provides<ConnectionPool>(singleton = true) { pool }
        provides<Json>(singleton = true) { graphJson }
        provides<bosca.pipelines.node.PipelineNodeSerializers>(name = "Git", singleton = true) {
            bosca.pipelines.node.GitPipelineNodeSerializersProvider()
        }
        provides<bosca.pipelines.service.NodeSuspensionService> { bosca.pipelines.service.NodeSuspensionServiceImpl() }
        provides<EventCatalogRegistrar>(name = "CoreGit", singleton = true) { CoreGitEventCatalogRegistrarProvider() }
        coEvery { security.getPrincipalGroups(any<UUID>()) } returns emptyList()
        coEvery { security.getPrincipalGroups(principalId) } returns listOf(writers)
        coEvery { security.getPrincipalByIdentifier(any()) } returns Principal(id = servicePrincipalId)
        coEvery { pipelines.graphAsJsonElement(any()) } answers {
            val p = firstArg<Pipeline>()
            graphJson.encodeToJsonElement(PipelineGraph.serializer(), PipelineGraph(p.nodes, p.edges))
        }
        coEvery { pipelines.decodeGraph(any()) } answers {
            graphJson.decodeFromJsonElement(PipelineGraph.serializer(), firstArg())
        }
        val eventName = GitHubDelivery.serializer().descriptor.serialName
        val graph = Pipeline(
            id = UUID.NIL,
            name = "Inbound GitHub", key = "configured-by-administrator", triggered = true, acceptedInputType = eventName,
            nodes = listOf(InputNode("input", acceptedType = eventName),
                if (pullRequests) bosca.git.pipeline.GitHubImportPullRequestNode("probe") else if (sync) bosca.git.pipeline.GitHubPushNode("probe") else DeliveryProbe("probe"), OutputNode("output")),
            edges = listOf(
                PipelineEdge(id = "in", source = "input", target = "probe"),
                PipelineEdge(id = "out", source = "probe", target = "output"),
            ),
        )
        val record = PipelineRepositoryImpl().add(PipelineRecord(
            name = graph.name, key = graph.key, acceptedInputType = eventName, triggered = true,
            graph = pipelines.graphAsJsonElement(graph),
        ))
        val runRepository = PipelineRunRepositoryImpl()
        val pending = mutableMapOf<Pair<UUID, String>, bosca.pipelines.service.PipelineRunNodeResult>()
        val resultStore = mockk<PipelineRunResultStore>()
        coEvery { resultStore.put(any(), any(), any(), any()) } answers {
            pending[firstArg<UUID>() to secondArg<String>()] = bosca.pipelines.service.PipelineRunNodeResult(arg(2), arg(3))
        }
        coEvery { resultStore.get(any(), any()) } answers { pending[firstArg<UUID>() to secondArg<String>()] }
        coEvery { resultStore.remove(any(), any()) } answers { pending.remove(firstArg<UUID>() to secondArg<String>()) }
        val runService = PipelineRunServiceImpl(
            runRepository = runRepository,
            runLogRepository = PipelineRunLogRepositoryImpl(),
            resultStore = resultStore,
            nodeExecutionRepository = NodeExecutionRepositoryImpl(),
            iterationRepository = PipelineRunIterationRepositoryImpl(),
            rollbackRepository = RollbackRepositoryImpl(),
            pipelineService = pipelines,
            executor = PipelineExecutorImpl(),
            securityService = security,
            config = PipelinesRuntimeConfiguration(),
            pubSub = mockk(relaxed = true),
        )
        provides<bosca.pipelines.service.PipelineRunService> { runService }
        val fixture = EventFixture(mutableListOf(graph.copy(id = record.id)), pipelines, runService, runRepository, graphJson, resultStore)
        coEvery { pipelines.descriptorFor(any()) } answers {
            val key = when (firstArg<PipelineNode>()) {
                is bosca.git.pipeline.GitHubPushNode -> "githubPush"
                is bosca.git.pipeline.GitHubImportPullRequestNode -> "githubImportPullRequest"
                else -> ""
            }
            bosca.pipelines.node.GitPipelineNodeSerializersProvider().descriptors.find { it.key == key }
        }
        coEvery { pipelines.triggeredEventTypes() } answers { if (fixture.pipelines.isEmpty()) emptySet() else setOf(eventName) }
        coEvery { pipelines.triggeredFor(eventName) } answers { fixture.pipelines.toList() }
        coEvery { pipelines.get(any()) } answers { fixture.pipelines.find { it.id == firstArg<UUID>() } }
        provides<bosca.pipelines.PipelineEventDispatcher>(singleton = true) {
            bosca.pipelines.trigger.PipelineEventDispatcherImpl(pipelines, graphJson)
        }
        val delegate = mockk<JobQueue>(relaxed = true)
        coEvery { delegate.enqueue(any()) } coAnswers {
            val job = firstArg<Job>()
            if (job.getId() == UUID.NIL) job.setPersistentId(UUID.random())
            afterCommit {
                check(!fixture.failPublication) { "Queue unavailable" }
                fixture.jobs[job.getId()] = job
            }
            job.getId()
        }
        val channel = mockk<bosca.sharedqueue.jobs.enqueue.JobEnqueueEventChannel>()
        coEvery { channel.emit(any()) } answers { fixture.events += firstArg<bosca.sharedqueue.jobs.enqueue.JobEnqueueEvent>() }
        fixture.queue = bosca.sharedqueue.jobs.enqueue.EventEmittingJobQueue(delegate, "pipelines", channel)
        provides<JobQueue>(name = bosca.pipelines.configuration.PipelinesJobQueueNames.jobQueue, singleton = true) { fixture.queue }
        DeliveryProbe.observed.clear()
        return fixture
    }

    @Test fun `verified PR reaches its generated backing job and resumes with persisted counterpart state`() = withDb {
        val fixture = installTriggeredPipeline(pullRequests = true)
        val pulls = mockk<PullRequestService>()
        val profiles = mockk<bosca.profile.profile.service.ProfileService>()
        val profileId = UUID.random()
        val author = bosca.profile.model.Profile(id = profileId, name = "Original author", type = bosca.profile.model.ProfileType.GENERIC,
            visibility = bosca.profile.model.ProfileVisibility.USER)
        coEvery { profiles.getPrimaryProfile(any()) } returns author
        val native = bosca.git.repository.PullRequestRepositoryImpl()
        coEvery { pulls.create(any(), profileId) } coAnswers {
            val request = firstArg<bosca.git.model.CreatePullRequestInput>()
            native.create(bosca.git.model.PullRequest(repositoryId = request.repositoryId, number = 1,
                title = request.title, description = request.description, authorId = profileId,
                sourceBranch = request.sourceBranch, targetBranch = request.targetBranch))
        }
        coEvery { pulls.findById(any()) } coAnswers { native.findById(firstArg()) }
        val sync = GitHubSyncServiceImpl(repository, hostedService, secrets, security, writes, github, locks, pulls, profiles, permissions, protections)
        provides<GitHubSyncService> { sync }
        val remote = bosca.git.model.GitHubPullRequest(456, 7, "PR_456", "Current title", "Current body", "open",
            modified = java.time.OffsetDateTime.now(), user = bosca.git.model.GitHubPullRequestUser(7, "original", "User"),
            head = bosca.git.model.GitHubPullRequestBranch("feature", sha, bosca.git.model.GitHubWebhookRepository(123)),
            base = bosca.git.model.GitHubPullRequestBranch("main", nextSha, bosca.git.model.GitHubWebhookRepository(123)))
        coEvery { github.getPullRequest(any(), any(), 7) } returns remote
        coEvery { writes.compareRefs(any(), any(), any()) } returns listOf(
            RefComparison("refs/heads/feature", sha, sha), RefComparison("refs/heads/main", nextSha, nextSha))
        coEvery { writes.synchronizeRef(any()) } answers {
            val input = firstArg<RefSynchronizationInput>()
            RefSynchronizationResult(GitHubSyncResult.UNCHANGED, input.afterSha, input.afterSha)
        }
        service.savePair(input); service.mapUser(7, principalId)
        val body = payload(extra = ",\"number\":7,\"pull_request\":" + json.encodeToString(bosca.git.model.GitHubPullRequest.serializer(), remote))
        val receipt = receive(body, event = "pull_request")
        fixture.dispatchAll(); fixture.driveAll()
        assertEquals(PipelineRunStatus.SUSPENDED, fixture.runRepository.listActive(0, 25).single().status)
        fixture.executeBackingJobs(security)
        assertTrue(fixture.runRepository.listActive(0, 25).isEmpty())
        val state = sync.findPullRequestStates(repositoryId, 0, 25).single()
        assertEquals("Current title", native.findById(requireNotNull(state.pullRequestId))?.title)
        assertEquals(profileId, native.findById(requireNotNull(state.pullRequestId))?.authorId)
        assertEquals(receipt, receive(body, event = "pull_request", id = receipt.deliveryId))
        fixture.dispatchAll(); fixture.driveAll(); fixture.executeBackingJobs(security)
        coVerify(exactly = 1) { pulls.create(any(), profileId) }
        coVerify { writes.synchronizeRef(match { !it.triggerBuild }) }
    }

    @Test fun `verified push runs through the generated node backing job real transport and resume without repeating imports`() = withDb {
        val fixture = installTriggeredPipeline(sync = true)
        val directory = java.nio.file.Files.createTempDirectory("bosca-github-pipeline-").toFile()
        val remote = org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(directory).call().repository
        val local = org.eclipse.jgit.internal.storage.dfs.InMemoryRepository.Builder()
            .setRepositoryDescription(org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription("pipeline"))
            .setFS(org.eclipse.jgit.util.FS.DETECTED).build()
        try {
            val manager = mockk<bosca.git.dfs.BoscaDfsRepositoryManager>()
            every { manager.open(repositoryId) } answers { local.incrementOpen(); local }
            every { manager.open(repositoryId, any()) } answers { local.incrementOpen(); local }
            val notifier = mockk<RefUpdateNotifier>(relaxed = true)
            val locks = mockk<bosca.lock.DistributedLockFactory>()
            val lock = mockk<bosca.lock.DistributedLock>()
            coEvery { locks.create(any()) } returns lock
            coEvery { lock.acquire(any(), any(), any()) } returns true
            coEvery { lock.renew(any()) } returns true
            coEvery { lock.release() } returns true
            val actualWrites = RepositoryWriteServiceImpl(manager, notifier, locks)
            val actualSync = GitHubSyncServiceImpl(repository, hostedService, secrets, security, actualWrites, github, locks, mockk(), mockk(), permissions, protections)
            provides<GitHubSyncService> { actualSync }
            coEvery { github.repositoryUrl(any(), any()) } returns directory.toURI().toString()
            val commit = remote.newObjectInserter().use { inserter ->
                val builder = org.eclipse.jgit.lib.CommitBuilder()
                builder.setTreeId(inserter.insert(org.eclipse.jgit.lib.TreeFormatter()))
                builder.author = org.eclipse.jgit.lib.PersonIdent("Original Author", "author@example.com")
                builder.committer = builder.author; builder.message = "Original commit"
                inserter.insert(builder).also { inserter.flush() }
            }
            remote.updateRef(ref).apply { setNewObjectId(commit) }.update()
            service.savePair(input); service.mapUser(7, principalId)
            val delivery = receive(pushPayload(after = commit.name()))
            assertTrue(actualSync.reconcileRefs(repositoryId).isEmpty())
            assertNull(local.resolve(ref))
            assertNull(repository.findPushResult(delivery.deliveryId))
            fixture.dispatchAll(); fixture.driveAll()
            val active = fixture.runRepository.listActive(0, 25)
            assertEquals(2, active.size)
            assertTrue(active.all { it.status == PipelineRunStatus.SUSPENDED })
            fixture.executeBackingJobs(security)
            assertEquals(commit, local.resolve(ref))
            assertTrue(fixture.runRepository.listActive(0, 25).isEmpty())
            assertEquals("APPLIED", repository.findPushResult(delivery.deliveryId))
            assertEquals(commit.name(), service.findRefStates(repositoryId, 0, 25).single().sha)
            assertEquals(delivery, receive(pushPayload(after = commit.name()), id = delivery.deliveryId))
            fixture.dispatchAll(); fixture.driveAll(); fixture.executeBackingJobs(security)
            coVerify(exactly = 1) { notifier.notifyRefsUpdated(any(), repositoryId, any(), principalId) }
            assertTrue(fixture.events.any { it.executor == bosca.pipelines.trigger.ExecuteNodeInJobExecutor::class.qualifiedName })
        } finally {
            local.close(); remote.close(); directory.deleteRecursively()
        }
    }

    @Test fun `GitHub package installs through the real pipeline service JDBC repository and generated node catalog`() = withDb {
        installTriggeredPipeline()
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        provides<bosca.pipelines.node.PipelineNodeSerializers>(name = "CorePipelines", singleton = true) {
            bosca.pipelines.node.CorePipelinesPipelineNodeSerializersProvider()
        }
        val pubSub = mockk<bosca.di.ObjectProvider<bosca.pubsub.PubSubService>>()
        every { pubSub.exists } returns false
        val scheduler = mockk<bosca.scheduler.service.SchedulerService>(relaxed = true)
        coEvery { scheduler.getJobs(any(), any(), any()) } returns emptyList()
        val schedulerProvider = mockk<bosca.di.ObjectProvider<bosca.scheduler.service.SchedulerService>>()
        every { schedulerProvider.exists } returns true
        coEvery { schedulerProvider.get() } returns scheduler
        val actual = bosca.pipelines.service.PipelineServiceImpl(
            PipelineRepositoryImpl(), PipelinePermissionRepositoryImpl(), PipelineExecutorImpl(), security,
            PipelinesRuntimeConfiguration(), pubSub, schedulerProvider,
        )
        try {
            val registry = bosca.git.installer.GitHubPackageInstallerRegistry()
            val installation = registry.installation()
            val installer = registry.installer(actual)
            installer.install(installation, installation.versions.single())
            val imported = assertNotNull(actual.getByKey("github-import-refs"))
            val exported = assertNotNull(actual.getByKey("github-export-refs"))
            val reconciled = assertNotNull(actual.getByKey("github-reconcile-refs"))
            assertIs<bosca.git.pipeline.GitHubPushNode>(imported.nodes[1])
            assertIs<bosca.git.pipeline.GitHubRefNode>(exported.nodes[1])
            assertIs<bosca.git.pipeline.GitHubReconcileRefsNode>(reconciled.nodes[1])
            assertTrue(imported.triggered); assertTrue(exported.triggered); assertFalse(reconciled.triggered)
            assertEquals("0 * * * *", reconciled.schedule)
            coVerify(exactly = 1) { scheduler.createJob(match {
                it.jobName == bosca.pipelines.trigger.PipelineScheduledRunExecutor.NAME && it.cronExpression == "0 * * * *" && it.enabled == true &&
                    json.decodeFromJsonElement(bosca.pipelines.trigger.PipelineScheduledRunJob.serializer(), it.jobParameters).pipelineId == reconciled.id
            }, UUID.NIL) }
            for (pipeline in listOf(imported, exported, reconciled)) {
                assertNull(actual.validateGraph(actual.graphAsJsonElement(pipeline)))
            }
            val edited = actual.save(imported.id, "Operator's graph", imported.description, imported.acceptedInputType,
                false, imported.version, actual.graphAsJsonElement(imported), key = imported.key)
            installer.install(installation, installation.versions.single())
            val retained = assertNotNull(actual.getByKey(imported.key))
            assertEquals(edited.name, retained.name)
            assertEquals(edited.version, retained.version)
            assertFalse(retained.triggered)
            assertEquals(actual.graphAsJsonElement(edited), actual.graphAsJsonElement(retained))
            coVerify(exactly = 2) { scheduler.createJob(any(), any()) }
        } finally {
            actual.shutdown()
        }
    }

    @Test fun `verified delivery reaches existing pipeline jobs with its original user data`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        service.mapUser(7, principalId)
        val delivery = receive()
        assertTrue(fixture.runs(delivery).isEmpty())
        fixture.dispatchAll()
        fixture.driveAll()
        val run = fixture.runs(delivery).single()
        assertEquals(PipelineRunStatus.OK, run.status)
        assertNull(run.principalId)
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
        assertEquals(servicePrincipalId, DeliveryProbe.observed.single().principalId)
        assertEquals(run.runJobId, DeliveryProbe.observed.single().jobId)
        assertTrue(fixture.events.any { it.executor == bosca.pipelines.trigger.PipelineDispatchJobExecutor::class.qualifiedName })
        assertTrue(fixture.events.any { it.executor == bosca.pipelines.trigger.PipelineRunJobExecutor::class.qualifiedName })

        service.unmapUser(7)
        assertEquals(delivery, receive(id = delivery.deliveryId.uppercase()))
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(2, DeliveryProbe.observed.size)
        assertTrue(DeliveryProbe.observed.all { it.delivery == delivery && it.principalId == servicePrincipalId })
    }

    @Test fun `delivery IDs use existing event scope deduplication`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        val delivery = bosca.events.withEventManager {
            bosca.events.deferredEvents {
                receive(id = id)
                receive(id = id)
                assertTrue(fixture.jobs.isEmpty())
                assertNotNull(repository.findDelivery(id))
            }
        }
        assertEquals(1, fixture.jobs.size)
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
    }

    @Test fun `one event starts every configured matching pipeline`() = withDb {
        val fixture = installTriggeredPipeline()
        val original = fixture.pipelines.single()
        val second = PipelineRepositoryImpl().add(PipelineRecord(
            name = "Second inbound pipeline", key = "another-key", acceptedInputType = original.acceptedInputType,
            graph = pipelines.graphAsJsonElement(original), triggered = true,
        ))
        fixture.pipelines += original.copy(id = second.id, key = second.key)
        service.savePair(input)
        val delivery = receive()
        fixture.dispatchAll()
        fixture.driveAll()
        val runs = fixture.runs(delivery)
        assertEquals(fixture.pipelines.map { it.id }.toSet(), runs.map { it.pipelineId }.toSet())
        assertTrue(runs.all { it.status == PipelineRunStatus.OK })
        assertEquals(2, DeliveryProbe.observed.size)
    }

    @Test fun `failed event publication can be retried under the original delivery ID`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        fixture.failPublication = true
        assertFailsWith<IllegalStateException> { receive(id = id) }
        val delivery = assertNotNull(repository.findDelivery(id))
        assertTrue(fixture.runs(delivery).isEmpty())
        fixture.failPublication = false
        assertEquals(delivery, receive(id = id))
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(PipelineRunStatus.OK, fixture.runs(delivery).single().status)
        assertEquals(delivery, DeliveryProbe.observed.single().delivery)
    }

    @Test fun `rolled back intake publishes no event job or pipeline run`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        val id = UUID.random().toString()
        assertFailsWith<IllegalStateException> {
            transaction {
                receive(id = id)
                assertTrue(fixture.jobs.isEmpty())
                error("rollback")
            }
        }
        assertNull(repository.findDelivery(id))
        assertTrue(fixture.jobs.isEmpty())
        val retried = receive(id = id)
        fixture.dispatchAll()
        fixture.driveAll()
        assertEquals(PipelineRunStatus.OK, fixture.runs(retried).single().status)
    }

    @Test fun `ignored and fork deliveries dispatch no pipeline events`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        assertTrue(receive(event = "ping").ignored)
        assertTrue(receive(payload(extra = ",\"pull_request\":{\"head\":{\"repo\":{\"id\":999}}}"), "pull_request").ignored)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `unmapped and bot senders remain unattributed in the pipeline input`() = withDb {
        val fixture = installTriggeredPipeline()
        service.savePair(input)
        service.mapUser(7, principalId)
        for (body in listOf(payload(userId = 8), payload(type = "Bot"))) {
            val delivery = receive(body)
            fixture.dispatchAll()
            fixture.driveAll()
            assertEquals(PipelineRunStatus.OK, fixture.runs(delivery).single().status)
            val observed = DeliveryProbe.observed.last()
            assertEquals(delivery, observed.delivery)
            assertNull(observed.delivery.principalId)
            assertEquals(servicePrincipalId, observed.principalId)
        }
    }

    @Test fun `deliveries use the existing triggered pipeline gate`() = withDb {
        val fixture = installTriggeredPipeline()
        fixture.pipelines.clear()
        service.savePair(input)
        val delivery = receive()
        assertFalse(delivery.ignored)
        assertTrue(fixture.jobs.isEmpty())
        assertTrue(fixture.events.isEmpty())
        assertTrue(fixture.runs(delivery).isEmpty())
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try { withContext(manager.asCoroutineContext()) { block() } }
        finally { withContext(NonCancellable) { manager.release() } }
    }
}
