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
        ProviderRegistry.register(PipelineChildRunJobExecutor::class, PipelineChildRunJobExecutorProvider(), true)
        ProviderRegistry.register(ExecuteNodeInJobExecutor::class, ExecuteNodeInJobExecutorProvider(), true)
        provides<JobConfigurationEnqueuer>(name = PipelineChildRunJobExecutor.NAME) { PipelineChildRunJobExecutorConfigurationEnqueuer() }
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
        syncing = provide()
        queue = provide(PipelinesJobQueueNames.jobQueue)
        val config = PipelinesRuntimeConfiguration()
        val security = mockk<SecurityService>()
        val principal = mockk<Principal>(relaxed = true)
        every { principal.id } returns UUID.random()
        coEvery { security.getPrincipalByIdentifier(any()) } returns principal
        coEvery { security.getPrincipalGroups(any<UUID>()) } returns emptyList()
        provides<SecurityService> { security }
        provides<PipelinesRuntimeConfiguration> { config }
        val permissions = mockk<ArtifactPermissionEvaluator>()
        coEvery { permissions.verify(any(), any(), any(), any(), any(), any()) } returns Unit
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
            val body = savePipeline(Pipeline(id = UUID.NIL, name = "Sync body",
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
        val selected = ArtifactSyncTarget(destination.id, version.id, "latest", fixture.index)
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val inputs = NodeInputs(mapOf("target" to PipelineValue.of(selected, ArtifactSyncTarget.serializer())))
        assertFailsWith<GhcrException> { ArtifactSyncNode("sync").run(context, inputs) }
        val failed = syncing.syncs(artifact.id).single()
        assertNull(failed.synced)
        assertEquals("GHCR artifact sync failed: HTTP 503", failed.error)
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
        destination = syncing.updateDestination(destination.id, 0, false, null, null)
        publish()
        runPipelines()
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        destination = syncing.updateDestination(destination.id, 1, true, null, null)
        assertFailsWith<IllegalStateException> { syncing.updateDestination(destination.id, 0, false, null, null) }
        publish()
        artifacts.deleteVersion(version.id)
        runPipelines(2)
        assertTrue(syncing.syncs(artifact.id).isEmpty())
        assertTrue(remote.requests.isEmpty())
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
