@file:OptIn(bosca.di.annotation.InternalDI::class)

package bosca.artifacts.service

import bosca.artifacts.github.GitHubPublicationException
import bosca.artifacts.github.GitHubReleaseClient
import bosca.artifacts.github.GitHubReleaseTestServer
import bosca.artifacts.model.*
import bosca.artifacts.repository.*
import bosca.db.*
import bosca.di.*
import bosca.lock.DistributedLockFactory
import bosca.lock.nats.NatsDistributedLockFactory
import bosca.pubsub.PubSubService
import bosca.pipelines.service.PipelineSecretService
import bosca.pipelines.service.PipelineSecretServiceImpl
import bosca.pipelines.repository.PipelineSecretRepository
import bosca.pipelines.repository.PipelineSecretRepositoryImpl
import bosca.pipelines.repository.PipelineSecretRepositoryProvider
import bosca.pipelines.repository.*
import bosca.pipelines.configuration.*
import bosca.pipelines.trigger.*
import bosca.pipelines.PipelineEventDispatcher
import bosca.pipelines.node.PipelineRunDriveListener
import bosca.pipelines.service.*
import bosca.pipelines.model.PipelineRunStatus
import bosca.observability.ErrorCapture
import bosca.scheduler.service.SchedulerService
import bosca.events.catalog.EventCatalogRegistrar
import bosca.events.catalog.CoreArtifactsEventCatalogRegistrarProvider
import bosca.sharedqueue.jobs.*
import bosca.sharedqueue.jobs.nats.NatsJobQueueFactory
import bosca.sharedqueue.jobs.configuration.RegisterJobsConfiguration
import bosca.security.model.Principal
import bosca.security.service.SecurityService
import bosca.security.service.ImpersonatedAuthenticationContext
import bosca.serialization.UUID
import bosca.server.BoscaApplication
import bosca.server.config.ApplicationConfig
import bosca.artifacts.pipeline.ArtifactPublicationGetDestinations
import bosca.artifacts.pipeline.ArtifactPublicationNode
import bosca.pipelines.PipelineContext
import bosca.pipelines.PipelineExecutorImpl
import bosca.pipelines.builtin.ForEach
import bosca.pipelines.model.Pipeline
import bosca.pipelines.model.PipelineEdge
import bosca.pipelines.node.InputNode
import bosca.pipelines.node.OutputNode
import bosca.pipelines.node.PipelineNodeSerializers
import bosca.pipelines.node.ArtifactsPipelineNodeSerializersProvider
import bosca.pipelines.node.PipelinesPipelineNodeSerializersProvider
import bosca.pipelines.node.CorePipelinesPipelineNodeSerializersProvider
import bosca.pipelines.service.ExecutionResult
import bosca.pipelines.service.PipelineExecutor
import bosca.pipelines.service.PipelineService
import bosca.pipelines.node.NodeInputs
import bosca.pipelines.node.NodeResult
import bosca.pipelines.node.PipelineValue
import bosca.security.service.AuthenticationContext
import bosca.storage.service.ObjectStorageService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import bosca.test.resources.SharedNatsContainer
import bosca.test.resources.SharedPostgreSQLContainer
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/** Production migrations, generated JDBC/DI and pipeline action, encrypted credentials and HTTP adapter. */
class ArtifactPublicationIntegrationTest {
    private lateinit var postgres: SharedPostgreSQLContainer
    private lateinit var nats: SharedNatsContainer
    private lateinit var pool: ConnectionPool
    private lateinit var remote: GitHubReleaseTestServer
    private lateinit var artifacts: ArtifactRepositoryService
    private lateinit var publications: ArtifactPublicationService
    private lateinit var repository: ArtifactPublicationRepository
    private lateinit var blobs: BlobStorageService
    private lateinit var secrets: PipelineSecretService
    private lateinit var artifact: ArtifactRepository
    private lateinit var version: ArtifactVersion
    private lateinit var destination: ArtifactPublicationDestination
    private val bytes = "original build bytes".toByteArray()
    private val digest = GitHubReleaseTestServer.digest(bytes)
    private val commit = "1".repeat(40)
    private val objects = ConcurrentHashMap<String, ByteArray>()
    private var runner: JobRunner? = null
    private var durablePipelines: PipelineService? = null
    private lateinit var queue: JobQueue
    private val publicationPrincipals = CopyOnWriteArrayList<UUID?>()

    @BeforeTest fun setup() = runBlocking {
        ProviderRegistry.clear()
        postgres = SharedPostgreSQLContainer().withDatabaseName("artifact_publication")
        postgres.start()
        pool = ConnectionPool(ConnectionFactoryImpl(ConnectionConfig(
            url = postgres.jdbcUrl, user = postgres.username, password = postgres.password, maxConnections = 4,
        ), key = "artifact-publication-test"))
        nats = SharedNatsContainer()
        nats.start()
        val natsPool = nats.newConnectionPool(1)
        remote = GitHubReleaseTestServer()
        ProviderRegistry.register(NamespaceRepository::class, NamespaceRepositoryProvider(), true)
        ProviderRegistry.register(NamespacePermissionRepository::class, NamespacePermissionRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepoRepository::class, ArtifactRepoRepositoryProvider(), true)
        ProviderRegistry.register(VersionRepository::class, VersionRepositoryProvider(), true)
        ProviderRegistry.register(TagRepository::class, TagRepositoryProvider(), true)
        ProviderRegistry.register(UploadSessionRepository::class, UploadSessionRepositoryProvider(), true)
        ProviderRegistry.register(BlobRepository::class, BlobRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactPublicationRepository::class, ArtifactPublicationRepositoryProvider(), true)
        ProviderRegistry.register(ArtifactRepositoryService::class, ArtifactRepositoryServiceImplProvider(), true)
        ProviderRegistry.register(BlobStorageService::class, BlobStorageServiceImplProvider(), true)
        ProviderRegistry.register(ArtifactPublicationService::class, ArtifactPublicationServiceImplProvider(), true)
        ProviderRegistry.register(PipelineSecretRepository::class, PipelineSecretRepositoryProvider(), true)
        secrets = PipelineSecretServiceImpl(provide())
        provides<PipelineSecretService>(singleton = true) { secrets }
        provides<ConnectionPool>(singleton = true) { pool }
        provides<bosca.cache.CacheManager> { mockk(relaxed = true) }
        provides<bosca.cache.RequestCacheSerializer> { mockk(relaxed = true) }
        val application = BoscaApplication(ApplicationConfig.load("""
            bosca:
              server:
                development: false
        """.trimIndent().byteInputStream()))
        val storage = mockk<ObjectStorageService>()
        coEvery { storage.setInputStream(any(), any(), any()) } coAnswers {
            val data = secondArg<InputStream>().readBytes()
            objects[firstArg<bosca.storage.service.ObjectPath>().toString()] = data
            data.size.toLong()
        }
        coEvery { storage.getInputStream(any()) } coAnswers {
            ByteArrayInputStream(objects.getValue(firstArg<bosca.storage.service.ObjectPath>().toString()))
        }
        coEvery { storage.getString(any()) } coAnswers {
            objects.getValue(firstArg<bosca.storage.service.ObjectPath>().toString()).decodeToString()
        }
        coEvery { storage.delete(any()) } coAnswers { objects.remove(firstArg<bosca.storage.service.ObjectPath>().toString()) }
        provides<ObjectStorageService>(singleton = true) { storage }
        provides<PubSubService>(singleton = true) { mockk(relaxed = true) }
        val locks = NatsDistributedLockFactory(natsPool)
        provides<DistributedLockFactory>(singleton = true) { locks }
        provides<ArtifactPermissionEvaluator>(singleton = true) { mockk(relaxed = true) }
        provides<GitHubReleaseClient>(singleton = true) {
            GitHubReleaseClient(provide(), apiUrl = remote.base, uploadsUrl = remote.base)
        }
        artifacts = provide()
        publications = provide()
        repository = provide()
        blobs = provide()
        withDb {
            connection().useStatement("CREATE TABLE groups(id UUID PRIMARY KEY); CREATE TYPE permission_action AS ENUM ('view','list','edit','manage','delete','execute')") { it.execute() }
            for (name in ArtifactsMigration().resources) {
                val sql = javaClass.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
                connection().useStatement(sql) { it.execute() }
            }
            connection().useStatement("CREATE SCHEMA pipelines") { it.execute() }
            val secretMigration = PipelineSecretServiceImpl::class.java.getResource("/db/migrations/V15__pipeline_secret.sql")
                ?.readText() ?: error("Missing Pipeline secret migration")
            connection().useStatement(secretMigration) { it.execute() }
            secrets.setSecret("github-token", "test-token")
            artifact = artifacts.findOrCreateRepository("builds", "tool", ArtifactType.RAW)
            version = artifacts.createVersion(artifact.id, "1.0.0")
            blobs.store(digest, ByteArrayInputStream(bytes), bytes.size.toLong())
            artifacts.addVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
            artifacts.completeVersion(version.id, commit, emptyList(), null)
            destination = publications.createDestination(input())
        }
    }

    @AfterTest fun cleanup() = runBlocking {
        runner?.shutdown()
        durablePipelines?.shutdown()
        if (::remote.isInitialized) remote.close()
        if (::pool.isInitialized) pool.close()
        if (::postgres.isInitialized) postgres.stop()
        if (::nats.isInitialized) nats.stop()
        ProviderRegistry.clear()
    }

    private fun input(key: String = "github", enabled: Boolean = true) =
        ArtifactPublicationDestinationInput(artifact.id, key, 123, "acme", "tool", "github-token", "v", enabled)

    private suspend fun configurePublicationPipeline(): Principal {
        ProviderRegistry.register(PipelineManualRunJobExecutor::class, PipelineManualRunJobExecutorProvider(), true)
        ProviderRegistry.register(ExecuteNodeInJobExecutor::class, ExecuteNodeInJobExecutorProvider(), true)
        provides<JobConfigurationEnqueuer>(name = PipelineManualRunJobExecutor.NAME) { PipelineManualRunJobExecutorConfigurationEnqueuer() }
        provides<EventCatalogRegistrar>(name = "CoreArtifacts") { CoreArtifactsEventCatalogRegistrarProvider() }
        provides<PipelineNodeSerializers>(name = "Artifacts") { ArtifactsPipelineNodeSerializersProvider() }
        provides<PipelineNodeSerializers>(name = "CorePipelines") { CorePipelinesPipelineNodeSerializersProvider() }
        provides<PipelineNodeSerializers>(name = "Pipelines") { PipelinesPipelineNodeSerializersProvider() }
        val json = provide<Json>()
        val locks = provide<DistributedLockFactory>()
        val factory = NatsJobQueueFactory(nats.newConnectionPool(1), json, locks, null, emptyList(), false)
        provides<JobQueueFactory> { factory }
        queue = PipelinesConfiguration().pipelinesJobQueue(factory)
        provides<JobQueue>(name = PipelinesJobQueueNames.jobQueue, singleton = true) { queue }
        val caller = Principal(id = UUID.random())
        val security = mockk<SecurityService>()
        coEvery { security.getPrincipalById(caller.id) } returns caller
        coEvery { security.getPrincipalGroups(caller.id) } returns emptyList()
        provides<SecurityService> { security }
        val permissions = mockk<ArtifactPermissionEvaluator>()
        coEvery { permissions.verify(any(), any(), any(), any(), any(), any()) } coAnswers {
            publicationPrincipals.add(firstArg<AuthenticationContext>().principal()?.id)
        }
        ProviderRegistry.register(ArtifactPermissionEvaluator::class, permissions.asProvider(), overrideExisting = true)
        val config = PipelinesRuntimeConfiguration()
        provides<PipelinesRuntimeConfiguration> { config }
        val absentPubsub = mockk<ObjectProvider<PubSubService>> { every { exists } returns false }
        val absentScheduler = mockk<ObjectProvider<SchedulerService>> { every { exists } returns false }
        val pipelines = PipelineServiceImpl(PipelineRepositoryImpl(), PipelinePermissionRepositoryImpl(),
            PipelineExecutorImpl(), security, config, absentPubsub, absentScheduler)
        durablePipelines = pipelines
        provides<PipelineService> { pipelines }
        val pubsub = provide<PubSubService>()
        val results = PipelineRunResultStoreImpl(provide(), json)
        provides<PipelineRunResultStore> { results }
        provides<PipelineRunService>(singleton = true) {
            PipelineRunServiceImpl(PipelineRunRepositoryImpl(), PipelineRunLogRepositoryImpl(), results,
                NodeExecutionRepositoryImpl(), PipelineRunIterationRepositoryImpl(), RollbackRepositoryImpl(),
                pipelines, PipelineExecutorImpl(), security, config, pubsub)
        }
        ProviderRegistry.register(ArtifactPublicationService::class, ArtifactPublicationServiceImplProvider(), true)
        publications = provide()
        provides<NodeSuspensionService> { NodeSuspensionServiceImpl() }
        provides<PipelineRunDriveListener> { PipelineRunDriveListenerImpl(json) }
        provides<ErrorCapture> { ErrorCapture.Noop }
        provides<PipelineEventDispatcher> { PipelineEventDispatcherImpl(null, json) }
        RegisterJobsConfiguration()
        runner = PipelinesConfiguration().pipelinesJobQueueRunner(queue, locks, ErrorCapture.Noop.asProvider())
        for (name in PipelinesMigration().resources.filter { it != "V15__pipeline_secret.sql" }) {
            val sql = PipelineRunServiceImpl::class.java.getResource("/db/migrations/$name")?.readText() ?: error("Missing migration $name")
            connection().useStatement(sql) { it.execute() }
        }
        val graph = Pipeline(UUID.NIL, ArtifactPublicationService.PUBLICATION_PIPELINE_NAME,
            acceptedInputType = "bosca.artifacts.model.ArtifactPublicationTarget",
            nodes = listOf(InputNode("input", acceptedType = "bosca.artifacts.model.ArtifactPublicationTarget"),
                ArtifactPublicationNode("publish"), OutputNode("output")),
            edges = listOf(PipelineEdge("input-publish", "input", "publish", targetPort = "target"),
                PipelineEdge("publish-output", "publish", "output")))
        pipelines.save(UUID.NIL, graph.name, graph.description, graph.acceptedInputType, false, 0,
            pipelines.graphAsJsonElement(graph))
        return caller
    }

    private suspend fun awaitPublication(runId: UUID) = withTimeout(30_000) {
        val runs = provide<PipelineRunService>()
        while (true) {
            val state = assertNotNull(runs.get(runId))
            assertNotEquals(PipelineRunStatus.FAILED, state.status, state.error)
            if (state.status == PipelineRunStatus.OK) break
            delay(50)
        }
    }

    private suspend fun publishThroughPipeline(): NodeResult {
        val event = ArtifactCompleted(UUID.random(), version.id, listOf(UUID.random()), commit, null)
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val selected = assertIs<NodeResult.Output>(ArtifactPublicationGetDestinations("destinations").run(
            context, NodeInputs(mapOf("artifact" to PipelineValue.of(event, ArtifactCompleted.serializer()))),
        ))
        val targets = context.json.decodeFromJsonElement(ListSerializer(ArtifactPublicationTarget.serializer()),
            assertNotNull(selected.value).encode(context.json))
        assertEquals(1, targets.size)
        return ArtifactPublicationNode("publish").run(
            context, NodeInputs(mapOf("target" to PipelineValue.of(targets.single(), ArtifactPublicationTarget.serializer()))),
        )
    }

    @Test fun `pipeline action reuses the committed manifest publishes and verifies the stored bytes`() = runBlocking {
        lateinit var publication: ArtifactPublication
        withDb {
            transaction {
                publication = publications.prepare(destination.id, version.id, commit)
            }
            assertTrue(assertNotNull(artifacts.getVersion(version.id)).finalized)
            assertEquals(listOf(ArtifactPublicationFile("tool.zip", digest, bytes.size.toLong(), "application/zip")), publication.files)
        }
        withDb { assertIs<NodeResult.Output>(publishThroughPipeline()) }
        withDb {
            val result = assertNotNull(repository.find(publication.id))
            assertNotNull(result.published)
            assertNotNull(result.verified)
            assertEquals(42L, result.releaseId)
            assertEquals(1, result.attempts)
            assertNull(result.error)
            assertEquals(publication.id, publications.prepare(destination.id, version.id, commit).id)
        }
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        val requests = remote.requests.size
        withDb { publications.publish(publication.id) }
        assertEquals(requests, remote.requests.size)
    }

    @Test fun `the ordinary executor connects Get Destinations through For Each to the publication pipeline`() = withDb {
        val mirror = publications.createDestination(input("mirror"))
        ProviderRegistry.register(PipelineNodeSerializers::class, ArtifactsPipelineNodeSerializersProvider(), "Artifacts", true)
        ProviderRegistry.register(PipelineNodeSerializers::class, PipelinesPipelineNodeSerializersProvider(), "Pipelines", true)
        ProviderRegistry.register(PipelineNodeSerializers::class, CorePipelinesPipelineNodeSerializersProvider(), "CorePipelines", true)
        val body = Pipeline(UUID.random(), "Publish artifact", acceptedInputType = "bosca.artifacts.model.ArtifactPublicationTarget",
            nodes = listOf(InputNode("input", acceptedType = "bosca.artifacts.model.ArtifactPublicationTarget"),
                ArtifactPublicationNode("push"), OutputNode("output")),
            edges = listOf(PipelineEdge("input-push", "input", "push", targetPort = "target"),
                PipelineEdge("push-output", "push", "output")))
        val graph = Pipeline(UUID.random(), "Publish completed artifact", acceptedInputType = "bosca.artifacts.model.ArtifactCompleted",
            nodes = listOf(InputNode("input", acceptedType = "bosca.artifacts.model.ArtifactCompleted"),
                ArtifactPublicationGetDestinations("destinations"), ForEach("each", pipelineId = body.id), OutputNode("output")),
            edges = listOf(PipelineEdge("input-destinations", "input", "destinations", targetPort = "artifact"),
                PipelineEdge("destinations-each", "destinations", "each", targetPort = "in"),
                PipelineEdge("each-output", "each", "output")))
        val executor = PipelineExecutorImpl()
        val pipelines = mockk<PipelineService>()
        coEvery { pipelines.get(body.id) } returns body
        provides<PipelineService> { pipelines }
        provides<PipelineExecutor> { executor }
        val context = PipelineContext(AuthenticationContext(null, null), provide())
        val result = assertIs<ExecutionResult.Completed>(executor.execute(graph,
            PipelineValue.of(ArtifactCompleted(UUID.random(), version.id, emptyList(), commit, null), ArtifactCompleted.serializer()), context))
        assertEquals(2, assertNotNull(result.output).encode(context.json).jsonArray.size)
        val published = publications.publications(version.id)
        assertEquals(setOf(destination.id, mirror.id), published.map { it.destinationId }.toSet())
        assertTrue(published.all { it.published != null && it.verified != null })
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        assertEquals(1, remote.uploads)
    }

    @Test fun `on demand CLI publication publishes stored packages through durable jobs under the caller`() = withDb {
        publications.updateDestination(destination.id, destination.version, false)
        val cli = publications.createDestination(input("cli").copy(tagPrefix = "cli-v"))
        remote.tag = "cli-v1.0.0"
        val expected = linkedMapOf(
            "bosca-1.0.0-linux-x86_64.tar.gz" to "linux package".toByteArray(),
            "bosca-1.0.0-macos-arm64.pkg" to "macos package".toByteArray(),
            "SHA256SUMS" to "package checksums".toByteArray(),
            "install.sh" to "installer script".toByteArray(),
        )
        artifacts.deleteVersion(version.id)
        version = artifacts.createVersion(artifact.id, "1.0.0")
        for ((filename, data) in expected) {
            val hash = GitHubReleaseTestServer.digest(data)
            blobs.store(hash, data.inputStream(), data.size.toLong())
            artifacts.addVersionBlob(version.id, hash, "file", filename, "application/octet-stream")
        }
        artifacts.completeVersion(version.id, commit, emptyList(), null)
        val caller = configurePublicationPipeline()
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        val other = publications.createDestination(input("other").copy(tagPrefix = "cli-v"))
        val runId = publications.publish(authentication, cli.id, version.id, commit)
        assertEquals(caller.id, provide<PipelineRunService>().get(runId)?.principalId)
        assertTrue(publications.publications(version.id).isEmpty())
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertEquals(0, remote.uploads)
        assertNotNull(runner).run()
        awaitPublication(runId)
        val result = publications.publications(version.id).single()
        assertEquals(cli.id, result.destinationId)
        assertEquals("cli-v1.0.0", result.tagName)
        assertNotNull(result.verified)
        assertEquals(expected.keys, result.files.map { it.filename }.toSet())
        assertEquals(expected.keys, remote.files.keys)
        for ((filename, data) in expected) assertContentEquals(data, remote.files.getValue(filename))
        assertFalse(publications.publications(version.id).any { it.destinationId == other.id })
        assertTrue(publicationPrincipals.isNotEmpty())
        assertTrue(publicationPrincipals.all { it == caller.id })
        val repeated = publications.publish(authentication, cli.id, version.id, commit)
        assertNotEquals(runId, repeated)
        awaitPublication(repeated)
        val republished = publications.publications(version.id).single()
        assertEquals(result.id, republished.id)
        assertEquals(result.published, republished.published)
        assertNotNull(republished.verified)
        assertEquals(2, republished.attempts)
        assertEquals(expected.size, remote.uploads)
    }

    @Test fun `rolling back an on demand publication preserves verification and enqueues no work`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = publications.publications(version.id).single()
        val caller = configurePublicationPipeline()
        assertFailsWith<IllegalStateException> {
            transaction {
                publications.publish(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, version.id, commit)
                error("rollback")
            }
        }
        assertEquals(original, publications.publications(version.id).single())
        assertTrue(provide<PipelineRunService>().listActive(0, 100).isEmpty())
        assertNull(queue.dequeue(50.milliseconds))
    }

    @Test fun `partial uploads cannot publish or prevent the remaining files from uploading`() = withDb {
        version = artifacts.createVersion(artifact.id, "1.0.1")
        remote.tag = "v1.0.1"
        artifacts.addVersionBlob(version.id, digest, "file", "linux.tar.gz", "application/gzip")
        val caller = configurePublicationPipeline()
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        assertContains(assertFailsWith<IllegalArgumentException> {
            publications.publish(authentication, destination.id, version.id, commit)
        }.message.orEmpty(), "uploads have not completed")
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertTrue(publications.publications(version.id).isEmpty())
        assertTrue(provide<PipelineRunService>().listActive(0, 100).isEmpty())
        val data = "macOS package".toByteArray()
        val hash = GitHubReleaseTestServer.digest(data)
        blobs.store(hash, data.inputStream(), data.size.toLong())
        artifacts.addVersionBlob(version.id, hash, "file", "macos.pkg", "application/octet-stream")
        artifacts.completeVersion(version.id, commit, emptyList(), caller.id)
        val run = publications.publish(authentication, destination.id, version.id, commit)
        assertNotNull(runner).run()
        awaitPublication(run)
        assertEquals(setOf("linux.tar.gz", "macos.pkg"), remote.files.keys)
        assertNotNull(publications.publications(version.id).single().verified)
    }

    @Test fun `wrong commits and tag mismatches leave the version correctable before publication`() = withDb {
        val caller = configurePublicationPipeline()
        val authentication = ImpersonatedAuthenticationContext(caller, emptyList())
        assertFailsWith<IllegalArgumentException> {
            publications.publish(authentication, destination.id, version.id, "2".repeat(40))
        }
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, "2".repeat(40)) }
        remote.commit = "2".repeat(40)
        assertFailsWith<GitHubPublicationException> { publications.prepare(destination.id, version.id, commit) }
        assertFailsWith<GitHubPublicationException> {
            publications.publish(authentication, destination.id, version.id, commit)
        }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertTrue(publications.publications(version.id).isEmpty())
        assertTrue(provide<PipelineRunService>().listActive(0, 100).isEmpty())
        assertEquals(0, remote.creates)
        remote.commit = commit
        val run = publications.publish(authentication, destination.id, version.id, commit)
        assertNotNull(runner).run()
        awaitPublication(run)
        assertEquals(commit, publications.publications(version.id).single().commitSha)
        assertNotNull(publications.publications(version.id).single().verified)
    }

    @Test fun `republishing preserves verification while queued and reuses the original prerelease choice`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit, prerelease = true)
        publications.publish(publication.id)
        val original = publications.publications(version.id).single()
        val caller = configurePublicationPipeline() // Current body has prerelease = false.
        val run = publications.publish(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, version.id, commit)
        assertEquals(original, publications.publications(version.id).single())
        assertNotNull(runner).run()
        awaitPublication(run)
        val repeated = publications.publications(version.id).single()
        assertTrue(repeated.prerelease)
        assertEquals(original.id, repeated.id)
        assertEquals(original.files, repeated.files)
        assertEquals(original.published, repeated.published)
        assertTrue(assertNotNull(repeated.verified) > assertNotNull(original.verified))
        assertEquals(2, repeated.attempts)
        assertNull(repeated.error)
        assertEquals(1, remote.uploads)
    }

    @Test fun `a failed repeat records its attempt and error while retaining earlier success and can retry`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        remote.assetDigests["tool.zip"] = "sha256:" + "0".repeat(64)
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id, reverify = true) }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(original.published, failed.published)
        assertEquals(original.verified, failed.verified)
        assertEquals(original.releaseId, failed.releaseId)
        assertEquals(2, failed.attempts)
        assertContains(assertNotNull(failed.error), "content differs")
        remote.assetDigests.clear()
        publications.publish(publication.id)
        val retried = assertNotNull(repository.find(publication.id))
        assertEquals(3, retried.attempts)
        assertEquals(original.published, retried.published)
        assertTrue(assertNotNull(retried.verified) > assertNotNull(original.verified))
        assertNull(retried.error)
        assertEquals(1, remote.uploads)
    }

    @Test fun `a stale failure cannot overwrite a newer attempt or verification`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        transaction { repository.verified(publication.id) }
        val newerVerification = assertNotNull(repository.find(publication.id))
        assertEquals(0, repository.failed(publication.id, "stale failure", original.attempts, original.modified))
        assertEquals(newerVerification, repository.find(publication.id))
        transaction {
            repository.attempt(publication.id)
            repository.verified(publication.id)
        }
        val newerAttempt = assertNotNull(repository.find(publication.id))
        assertEquals(0, repository.failed(publication.id, "stale failure", newerVerification.attempts, newerVerification.modified))
        assertEquals(newerAttempt, repository.find(publication.id))
    }

    @Test fun `overlapping failed repeats count both attempts and retain the newer error`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        remote.assetDigests["tool.zip"] = "sha256:" + "0".repeat(64)
        val firstFailed = CompletableDeferred<Unit>()
        val secondFailed = CompletableDeferred<Unit>()
        val firstWritten = CompletableDeferred<Unit>()
        val failures = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<Int>()
        val gatedRepository = object : ArtifactPublicationRepository by repository {
            override suspend fun failed(id: UUID, error: String, expectedAttempts: Int, expectedModified: bosca.serialization.OffsetDateTime?): Int {
                failures.add(error)
                if (failures.size == 1) {
                    firstFailed.complete(Unit)
                    secondFailed.await()
                } else {
                    secondFailed.complete(Unit)
                    firstWritten.await()
                }
                val result = repository.failed(id, error, expectedAttempts, expectedModified)
                writes.add(result)
                if (writes.size == 1) afterCommit { firstWritten.complete(Unit) }
                return result
            }
        }
        val service = ArtifactPublicationServiceImpl(gatedRepository, artifacts, blobs, secrets, provide<GitHubReleaseClient>(), mockk(), mockk())
        withTimeout(20_000) {
            coroutineScope {
                val first = async {
                    withSeparateConnection {
                        assertFailsWith<GitHubPublicationException> { service.publish(publication.id, reverify = true) }
                    }
                }
                firstFailed.await()
                remote.commit = "2".repeat(40)
                val second = async {
                    withSeparateConnection {
                        assertFailsWith<GitHubPublicationException> { service.publish(publication.id, reverify = true) }
                    }
                }
                first.await()
                second.await()
            }
        }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(original.attempts + 2, failed.attempts)
        assertEquals(listOf(0, 1), writes.toList())
        assertContains(failures[0], "content differs")
        assertContains(failures[1], "tag does not point")
        assertEquals(failures[1], failed.error)
        assertEquals(original.published, failed.published)
        assertEquals(original.verified, failed.verified)
        assertEquals(1, remote.uploads)
    }

    @Test fun `an overlapping older failure cannot overwrite a newer successful repeat`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        remote.assetDigests["tool.zip"] = "sha256:" + "0".repeat(64)
        val failureStarted = CompletableDeferred<Unit>()
        val successFinished = CompletableDeferred<Unit>()
        val writes = CopyOnWriteArrayList<Int>()
        val gatedRepository = object : ArtifactPublicationRepository by repository {
            override suspend fun failed(id: UUID, error: String, expectedAttempts: Int, expectedModified: bosca.serialization.OffsetDateTime?): Int {
                failureStarted.complete(Unit)
                successFinished.await()
                return repository.failed(id, error, expectedAttempts, expectedModified).also { writes.add(it) }
            }
        }
        val service = ArtifactPublicationServiceImpl(gatedRepository, artifacts, blobs, secrets, provide<GitHubReleaseClient>(), mockk(), mockk())
        withTimeout(20_000) {
            coroutineScope {
                val first = async {
                    withSeparateConnection {
                        assertFailsWith<GitHubPublicationException> { service.publish(publication.id, reverify = true) }
                    }
                }
                failureStarted.await()
                remote.assetDigests.clear()
                withSeparateConnection { service.publish(publication.id, reverify = true) }
                successFinished.complete(Unit)
                first.await()
            }
        }
        val verified = assertNotNull(repository.find(publication.id))
        assertEquals(original.attempts + 2, verified.attempts)
        assertEquals(listOf(0), writes.toList())
        assertNull(verified.error)
        assertTrue(assertNotNull(verified.verified) > assertNotNull(original.verified))
        assertEquals(original.published, verified.published)
        assertEquals(original.releaseId, verified.releaseId)
        assertEquals(1, remote.uploads)
    }

    @Test fun `an older publication cannot verify over a newer failed repeat`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        val firstPublished = CompletableDeferred<Unit>()
        val failureFinished = CompletableDeferred<Unit>()
        val gatedRepository = object : ArtifactPublicationRepository by repository {
            override suspend fun published(id: UUID, releaseId: Long): ArtifactPublication? {
                val result = repository.published(id, releaseId)
                afterCommit {
                    firstPublished.complete(Unit)
                    failureFinished.await()
                }
                return result
            }
        }
        val service = ArtifactPublicationServiceImpl(gatedRepository, artifacts, blobs, secrets, provide<GitHubReleaseClient>(), mockk(), mockk())
        withTimeout(20_000) {
            coroutineScope {
                val first = async { withSeparateConnection { service.publish(publication.id, reverify = true) } }
                firstPublished.await()
                remote.assetDigests["tool.zip"] = "sha256:" + "0".repeat(64)
                withSeparateConnection {
                    assertFailsWith<GitHubPublicationException> { service.publish(publication.id, reverify = true) }
                }
                failureFinished.complete(Unit)
                first.await()
            }
        }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(original.attempts + 2, failed.attempts)
        assertContains(assertNotNull(failed.error), "content differs")
        assertEquals(original.published, failed.published)
        assertEquals(original.verified, failed.verified)
        assertEquals(1, remote.uploads)
    }

    @Test fun `a cancelled repeat retains its attempt number and remains retryable after prior verification`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        val cancellation = CancellationException("cancelled")
        val github = mockk<GitHubReleaseClient>()
        coEvery { github.publish(any(), any(), any()) } throws cancellation
        val service = ArtifactPublicationServiceImpl(repository, artifacts, blobs, secrets, github, mockk(), mockk())
        assertSame(cancellation, assertFailsWith<CancellationException> { service.publish(publication.id, reverify = true) })
        val pending = assertNotNull(repository.find(publication.id))
        assertEquals(original.attempts + 1, pending.attempts)
        assertEquals(original.verified, pending.verified)
        assertEquals(original.published, pending.published)
        assertNull(pending.error)
        assertTrue(assertNotNull(pending.modified) > assertNotNull(pending.verified))
        publications.publish(publication.id)
        val retried = assertNotNull(repository.find(publication.id))
        assertEquals(original.attempts + 2, retried.attempts)
        assertNull(retried.error)
        assertTrue(assertNotNull(retried.verified) > assertNotNull(original.verified))
        assertEquals(original.published, retried.published)
        assertEquals(1, remote.uploads)
    }

    @Test fun `a delayed cancelled attempt remains retryable after another attempt verifies`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val transactionStarted = CompletableDeferred<Unit>()
        val newerVerificationFinished = CompletableDeferred<Unit>()
        var first = true
        val delayedArtifacts = object : ArtifactRepositoryService by artifacts {
            override suspend fun finalizeVersion(id: UUID): ArtifactVersion {
                if (!first) return artifacts.finalizeVersion(id)
                first = false
                // A nested transaction starts PostgreSQL's clock before the version row is locked.
                return transaction {
                    transactionStarted.complete(Unit)
                    newerVerificationFinished.await()
                    artifacts.finalizeVersion(id)
                }
            }
        }
        val github = mockk<GitHubReleaseClient>()
        coEvery { github.publish(any(), any(), any()) } throws CancellationException("cancelled")
        val delayed = ArtifactPublicationServiceImpl(repository, delayedArtifacts, blobs, secrets, github, mockk(), mockk())
        withTimeout(20_000) {
            coroutineScope {
                val earlier = async {
                    withSeparateConnection {
                        assertFailsWith<CancellationException> { delayed.publish(publication.id, reverify = true) }
                    }
                }
                transactionStarted.await()
                try {
                    withSeparateConnection { publications.publish(publication.id, reverify = true) }
                } finally {
                    newerVerificationFinished.complete(Unit)
                }
                earlier.await()
            }
        }
        val pending = assertNotNull(repository.find(publication.id))
        assertEquals(3, pending.attempts)
        assertEquals(2, pending.verifiedAttempt)
        assertNull(pending.error)
        assertTrue(assertNotNull(pending.modified).isBefore(assertNotNull(pending.verified)))
        val callsBeforeRetry = remote.requests.size
        publications.publish(publication.id)
        val retried = assertNotNull(repository.find(publication.id))
        assertEquals(4, retried.attempts)
        assertEquals(retried.attempts, retried.verifiedAttempt)
        assertTrue(remote.requests.size > callsBeforeRetry)
        assertEquals(pending.published, retried.published)
        assertEquals(1, remote.uploads)
        val callsAfterRetry = remote.requests.size
        publications.publish(publication.id)
        assertEquals(callsAfterRetry, remote.requests.size)
        assertEquals(retried, repository.find(publication.id))
    }

    @Test fun `verification migration preserves confirmed success and retries ambiguous historical states`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val migration = javaClass.getResource("/db/migrations/V10__artifact_publication_verified_attempt.sql")
            ?.readText() ?: error("Missing V10")
        val verified = java.time.OffsetDateTime.parse("2026-10-13T12:00:00Z")
        data class HistoricalState(
            val verified: java.time.OffsetDateTime?,
            val modified: java.time.OffsetDateTime,
            val error: String?,
            val expectedAttempt: Int?,
        )
        for (state in listOf(
            HistoricalState(verified, verified, null, 3),
            HistoricalState(null, verified, null, null),
            HistoricalState(verified, verified.plusSeconds(1), null, null),
            HistoricalState(verified, verified.minusSeconds(1), null, null),
            HistoricalState(verified, verified, "Verification failed", null),
        )) {
            connection().useStatement("ALTER TABLE artifacts.publications DROP COLUMN verified_attempt") { it.execute() }
            connection().useStatement("UPDATE artifacts.publications SET attempts = 3, verified = ?, modified = ?, error = ? WHERE id = ?") {
                it.setObject(1, state.verified)
                it.setObject(2, state.modified)
                it.setString(3, state.error)
                it.setObject(4, java.util.UUID.fromString(publication.id.toString()))
                it.executeUpdate()
            }
            connection().useStatement(migration) { it.execute() }
            val migrated = assertNotNull(repository.find(publication.id))
            assertEquals(state.expectedAttempt, migrated.verifiedAttempt, state.toString())
            assertEquals(state.verified, migrated.verified)
            val callsBeforeRetry = remote.requests.size
            publications.publish(publication.id)
            val result = assertNotNull(repository.find(publication.id))
            assertEquals(if (state.expectedAttempt == null) 4 else 3, result.attempts)
            assertEquals(result.attempts, result.verifiedAttempt)
            assertNull(result.error)
            assertEquals(state.expectedAttempt == null, remote.requests.size > callsBeforeRetry)
        }
        assertEquals(1, remote.uploads)
    }

    @Test fun `repeat verification failures retain the previous verification and record only one new attempt`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val original = assertNotNull(repository.find(publication.id))
        val delegate = remote.server.dispatcher
        var inspections = 0
        remote.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.url.encodedPath == "/repos/acme/tool/releases/42/assets" && request.url.queryParameter("page") == "1") {
                    inspections++
                    if (inspections == 2) return MockResponse.Builder().code(503).body("{}").build()
                }
                return delegate.dispatch(request)
            }
        }
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id, reverify = true) }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(2, failed.attempts)
        assertEquals(original.published, failed.published)
        assertEquals(original.verified, failed.verified)
        assertContains(assertNotNull(failed.error), "503")
        publications.publish(publication.id)
        val result = assertNotNull(repository.find(publication.id))
        assertEquals(3, result.attempts)
        assertNull(result.error)
        assertTrue(assertNotNull(result.verified) > assertNotNull(original.verified))
        assertEquals(1, remote.uploads)
    }

    @Test fun `manual confirmation redelivery preserves a finalized version's recorded build commit`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        val files = artifacts.getVersionBlobs(version.id)
        assertEquals(commit, artifacts.confirmVersionCompletion(version.id, commit, files, null).completedCommitSha)
        assertFailsWith<IllegalArgumentException> { artifacts.confirmVersionCompletion(version.id, "2".repeat(40), files, null) }
        assertEquals(commit, assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
    }

    @Test fun `stored unpublished versions can confirm completion after migration and publish without uploading again`() = withDb {
        val files = artifacts.getVersionBlobs(version.id)
        connection().useStatement("ALTER TABLE artifacts.versions DROP COLUMN completed_commit_sha") { it.execute() }
        val migration = javaClass.getResource("/db/migrations/V9__artifact_completion.sql")?.readText() ?: error("Missing V9")
        connection().useStatement(migration) { it.execute() }
        assertNull(assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        val caller = configurePublicationPipeline()
        val completed = artifacts.confirmVersionCompletion(version.id, commit, files, caller.id)
        assertEquals(commit, completed.completedCommitSha)
        assertFalse(completed.finalized)
        val run = publications.publish(ImpersonatedAuthenticationContext(caller, emptyList()), destination.id, version.id, commit)
        assertNotNull(runner).run()
        awaitPublication(run)
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
        assertEquals(files, artifacts.getVersionBlobs(version.id))
        assertNotNull(publications.publications(version.id).single().verified)
    }

    @Test fun `manual completion rejects empty duplicate changed and cross-version manifests`() = withDb {
        val files = artifacts.getVersionBlobs(version.id)
        for (snapshot in listOf(emptyList(), files + files, files.map { it.copy(versionId = UUID.random()) })) {
            assertFailsWith<IllegalArgumentException> { artifacts.confirmVersionCompletion(version.id, commit, snapshot, null) }
        }
        val extra = "extra file".toByteArray()
        val hash = GitHubReleaseTestServer.digest(extra)
        blobs.store(hash, extra.inputStream(), extra.size.toLong())
        artifacts.addVersionBlob(version.id, hash, "file", "extra.zip", "application/zip")
        assertFailsWith<IllegalArgumentException> { artifacts.confirmVersionCompletion(version.id, commit, files, null) }
        assertNull(assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        val completed = artifacts.confirmVersionCompletion(version.id, commit, artifacts.getVersionBlobs(version.id).reversed(), null)
        assertEquals(commit, completed.completedCommitSha)
    }

    @Test fun `manual completion rolls back on invalid commits and non-raw versions`() = withDb {
        connection().useStatement("UPDATE artifacts.versions SET completed_commit_sha = NULL WHERE id = '${version.id}'") { it.execute() }
        val files = artifacts.getVersionBlobs(version.id)
        assertFailsWith<IllegalArgumentException> { artifacts.confirmVersionCompletion(version.id, "main", files, null) }
        assertNull(assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        val docker = artifacts.findOrCreateRepository("images", "tool", ArtifactType.DOCKER)
        val dockerVersion = artifacts.createVersion(docker.id, "1.0.0")
        artifacts.addVersionBlob(dockerVersion.id, digest, "manifest", "manifest.json", "application/json")
        assertFailsWith<IllegalArgumentException> {
            artifacts.confirmVersionCompletion(dockerVersion.id, commit, artifacts.getVersionBlobs(dockerVersion.id), null)
        }
        assertNull(assertNotNull(artifacts.getVersion(dockerVersion.id)).completedCommitSha)
    }

    @Test fun `manifest changes clear completion while identical upload redelivery preserves it`() = withDb {
        artifacts.addVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        artifacts.addOrReplaceVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        assertEquals(commit, assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        val next = "changed stored bytes".toByteArray()
        val hash = GitHubReleaseTestServer.digest(next)
        blobs.store(hash, next.inputStream(), next.size.toLong())
        artifacts.addOrReplaceVersionBlob(version.id, hash, "file", "tool.zip", "application/zip")
        assertNull(assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        artifacts.completeVersion(version.id, commit, emptyList(), null)
        val publication = publications.prepare(destination.id, version.id, commit)
        assertEquals(hash, publication.files.single().digest)
    }

    @Test fun `completion migration backfills published versions and leaves unpublished versions incomplete`() = withDb {
        publications.prepare(destination.id, version.id, commit)
        val unpublished = artifacts.createVersion(artifact.id, "1.0.1")
        artifacts.addVersionBlob(unpublished.id, digest, "file", "tool.zip", "application/zip")
        connection().useStatement("ALTER TABLE artifacts.versions DROP COLUMN completed_commit_sha") { it.execute() }
        val migration = javaClass.getResource("/db/migrations/V9__artifact_completion.sql")?.readText() ?: error("Missing V9")
        connection().useStatement(migration) { it.execute() }
        assertEquals(commit, assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        assertNull(assertNotNull(artifacts.getVersion(unpublished.id)).completedCommitSha)
        val other = publications.createDestination(input("other").copy(tagPrefix = "cli-v"))
        assertEquals(commit, publications.prepare(other.id, version.id, commit).commitSha)
    }

    @Test fun `finalized versions without a recorded commit record it on completion and then keep it`() = withDb {
        val dispatcher = mockk<PipelineEventDispatcher>(relaxed = true)
        provides<PipelineEventDispatcher> { dispatcher }
        publications.prepare(destination.id, version.id, commit)
        connection().useStatement("UPDATE artifacts.versions SET completed_commit_sha = NULL") { it.execute() }
        artifacts.completeVersion(version.id, commit, emptyList(), null)
        assertEquals(commit, assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        artifacts.completeVersion(version.id, commit, emptyList(), null)
        coVerify(exactly = 2) { dispatcher.dispatch(any(), any<ArtifactCompleted>(), any()) }
        artifacts.completeVersion(version.id, "2".repeat(40), listOf(UUID.random()), null)
        assertEquals(commit, assertNotNull(artifacts.getVersion(version.id)).completedCommitSha)
        coVerify(exactly = 2) { dispatcher.dispatch(any(), any<ArtifactCompleted>(), any()) }
    }

    @Test fun `completion rollback restores readiness and the original manifest`() = withDb {
        val incomplete = artifacts.createVersion(artifact.id, "1.0.1")
        artifacts.addVersionBlob(incomplete.id, digest, "file", "tool.zip", "application/zip")
        assertFailsWith<IllegalStateException> {
            transaction {
                artifacts.completeVersion(incomplete.id, commit, emptyList(), null)
                assertEquals(commit, assertNotNull(artifacts.getVersion(incomplete.id)).completedCommitSha)
                error("rollback")
            }
        }
        assertNull(assertNotNull(artifacts.getVersion(incomplete.id)).completedCommitSha)
    }

    @Test fun `outer rollback leaves no finalized version or publication`() = runBlocking {
        withDb {
            assertFailsWith<IllegalStateException> {
                transaction {
                    publications.prepare(destination.id, version.id, commit)
                    error("roll back")
                }
            }
            assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
            assertTrue(publications.publications(version.id).isEmpty())
        }
    }

    @Test fun `disabled destinations prevent finalization and publication until enabled`() = withDb {
        destination = publications.updateDestination(destination.id, destination.version, false)
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        destination = publications.updateDestination(destination.id, destination.version, true)
        val publication = publications.prepare(destination.id, version.id, commit)
        destination = publications.updateDestination(destination.id, destination.version, false)
        assertFailsWith<IllegalStateException> { publications.publish(publication.id) }
        assertNull(assertNotNull(repository.find(publication.id)).published)
    }

    @Test fun `response loss retries reuse original stored bytes and completed remote assets`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        remote.uploadFailures = 1
        remote.loseUploadResponse = true
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id) }
        val failed = assertNotNull(repository.find(publication.id))
        assertEquals(1, failed.attempts)
        assertNull(failed.published)
        assertNull(failed.verified)
        assertEquals("GitHub artifact publication failed: HTTP 502", failed.error)
        assertTrue(assertNotNull(artifacts.getVersion(version.id)).finalized)
        publications.publish(publication.id)
        assertNotNull(assertNotNull(repository.find(publication.id)).verified)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.creates)
        assertEquals(0, remote.deletes)
        assertContentEquals(bytes, remote.files.getValue("tool.zip"))
    }

    @Test fun `publication success remains durable when independent verification fails`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        remote.verificationFailures = 1
        assertFailsWith<GitHubPublicationException> { publications.publish(publication.id) }
        val published = assertNotNull(repository.find(publication.id))
        assertNotNull(published.published)
        assertEquals(42L, published.releaseId)
        assertNull(published.verified)
        assertEquals(1, published.attempts)
        assertNotNull(published.error)
        publications.publish(publication.id)
        val verified = assertNotNull(repository.find(publication.id))
        assertEquals(published.published, verified.published)
        assertNotNull(verified.verified)
        assertNull(verified.error)
        assertEquals(2, verified.attempts)
        assertEquals(1, remote.uploads)
        assertEquals(1, remote.publications)
    }

    @Test fun `fixed manifests reject changed bytes new files metadata and different publication inputs`() = withDb {
        publications.prepare(destination.id, version.id, commit)
        artifacts.addVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        artifacts.addOrReplaceVersionBlob(version.id, digest, "file", "tool.zip", "application/zip")
        assertFailsWith<IllegalArgumentException> { artifacts.addOrReplaceVersionBlob(version.id, digest, "file", "tool.zip", "text/plain") }
        assertFailsWith<IllegalArgumentException> { artifacts.addVersionBlob(version.id, digest, "other", "other.zip", "application/zip") }
        assertFailsWith<IllegalArgumentException> { artifacts.addOrReplaceVersionBlob(version.id, "sha256:" + "0".repeat(64), "file", "tool.zip", "application/zip") }
        assertEquals(1, assertNotNull(blobs.get(digest)).refCount)
        assertEquals(1, artifacts.getVersionBlobs(version.id).size)
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, "2".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, version.id, commit, true) }
        assertEquals(1, publications.publications(version.id).size)
    }

    @Test fun `unrequested fixed path uploads remain replaceable`() = withDb {
        val nextBytes = "next build bytes".toByteArray()
        val nextDigest = GitHubReleaseTestServer.digest(nextBytes)
        blobs.store(nextDigest, ByteArrayInputStream(nextBytes), nextBytes.size.toLong())
        artifacts.addOrReplaceVersionBlob(version.id, nextDigest, "file", "tool.zip", "application/zip")
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertEquals(nextDigest, artifacts.getVersionBlobs(version.id).single().digest)
        assertNull(blobs.get(digest))
    }

    @Test fun `preparing one selected destination does not create publications for other matches`() = withDb {
        val second = publications.createDestination(input("mirror"))
        val first = publications.prepare(destination.id, version.id, commit)
        assertEquals(listOf(first.id), publications.publications(version.id).map { it.id })
        assertNull(repository.find(second.id, version.id))
        val mirror = publications.prepare(second.id, version.id, commit)
        assertEquals(2, publications.publications(version.id).size)
        assertEquals(first.id, publications.prepare(destination.id, version.id, commit).id)
        assertEquals(mirror.id, publications.prepare(second.id, version.id, commit).id)
    }

    @Test fun `a destination cannot accept an artifact from a different repository`() = withDb {
        val other = artifacts.findOrCreateRepository("builds", "other", ArtifactType.RAW)
        val unrelated = publications.createDestination(input("unrelated").copy(repositoryId = other.id))
        assertFailsWith<IllegalArgumentException> { publications.prepare(unrelated.id, version.id, commit) }
        assertFalse(assertNotNull(artifacts.getVersion(version.id)).finalized)
        assertTrue(publications.publications(version.id).isEmpty())
    }

    @Test fun `changing destinations preserves the version's original commit and prerelease choice`() = withDb {
        val original = publications.prepare(destination.id, version.id, commit)
        destination = publications.updateDestination(destination.id, destination.version, false)
        val second = publications.createDestination(input("replacement"))
        assertFailsWith<IllegalArgumentException> { publications.prepare(second.id, version.id, "2".repeat(40)) }
        assertFailsWith<IllegalArgumentException> { publications.prepare(second.id, version.id, commit, true) }
        val replacement = publications.prepare(second.id, version.id, commit)
        assertEquals(second.id, replacement.destinationId)
        assertEquals(original.commitSha, replacement.commitSha)
        assertEquals(original.prerelease, replacement.prerelease)
        assertEquals(original.files, replacement.files)
    }

    @Test fun `pipeline retries retain the publication identity and reuse remote assets after response loss`() = withDb {
        remote.uploadFailures = 1
        remote.loseUploadResponse = true
        assertFailsWith<GitHubPublicationException> { publishThroughPipeline() }
        val original = publications.publications(version.id).single()
        assertNull(original.verified)
        assertIs<NodeResult.Output>(publishThroughPipeline())
        val retried = publications.publications(version.id).single()
        assertEquals(original.id, retried.id)
        assertNotNull(retried.verified)
        assertEquals(1, remote.creates)
        assertEquals(1, remote.uploads)
    }

    @Test fun `existing encrypted Pipeline secrets are reused rotated and referenced under destination version comparisons`() = withDb {
        val stored = assertNotNull(PipelineSecretRepositoryImpl().findByName(destination.tokenSecretName))
        assertFalse(stored.encryptedValue.contains("test-token"))
        assertEquals(destination, publications.destinations(artifact.id).single())
        assertNotNull(destination.created)
        secrets.setSecret("rotated-secret", "rotated-token")
        val rotated = publications.updateDestination(destination.id, 0, true, "rotated-secret")
        assertEquals(1, rotated.version)
        assertEquals("rotated-secret", rotated.tokenSecretName)
        assertFailsWith<IllegalStateException> { publications.updateDestination(destination.id, 0, false, "stale-secret") }
        val publication = publications.prepare(destination.id, version.id, commit)
        val before = remote.requests.size
        publications.publish(publication.id)
        assertTrue(remote.requests.drop(before).all { it.headers["Authorization"] == "Bearer rotated-token" })
    }

    @Test fun `secret value rotation is resolved at publication time without changing the destination`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        secrets.setSecret(destination.tokenSecretName, "new-secret-value")
        val before = remote.requests.size
        publications.publish(publication.id)
        assertTrue(remote.requests.drop(before).all { it.headers["Authorization"] == "Bearer new-secret-value" })
        assertEquals(destination, publications.destinations(artifact.id).single())
    }

    @Test fun `missing credentials fail before a remote release is created`() = withDb {
        assertFailsWith<IllegalStateException> {
            publications.createDestination(input().copy(tokenSecretName = "missing-secret"))
        }
        val publication = publications.prepare(destination.id, version.id, commit)
        secrets.deleteSecret(destination.tokenSecretName)
        assertFailsWith<IllegalStateException> { publications.publish(publication.id) }
        assertEquals(0, remote.creates)
        assertNull(assertNotNull(repository.find(publication.id)).verified)
    }

    @Test fun `invalid filenames and media types roll back finalization`() = withDb {
        for ((name, media) in listOf("bad name.zip" to "application/zip", "tool.zip" to "bad type")) {
            val invalid = artifacts.createVersion(artifact.id, UUID.random().toString())
            artifacts.addVersionBlob(invalid.id, digest, "file", name, media)
            artifacts.completeVersion(invalid.id, commit, emptyList(), null)
            assertFailsWith<IllegalArgumentException> { publications.prepare(destination.id, invalid.id, commit) }
            assertFalse(assertNotNull(artifacts.getVersion(invalid.id)).finalized)
            assertTrue(publications.publications(invalid.id).isEmpty())
        }
    }

    @Test fun `explicit local deletion cancels publication work without deleting the remote release`() = withDb {
        val publication = publications.prepare(destination.id, version.id, commit)
        publications.publish(publication.id)
        artifacts.deleteVersion(version.id)
        assertNull(repository.find(publication.id))
        val requests = remote.requests.size
        publications.publish(publication.id)
        assertEquals(requests, remote.requests.size)
        assertTrue(remote.hasRelease)
        assertEquals(0, remote.deletes)
    }

    private fun withDb(block: suspend () -> Unit) = runBlocking { withSeparateConnection(block) }

    private suspend fun withSeparateConnection(block: suspend () -> Unit) {
        val manager = pool.connection()
        try { withContext(manager.asCoroutineContext()) { block() } }
        finally { withContext(NonCancellable) { manager.release() } }
    }
}
