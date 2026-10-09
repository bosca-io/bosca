@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.sync

import bosca.artifacts.model.*
import bosca.artifacts.repository.*
import bosca.artifacts.service.*
import bosca.cache.CacheManager
import bosca.cache.RequestCacheSerializer
import bosca.db.*
import bosca.di.*
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.observability.ErrorCapture
import bosca.artifacts.pipeline.*
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.CoreArtifactsEventCatalogRegistrarProvider
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.configuration.*
import bosca.pipelines.model.*
import bosca.pipelines.node.*
import bosca.pipelines.repository.*
import bosca.pipelines.service.*
import bosca.pipelines.trigger.*
import bosca.scheduler.service.SchedulerService
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.security.service.AuthenticationContext
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.pubsub.PubSubService
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.sharedqueue.jobs.*
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.storage.service.ObjectPath
import bosca.storage.service.ObjectStorageService
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Credentials
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/** Real Postgres and NATS: normal tag event, pipeline matching, durable action and GHCR copying. */
class ArtifactSyncIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var nats: SharedNatsContainer
    private lateinit var pool: ConnectionPool
    private lateinit var remote: GhcrTestServer
    private lateinit var artifacts: ArtifactRepositoryService
    private lateinit var syncing: ArtifactSyncService
    private lateinit var secrets: PipelineSecretService
    private lateinit var queue: JobQueue
    private lateinit var artifact: ArtifactRepository
    private lateinit var destination: ArtifactSyncDestination
    private lateinit var version: ArtifactVersion
    private lateinit var pipelines: PipelineService
    private lateinit var trigger: Pipeline
    private lateinit var body: Pipeline
    private lateinit var caller: Principal
    private val pushPrincipals = java.util.concurrent.CopyOnWriteArrayList<UUID?>()
    private lateinit var runner: JobRunner
    private lateinit var pubsub: PubSubService
    private var runnerStarted = false
    private val fixture = ImageFixture()
    private val objects = ConcurrentHashMap<String, ByteArray>()

    @BeforeTest
    fun setup() = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("artifact_sync")
        postgres.start()
        pool = ConnectionPool(
            ConnectionFactoryImpl(
                ConnectionConfig(
                    postgres.jdbcUrl, postgres.username, postgres.password, maxConnections = 6,
                ), key = "artifact-sync-test"
            )
        )
        nats = SharedNatsContainer()
        nats.start()
        remote = GhcrTestServer()
        val application = BoscaApplication(ApplicationConfig.load("bosca:\n  server:\n    development: false\n".byteInputStream()))
        // Test KSP emits its own empty module registrar; use the production generated providers.
        ProviderRegistry.register(NamespaceRepository::class, NamespaceRepositoryProvider(), true)
        ProviderRegistry.register(NamespacePermissionRepository::class, NamespacePermissionRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepoRepository::class, ArtifactRepoRepositoryProvider(), true)
        ProviderRegistry.register(VersionRepository::class, VersionRepositoryProvider(), true)
        ProviderRegistry.register(TagRepository::class, TagRepositoryProvider(), true)
        ProviderRegistry.register(UploadSessionRepository::class, UploadSessionRepositoryProvider(), true)
        ProviderRegistry.register(BlobRepository::class, BlobRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactSyncRepository::class, ArtifactSyncRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepositoryService::class, ArtifactRepositoryServiceImplProvider(), true)
        ProviderRegistry.register(BlobStorageService::class, BlobStorageServiceImplProvider(), true)
        ProviderRegistry.register(ArtifactSyncService::class, ArtifactSyncServiceImplProvider(), true)
        ProviderRegistry.register(PipelineDispatchJobExecutor::class, PipelineDispatchJobExecutorProvider(), true)
        ProviderRegistry.register(PipelineRunJobExecutor::class, PipelineRunJobExecutorProvider(), true)
        ProviderRegistry.register(PipelineManualRunJobExecutor::class, PipelineManualRunJobExecutorProvider(), true)
        ProviderRegistry.register(PipelineChildRunJobExecutor::class, PipelineChildRunJobExecutorProvider(), true)
        ProviderRegistry.register(ExecuteNodeInJobExecutor::class, ExecuteNodeInJobExecutorProvider(), true)
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { PipelineChildRunJobExecutorConfigurationEnqueuer() }
        provides<JobConfigurationEnqueuer>(name = PipelineManualRunJobExecutor.NAME) { PipelineManualRunJobExecutorConfigurationEnqueuer() }
        provides<EventCatalogRegistrar>(name = "CoreArtifacts") { CoreArtifactsEventCatalogRegistrarProvider() }
        provides<PipelineNodeSerializers>(name = "Artifacts") { ArtifactsPipelineNodeSerializersProvider() }
        provides<PipelineNodeSerializers>(name = "CorePipelines") { CorePipelinesPipelineNodeSerializersProvider() }
        provides<PipelineNodeSerializers>(name = "Pipelines") { PipelinesPipelineNodeSerializersProvider() }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<CacheManager> { mockk(relaxed = true) }
        provides<RequestCacheSerializer> { mockk(relaxed = true) }
        pubsub = mockk(relaxed = true)
        provides<PubSubService> { pubsub }
        provides<ErrorCapture> { ErrorCapture.Noop }
        val storage = mockk<ObjectStorageService>()
        coEvery { storage.setInputStream(any(), any(), any()) } coAnswers {
            val bytes = secondArg<InputStream>().readBytes()
            objects[firstArg<ObjectPath>().toString()] = bytes
            bytes.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } coAnswers {
            ByteArrayInputStream(objects.getValue(firstArg<ObjectPath>().toString()))
        }
        coEvery { storage.getString(any()) } coAnswers {
            objects.getValue(firstArg<ObjectPath>().toString()).decodeToString()
        }
        coEvery { storage.delete(any()) } coAnswers { objects.remove(firstArg<ObjectPath>().toString()); Unit }
        provides<ObjectStorageService> { storage }
        secrets = PipelineSecretServiceImpl(PipelineSecretRepositoryImpl())
        provides<PipelineSecretService>(singleton = true) { secrets }
        val natsPool = nats.newConnectionPool(1)
        val locks = NatsDistributedLockFactory(natsPool)
        provides<DistributedLockFactory>(singleton = true) { locks }
        provides<JobQueueFactory>(singleton = true) {
            NatsJobQueueFactory(natsPool, application.json, locks, null, emptyList(), false)
        }
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue, singleton = true) {
            PipelinesConfiguration().pipelinesJobQueue(provide())
        }
        provides<GhcrClient>(singleton = true) { GhcrClient(provide(), registryUrl = remote.base) }
        artifacts = provide()
        queue = provide(PipelinesJobQueueNames.jobQueue)
        val config = PipelinesRuntimeConfiguration()
        val security = mockk<SecurityService>()
        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns UUID.random()
        coEvery { security.getPrincipalByIdentifier(any()) } returns principal
        caller = Principal(id = UUID.random())
        coEvery { security.getPrincipalById(any()) } returns caller
        coEvery { security.getPrincipalGroups(any<UUID>()) } returns emptyList()
        provides<SecurityService> { security }
        provides<PipelinesRuntimeConfiguration> { config }
        val permissions = mockk<ArtifactPermissionEvaluator>()
        coEvery { permissions.verify(any(), any(), any(), any(), any(), any()) } coAnswers {
            pushPrincipals.add(firstArg<AuthenticationContext>().principal()?.id)
        }
        provides<ArtifactPermissionEvaluator> { permissions }
        val absentPubsub = mockk<ObjectProvider<PubSubService>> { every { exists } returns false }
        val absentScheduler = mockk<ObjectProvider<SchedulerService>> { every { exists } returns false }
        pipelines = PipelineServiceImpl(PipelineRepositoryImpl(), PipelinePermissionRepositoryImpl(),
            PipelineExecutorImpl(), security, config, absentPubsub, absentScheduler)
        provides<PipelineService> { pipelines }
        val results = PipelineRunResultStoreImpl(storage, application.json)
        provides<PipelineRunResultStore> { results }
        provides<PipelineRunService>(singleton = true) {
            PipelineRunServiceImpl(PipelineRunRepositoryImpl(), PipelineRunLogRepositoryImpl(), results,
                NodeExecutionRepositoryImpl(), PipelineRunIterationRepositoryImpl(), RollbackRepositoryImpl(),
                pipelines, PipelineExecutorImpl(), security, config, pubsub)
        }
        provides<NodeSuspensionService> { NodeSuspensionServiceImpl() }
        provides<PipelineRunDriveListener> { PipelineRunDriveListenerImpl(application.json) }
        // Use the producer-only dispatcher exactly as the standalone artifact server does.
        provides<PipelineEventDispatcher> { PipelineEventDispatcherImpl(null, application.json) }
        RegisterJobsConfiguration()
        syncing = provide()
        runner = PipelinesConfiguration().pipelinesJobQueueRunner(queue, locks, ErrorCapture.Noop.asProvider())
        withDb {
            connection().useStatement("CREATE TABLE groups(id UUID PRIMARY KEY); CREATE TYPE permission_action AS ENUM ('view','list','edit','manage','delete','execute')") { it.execute() }
            for (name in ArtifactsMigration().resources) {
                val sql = javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
                connection().useStatement(sql) { it.execute() }
            }
            for (name in PipelinesMigration().resources) {
                val sql = PipelineRunServiceImpl::class.java.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
                connection().useStatement(sql) { it.execute() }
            }
            body = savePipeline(Pipeline(id = UUID.NIL, name = ArtifactSyncService.PUSH_PIPELINE_NAME,
                acceptedInputType = ArtifactSyncTarget::class.qualifiedName.orEmpty(),
                nodes = listOf(InputNode("input", acceptedType = ArtifactSyncTarget::class.qualifiedName.orEmpty()), ArtifactSyncNode("sync"), OutputNode("output")),
                edges = listOf(PipelineEdge("in-sync", "input", "sync", targetPort = "target"), PipelineEdge("sync-out", "sync", "output"))))
            trigger = savePipeline(Pipeline(id = UUID.NIL, name = "Docker tag sync", triggered = true,
                acceptedInputType = ArtifactTagPublished::class.qualifiedName.orEmpty(),
                nodes = listOf(InputNode("input", acceptedType = ArtifactTagPublished::class.qualifiedName.orEmpty()),
                    ArtifactSyncGetDestinations("destinations"), ForEach("each", pipelineId = body.id, continueOnError = true), OutputNode("output")),
                edges = listOf(PipelineEdge("in-dest", "input", "destinations", targetPort = "artifact"),
                    PipelineEdge("dest-each", "destinations", "each"), PipelineEdge("each-out", "each", "output"))))
            secrets.setSecret("ghcr-token", "credential")
            artifact = artifacts.findOrCreateRepository("images", "server", ArtifactType.DOCKER)
            val blobs = provide<BlobStorageService>()
            for ((digest, bytes) in fixture.bytes) blobs.store(digest, ByteArrayInputStream(bytes), bytes.size.toLong())
            version = storeVersion(fixture.index)
            destination = syncing.createDestination(input())
        }
    }

    @AfterTest
    fun cleanup() = runBlocking {
        if (::runner.isInitialized) runner.shutdown()
        if (::pipelines.isInitialized) pipelines.shutdown()
        if (::remote.isInitialized) remote.close()
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
        if (::nats.isInitialized) nats.stop()
        ProviderRegistry.clear()
    }

    private fun input(enabled: Boolean = true) =
        ArtifactSyncDestinationInput(artifact.id, "ghcr", "acme/server", "acme", "ghcr-token", enabled)

    private suspend fun storeVersion(digest: String, mediaType: String = "application/vnd.oci.image.index.v1+json"): ArtifactVersion {
        val version = artifacts.createVersion(artifact.id, digest)
        artifacts.addVersionBlob(version.id, digest, "manifest", null, mediaType)
        return version
    }

    private suspend fun publish(digest: String = fixture.index) = transaction {
        artifacts.setTag(artifact.id, "latest", digest)
    }

    private suspend fun savePipeline(pipeline: Pipeline) = pipelines.save(
        id = UUID.NIL, name = pipeline.name, description = pipeline.description,
        acceptedInputType = pipeline.acceptedInputType, triggered = pipeline.triggered, version = 0,
        graph = pipelines.graphAsJsonElement(pipeline))

    private suspend fun runPipelines(expectedRuns: Int = 1) {
        if (!runnerStarted) {
            runner.run()
            runnerStarted = true
        }
        withTimeout(45_000) {
            while (true) {
                val completed = connection().useStatement("SELECT count(*) FROM pipelines.pipeline_run WHERE pipeline_id = ?::uuid AND status = 'ok'") {
                    it.setString(1, trigger.id.toString())
                    it.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
                }
                if (completed >= expectedRuns) break
                delay(100)
            }
        }
    }

    @Test
    fun `normal tag publication defers the pipeline event until commit and copies through durable pipeline jobs`() = withDb {
        transaction {
            publish()
            assertNull(queue.dequeue(50.milliseconds))
            assertTrue(remote.tags.isEmpty())
        }
        runPipelines()
        val state = syncing.syncs(artifact.id).single()
        assertNotNull(state.synced)
        assertEquals(1, state.attempts)
        assertNull(state.error)
        assertEquals(fixture.index, remote.tags["latest"])
        assertEquals(destination.remoteRepository, syncing.destinations(artifact.id).single().remoteRepository)
        val stored = assertNotNull(PipelineSecretRepositoryImpl().findByName("ghcr-token"))
        assertFalse(stored.encryptedValue.contains("credential"))
    }

    @Test
    fun `publication pipelines retain the stored OCI media type when the root omits it`() = withDb {
        val root = fixture.withoutMediaType(fixture.manifest)
        val bytes = fixture.bytes.getValue(root)
        provide<BlobStorageService>().store(root, ByteArrayInputStream(bytes), bytes.size.toLong())
        storeVersion(root, "application/vnd.oci.image.manifest.v1+json")
        publish(root)
        runPipelines()
        assertNotNull(syncing.syncs(artifact.id).single().synced)
        assertEquals(root, remote.tags["latest"])
        assertContentEquals(bytes, remote.manifests[root])
        assertEquals("application/vnd.oci.image.manifest.v1+json", remote.requests.single {
            it.method == "PUT" && it.url.encodedPath.endsWith("/manifests/latest")
        }.headers["Content-Type"])
    }

    @Test
    fun `publication pipelines preserve foreign layers missing from Bosca storage`() = withDb {
        val root = fixture.withLayer("application/vnd.docker.image.rootfs.foreign.diff.tar.gzip", listOf("https://example.org/base-layer"),
            "application/vnd.docker.distribution.manifest.v2+json")
        val bytes = fixture.bytes.getValue(root)
        provide<BlobStorageService>().store(root, ByteArrayInputStream(bytes), bytes.size.toLong())
        storeVersion(root, "application/vnd.docker.distribution.manifest.v2+json")
        // Remove the cached layer to exercise an external-only reference.
        provide<BlobStorageService>().deleteIfUnreferenced(fixture.layer)
        assertNull(provide<BlobStorageService>().get(fixture.layer))
        publish(root)
        runPipelines()
        assertNotNull(syncing.syncs(artifact.id).single().synced)
        assertEquals(root, remote.tags["latest"])
        assertContentEquals(bytes, remote.manifests[root])
        assertFalse(remote.blobs.containsKey(fixture.layer))
    }

    @Test
    fun `rolled back publishing leaves neither desired state nor queued work`() = withDb {
        assertFailsWith<IllegalStateException> {
            transaction { publish(); error("rollback") }
        }
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertNull(queue.dequeue(50.milliseconds))
        assertNull(artifacts.findTag(artifact.id, "latest"))
    }

    @Test
    fun `the production job runner automatically retries a failed remote push`() = withDb {
        remote.tagFailures = 1
        publish()
        runPipelines()
        val state = syncing.syncs(artifact.id).single()
        assertEquals(2, state.attempts)
        assertNull(state.error)
        assertEquals(fixture.index, remote.tags["latest"])
    }

    @Test
    fun `queued deliveries resolve the latest digest including a return to an earlier image`() = withDb {
        val second = storeVersion(fixture.manifest)
        publish()
        publish(second.version)
        runPipelines(2)
        val originalId = syncing.syncs(artifact.id).single().id
        assertEquals(fixture.manifest, remote.tags["latest"])
        assertEquals(second.id, syncing.syncs(artifact.id).single().versionId)
        publish(fixture.index)
        runPipelines(3)
        assertEquals(originalId, syncing.syncs(artifact.id).single().id)
        assertEquals(fixture.index, remote.tags["latest"])
        assertEquals(1, syncing.syncs(artifact.id).single().attempts)
    }

    @Test
    fun `remote failure records safe error and retry recovers with rotated credentials`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        remote.tagFailures = 1
        val selected = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val inputs = NodeInputs(mapOf("target" to PipelineValue.of(selected, ArtifactSyncTarget.serializer())))
        assertFailsWith<GhcrException> { ArtifactSyncNode("sync").run(context, inputs) }
        val failed = syncing.syncs(artifact.id).single()
        assertNull(failed.synced)
        assertEquals("GHCR artifact sync failed: HTTP 503 while assigning the image tag", failed.error)
        secrets.setSecret("ghcr-token", "rotated")
        syncing.retry(failed.id)
        runPipelines(2)
        val state = syncing.syncs(artifact.id).single()
        assertEquals(2, state.attempts)
        assertNotNull(state.synced)
        assertNull(state.error)
        assertEquals(Credentials.basic("acme", "rotated"), remote.requests.last { it.url.encodedPath == "/token" }.headers["Authorization"])
    }

    @Test
    fun `disabled destinations do not queue and local deletion cancels pending work`() = withDb {
        destination = syncing.updateDestination(destination.id, 0, false, null, null, null, null)
        publish()
        runPipelines()
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        destination = syncing.updateDestination(destination.id, 1, true, null, null, null, null)
        assertFailsWith<IllegalStateException> { syncing.updateDestination(destination.id, 0, false, null, null, null, null) }
        publish()
        artifacts.deleteVersion(version.id)
        runPipelines(2)
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `destination deletion rejects stale versions and cancels its queued work while preserving other destinations`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val target = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)
        val queued = assertNotNull(syncing.prepare(target))
        val other = syncing.createDestination(input().copy(key = "other", remoteRepository = "acme/other"))
        val otherSync = assertNotNull(syncing.prepare(target.copy(destinationId = other.id, remoteRepository = other.remoteRepository)))
        destination = syncing.updateDestination(destination.id, 0, true, null, null, null, null)

        assertFailsWith<IllegalStateException> { syncing.deleteDestination(destination.id, 0) }
        assertEquals(2, syncing.destinations(artifact.id).size)
        assertEquals(2, syncing.syncs(artifact.id).size)
        syncing.deleteDestination(destination.id, destination.version)

        assertEquals(listOf(other.id), syncing.destinations(artifact.id).map { it.id })
        assertEquals(listOf(otherSync.id), syncing.syncs(artifact.id).map { it.id })
        assertNull(syncing.prepare(target))
        syncing.sync(queued.id)
        assertTrue(remote.requests.isEmpty())
        assertNotNull(artifacts.getRepository(artifact.id))
        assertNotNull(artifacts.getVersion(version.id))
        assertEquals(fixture.index, artifacts.findTag(artifact.id, "latest")?.manifestDigest)
        assertEquals("credential", secrets.resolve("ghcr-token"))
        val replacement = syncing.createDestination(input())
        assertNotEquals(destination.id, replacement.id)
        assertFailsWith<IllegalStateException> { syncing.deleteDestination(destination.id, destination.version) }
    }

    @Test
    fun `destination deletion preserves previously synced remote images`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val queued = assertNotNull(syncing.prepare(ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)))
        syncing.sync(queued.id)
        assertNotNull(syncing.syncs(artifact.id).single().synced)
        val requestCount = remote.requests.size

        syncing.deleteDestination(destination.id, destination.version)

        assertTrue(syncing.destinations(artifact.id).isEmpty())
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertEquals(fixture.index, remote.tags["latest"])
        assertEquals(requestCount, remote.requests.size)
    }

    @Test
    fun `renaming a destination preserves completed syncs and rejects stale image path edits`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val target = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)
        val queued = assertNotNull(syncing.prepare(target))
        syncing.sync(queued.id)
        val completed = syncing.syncs(artifact.id).single()
        assertNotNull(completed.synced)

        val renamed = syncing.updateDestination(destination.id, 0, true, null, null, "renamed", null)

        assertEquals(destination.id, renamed.id)
        assertEquals("renamed", renamed.key)
        assertEquals(destination.remoteRepository, renamed.remoteRepository)
        assertEquals(1L, renamed.version)
        assertEquals(completed, syncing.syncs(artifact.id).single())
        assertFailsWith<IllegalStateException> {
            syncing.updateDestination(destination.id, 0, true, null, null, "stale", "acme/stale")
        }
        assertEquals(renamed, syncing.destinations(artifact.id).single())
        assertEquals(completed, syncing.syncs(artifact.id).single())
    }

    @Test
    fun `editing the image path clears only its previous results and copies future syncs to the new path`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        artifacts.setTag(artifact.id, "pending", fixture.index)
        val target = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)
        val completed = assertNotNull(syncing.prepare(target))
        syncing.sync(completed.id)
        val pending = assertNotNull(syncing.prepare(target.copy(tagName = "pending")))
        val other = syncing.createDestination(input().copy(key = "other", remoteRepository = "acme/other"))
        val otherSync = assertNotNull(syncing.prepare(target.copy(destinationId = other.id, remoteRepository = other.remoteRepository)))
        val requestCount = remote.requests.size

        val edited = syncing.updateDestination(destination.id, 0, true, null, null, "renamed", "acme/renamed")

        assertEquals(destination.id, edited.id)
        assertEquals("renamed", edited.key)
        assertEquals("acme/renamed", edited.remoteRepository)
        assertEquals(1L, edited.version)
        assertEquals(listOf(otherSync.id), syncing.syncs(artifact.id).map { it.id })
        syncing.sync(pending.id)
        assertEquals(requestCount, remote.requests.size)
        assertEquals(fixture.index, remote.tags["latest"])
        assertNotNull(artifacts.getVersion(version.id))
        assertEquals("credential", secrets.resolve("ghcr-token"))

        assertNull(syncing.prepare(target))
        val fresh = assertNotNull(syncing.prepare(target.copy(remoteRepository = edited.remoteRepository)))
        assertNotEquals(completed.id, fresh.id)
        syncing.sync(fresh.id)
        assertNotNull(syncing.syncs(artifact.id).single { it.destinationId == destination.id }.synced)
        assertEquals("repository:acme/renamed:pull,push", remote.requests.last { it.url.encodedPath == "/token" }.url.queryParameter("scope"))
        assertTrue(remote.requests.any { it.method == "PUT" && it.url.encodedPath == "/v2/acme/renamed/manifests/latest" })
        assertFalse(remote.requests.any { it.method == "DELETE" })
    }

    @Test
    fun `invalid and duplicate destination edits preserve configuration and sync records`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val queued = assertNotNull(syncing.prepare(ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)))
        val other = syncing.createDestination(input().copy(key = "other"))
        for (key in listOf("", "invalid name", "a".repeat(101))) {
            assertFailsWith<IllegalArgumentException> {
                syncing.updateDestination(destination.id, 0, true, null, null, key, null)
            }
        }
        for (path in listOf("", "Acme/server", "../server", "server", "acme//server", "acme/server:tag")) {
            assertFailsWith<IllegalArgumentException> {
                syncing.updateDestination(destination.id, 0, true, null, null, null, path)
            }
        }
        assertFailsWith<java.sql.SQLException> {
            syncing.updateDestination(destination.id, 0, true, null, null, other.key, "acme/renamed")
        }
        assertEquals(destination, syncing.destinations(artifact.id).single { it.id == destination.id })
        assertEquals(listOf(queued.id), syncing.syncs(artifact.id).map { it.id })
    }

    @Test
    fun `on demand pushes create the first sync for one destination and can push a completed tag again under the caller`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val other = syncing.createDestination(input().copy(key = "other", remoteRepository = "acme/other"))
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        val runs = provide<PipelineRunService>()
        assertTrue(syncing.syncs(artifact.id).isEmpty())

        val runId = syncing.push(authentication, destination.id, "latest")

        assertEquals(caller.id, runs.get(runId)?.principalId)
        assertTrue(remote.requests.isEmpty())
        runner.run()
        runnerStarted = true
        awaitPush(runId)
        val first = syncing.syncs(artifact.id).single()
        assertEquals(destination.id, first.destinationId)
        assertNotNull(first.synced)
        assertEquals(fixture.index, remote.tags["latest"])
        assertTrue(pushPrincipals.isNotEmpty())
        assertTrue(pushPrincipals.all { it == caller.id })
        assertFalse(remote.requests.any { it.url.encodedPath.startsWith("/v2/${other.remoteRepository}/") })

        val repeated = syncing.push(authentication, destination.id, "latest")
        assertNotEquals(runId, repeated)
        awaitPush(repeated)
        assertEquals(first.id, syncing.syncs(artifact.id).single().id)
        assertEquals(2, syncing.syncs(artifact.id).single().attempts)
        assertEquals(2, remote.requests.count { it.method == "PUT" && it.url.encodedPath == "/v2/acme/server/manifests/latest" })
    }

    @Test
    fun `on demand pushes reject unavailable destinations tags secrets and pipelines without creating syncs`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        assertFailsWith<NoSuchElementException> { syncing.push(authentication, UUID.random(), "latest") }
        assertFailsWith<NoSuchElementException> { syncing.push(authentication, destination.id, "missing") }
        val disabled = syncing.updateDestination(destination.id, 0, false, null, null, null, null)
        assertFailsWith<IllegalStateException> { syncing.push(authentication, disabled.id, "latest") }
        destination = syncing.updateDestination(disabled.id, disabled.version, true, null, null, null, null)
        secrets.deleteSecret("ghcr-token")
        assertFailsWith<IllegalStateException> { syncing.push(authentication, destination.id, "latest") }
        secrets.setSecret("ghcr-token", "credential")
        pipelines.delete(body.id)
        val missing = assertFailsWith<IllegalStateException> { syncing.push(authentication, destination.id, "latest") }
        assertContains(missing.message.orEmpty(), "Install 'Default Artifact Sync Pipelines' in System > Packages")
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(provide<PipelineRunService>().listActive(0, 100).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `ambiguous on demand pipelines report how to select the intended sync graph`() = withDb {
        pipelines.delete(trigger.id)
        pipelines.delete(body.id)
        savePipeline(body.copy(id = UUID.NIL, name = "First Docker sync"))
        savePipeline(body.copy(id = UUID.NIL, name = "Second Docker sync"))
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val failure = assertFailsWith<IllegalStateException> {
            syncing.push(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, "latest")
        }
        assertContains(failure.message.orEmpty(), "Multiple Docker sync pipelines")
        assertContains(failure.message.orEmpty(), "Give exactly one the name '${ArtifactSyncService.PUSH_PIPELINE_NAME}'")
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(provide<PipelineRunService>().listActive(0, 100).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `rolling back an on demand push removes its sync and durable run`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        var runId: UUID? = null
        assertFailsWith<IllegalStateException> {
            transaction {
                runId = syncing.push(authentication, destination.id, "latest")
                error("rollback")
            }
        }
        assertNull(provide<PipelineRunService>().get(assertNotNull(runId)))
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `on demand push returns before the polling window and delivery waits for the outer transaction`() = withDb {
        pipelines.delete(trigger.id)
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        val runId = transaction {
            artifacts.setTag(artifact.id, "latest", fixture.index)
            val queued = withTimeout(2000) { syncing.push(authentication, destination.id, "latest") }
            assertEquals(PipelineRunStatus.RUNNING, provide<PipelineRunService>().get(queued)?.status)
            assertNull(queue.dequeue(50.milliseconds))
            queued
        }
        runner.run()
        runnerStarted = true
        awaitPush(runId)
        assertNotNull(syncing.syncs(artifact.id).single().synced)
    }

    @Test
    fun `queued on demand pushes skip edited image paths and a new push uses the edited path`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        val oldRun = syncing.push(authentication, destination.id, "latest")
        val edited = syncing.updateDestination(destination.id, destination.version, true, null, null, null, "acme/edited")
        runner.run()
        runnerStarted = true
        awaitPush(oldRun)
        assertEquals(JsonPrimitive("SKIPPED"), provide<PipelineRunService>().get(oldRun)?.output)
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())

        val newRun = syncing.push(authentication, edited.id, "latest")
        awaitPush(newRun)
        assertNotNull(syncing.syncs(artifact.id).single().synced)
        assertEquals(1, remote.requests.count { it.method == "PUT" && it.url.encodedPath == "/v2/acme/edited/manifests/latest" })
        assertFalse(remote.requests.any { it.url.encodedPath.startsWith("/v2/${destination.remoteRepository}/") })
    }

    @Test
    fun `obsolete on demand tags remove pending status after the run skips them`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val newer = storeVersion(fixture.manifest)
        val runId = syncing.push(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, "latest")
        assertEquals(fixture.index, syncing.syncs(artifact.id).single().manifestDigest)
        artifacts.setTag(artifact.id, "latest", newer.version)
        runner.run()
        runnerStarted = true
        awaitPush(runId)
        assertEquals(JsonPrimitive("SKIPPED"), provide<PipelineRunService>().get(runId)?.output)
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `obsolete on demand runs preserve a newer pending digest`() = withDb {
        pipelines.delete(trigger.id)
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val runId = syncing.push(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, "latest")
        val newer = storeVersion(fixture.manifest)
        artifacts.setTag(artifact.id, "latest", newer.version)
        val pending = assertNotNull(syncing.prepare(ArtifactSyncTarget(destination.id, newer.id, "latest", newer.version, destination.remoteRepository)))
        runner.run()
        runnerStarted = true
        awaitPush(runId)
        assertEquals(listOf(pending), syncing.syncs(artifact.id))
        assertTrue(remote.requests.isEmpty())
        syncing.sync(pending.id)
        assertEquals(newer.version, remote.tags["latest"])
        assertNotNull(syncing.syncs(artifact.id).single().synced)
    }

    @Test
    fun `sync discards a tag that moves after preparation`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val queued = assertNotNull(syncing.prepare(ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)))
        val newer = storeVersion(fixture.manifest)
        artifacts.setTag(artifact.id, "latest", newer.version)
        syncing.sync(queued.id)
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())
    }

    @Test
    fun `obsolete target cleanup preserves completed sync records`() = withDb {
        artifacts.setTag(artifact.id, "latest", fixture.index)
        val target = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index, destination.remoteRepository)
        val queued = assertNotNull(syncing.prepare(target))
        syncing.sync(queued.id)
        val completed = syncing.syncs(artifact.id).single()
        val newer = storeVersion(fixture.manifest)
        artifacts.setTag(artifact.id, "latest", newer.version)
        assertNull(syncing.prepare(target))
        assertEquals(listOf(completed), syncing.syncs(artifact.id))
    }

    private suspend fun awaitPush(runId: UUID) {
        val runs = provide<PipelineRunService>()
        withTimeout(45_000) {
            while (runs.get(runId)?.status?.isTerminal != true) delay(100)
        }
        val run = assertNotNull(runs.get(runId))
        assertEquals(PipelineRunStatus.OK, run.status, run.error)
    }

    @Test
    fun `configuration rejects incompatible repositories and malformed GHCR paths`() = withDb {
        val raw = artifacts.findOrCreateRepository("files", "raw", ArtifactType.RAW)
        assertFailsWith<IllegalArgumentException> { syncing.createDestination(input().copy(repositoryId = raw.id)) }
        for (path in listOf("ghcr.io/Acme/server", "../server", "server", "acme//server", "acme/server:tag")) {
            assertFailsWith<IllegalArgumentException> { syncing.createDestination(input().copy(remoteRepository = path)) }
        }
    }

    @Test
    fun `tag announcements see committed data and rolled back tag writes emit nothing`() = withDb {
        var announcements = 0
        var visible = false
        coEvery { pubsub.publish(ArtifactVersionPublished.CHANNEL, ArtifactVersionPublished.serializer(), any<ArtifactVersionPublished>()) } coAnswers {
            announcements++
            val reader = pool.connection()
            try {
                withContext(reader.asCoroutineContext()) {
                    visible = artifacts.findTag(artifact.id, "committed")?.manifestDigest == fixture.index
                }
            } finally { withContext(NonCancellable) { reader.release() } }
        }
        transaction {
            artifacts.setTag(artifact.id, "committed", fixture.index)
            assertEquals(0, announcements)
        }
        assertEquals(1, announcements)
        assertTrue(visible)
        assertFailsWith<IllegalStateException> {
            transaction { artifacts.setTag(artifact.id, "rollback", fixture.index); error("rollback") }
        }
        assertEquals(1, announcements)
        assertNull(artifacts.findTag(artifact.id, "rollback"))
    }

    @Test
    fun `concurrent first repository creation recovers before the manifest transaction`(): Unit = runBlocking {
        val real = provide<NamespaceRepository>()
        val arrivals = java.util.concurrent.atomic.AtomicInteger()
        val ready = CompletableDeferred<Unit>()
        val namespaces = object : NamespaceRepository by real {
            override suspend fun findByName(name: String): ArtifactNamespace? {
                val found = real.findByName(name)
                if (name == "first-push" && arrivals.incrementAndGet() <= 2) {
                    if (arrivals.get() == 2) ready.complete(Unit)
                    ready.await()
                }
                return found
            }
        }
        val service = ArtifactRepositoryServiceImpl(namespaces, provide(), provide(), provide(), provide(),
            provide(), provide(), provide(), pubsub)
        val results = withTimeout(10_000) {
            (1..2).map {
                async {
                    val manager = pool.connection()
                    try {
                        withContext(manager.asCoroutineContext()) {
                            val created = service.findOrCreateRepository("first-push", "image", ArtifactType.DOCKER)
                            transaction { service.getRepository(created.id) }
                        }
                    } finally { withContext(NonCancellable) { manager.release() } }
                }
            }.awaitAll()
        }
        assertEquals(2, results.size)
        assertEquals(results[0]?.id, results[1]?.id)
    }

    @Test
    fun `duplicate pipeline events serialize the remote tag write`() = withDb {
        publish()
        publish()
        runPipelines(2)
        val state = syncing.syncs(artifact.id).single()
        assertEquals(1, state.attempts)
        assertNotNull(state.synced)
        assertEquals(fixture.index, remote.tags["latest"])
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking {
        val manager = pool.connection()
        try {
            withContext(manager.asCoroutineContext()) { block() }
        } finally {
            withContext(NonCancellable) { manager.release() }
        }
    }
}
